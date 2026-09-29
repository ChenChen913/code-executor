package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.error.ApiException;
import com.chenchen.codeexecutor.language.LanguageSpec;
import com.chenchen.codeexecutor.model.ExecutionResponse;
import com.chenchen.codeexecutor.model.ExecutionStatus;
import com.chenchen.codeexecutor.model.PhaseOutcome;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 真刀真枪跑代码的实现：<b>一个请求 = 一个容器，绝不复用</b>。
 *
 * <p>流程：
 * <ol>
 *   <li>取一个容器——优先从预热池里拿已经创建好的，拿不到就现场创建</li>
 *   <li>把源码和 stdin 写进容器的工作目录（一次 exec 写完所有文件）</li>
 *   <li>有编译步骤就先编译，编译不过直接返回，不再运行</li>
 *   <li>运行，同时盯着超时</li>
 *   <li>不管是成功、报错、超时还是抛异常，最后一定删容器</li>
 * </ol>
 *
 * <p><b>关于预热池</b>：池子里放的是"从未被使用过"的容器，用完的容器一律销毁、
 * 永不归还。复用型容器池能多省几十毫秒的销毁时间，但会让容器跨请求存活，
 * 引入跨用户的状态泄漏风险——这个交换不划算。详见 {@link ContainerPool}。
 */
@Component
@ConditionalOnProperty(name = "executor.dry-run", havingValue = "false", matchIfMissing = true)
public class DockerCodeRunner implements CodeRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerCodeRunner.class);

    /** 写源码这一步的超时。只是往内存盘里写几十 KB，正常远小于 1 秒。 */
    private static final int SOURCE_WRITE_TIMEOUT_MS = 10_000;

    private final DockerClient docker;
    private final ExecutorProperties properties;
    private final SandboxPolicy sandbox;
    private final ContainerFactory factory;
    private final ContainerPool pool;

    public DockerCodeRunner(DockerClient docker,
                            ExecutorProperties properties,
                            SandboxPolicy sandbox,
                            ContainerFactory factory,
                            ContainerPool pool) {
        this.docker = docker;
        this.properties = properties;
        this.sandbox = sandbox;
        this.factory = factory;
        this.pool = pool;
    }

    @Override
    public ExecutionResponse run(String jobId, LanguageSpec spec, SourceBundle source) {
        long startedAt = System.nanoTime();
        String containerId = null;
        PhaseOutcome compile = null;
        PhaseOutcome run = null;

        try {
            Acquired acquired = acquireContainer(jobId, spec);
            containerId = acquired.containerId();
            writeFiles(containerId, source);

            if (spec.hasCompileStep()) {
                compile = execPhase(containerId, spec.getCompileCommand(), spec.getCompileTimeoutMs());
            }
            // 编译没过就不要再运行了：既省时间，也让返回给用户的错误更干净
            if (compile == null || compile.ok()) {
                List<String> runCommand = source.hasStdin()
                        ? sandbox.withStdinRedirect(spec.getRunCommand())
                        : spec.getRunCommand();
                run = execPhase(containerId, runCommand, spec.getRunTimeoutMs());
            }

            boolean outOfMemory = isOomKilled(containerId);
            ExecutionStatus status = decide(compile, run, outOfMemory);
            long durationMs = elapsedMillis(startedAt);

            log.info("job={} language={} status={} duration={}ms pooled={}",
                    jobId, spec.getId(), status, durationMs, acquired.pooled());
            return ExecutionResponse.of(jobId, spec.getId(), status, compile, run,
                    durationMs, describe(status, spec, compile));

        } finally {
            // 放在 finally 里是刻意的：正常返回、抛异常、超时，都必须走到这里。
            // 用过的容器一律销毁，绝不归还到池子里——这是"绝不复用"的落实点。
            factory.destroy(containerId);
        }
    }

    /** 这次拿到的容器，以及它是不是来自预热池（只用于日志和观测）。 */
    private record Acquired(String containerId, boolean pooled) {
    }

    /**
     * 取一个容器：优先用预热池里的，拿不到就现场创建。
     *
     * <p>池子是优化不是依赖——池子空了、坏了，服务照样能用，只是慢一点。
     */
    private Acquired acquireContainer(String jobId, LanguageSpec spec) {
        Optional<ContainerPool.Warm> warm = pool.acquire(spec);
        if (warm.isPresent()) {
            return new Acquired(warm.get().containerId(), true);
        }
        // 保命时长按"编译超时 + 运行超时 + 余量"算
        int keepAliveSeconds = spec.totalTimeoutMs() / 1000
                + properties.getContainerLifetimeSlackSeconds();
        return new Acquired(factory.createAndStart(jobId, spec, keepAliveSeconds), false);
    }

    /**
     * 把所有源文件（以及标准输入文件）一次写进容器的工作目录。
     *
     * <p>⚠️ 这里不能用 {@code docker cp}。实测结论：{@code docker cp} 是守护进程按
     * "rootfs 里的路径"写的，看不到容器内部的 tmpfs 挂载，会被 {@code --read-only} 拒绝：
     *
     * <pre>
     * Status 400: {"message":"container rootfs is marked read-only"}
     * </pre>
     *
     * <p>也不用 exec 的 stdin：docker-java 那条路会挂住不返回
     * （见 {@link SandboxPolicy#writeFilesCommand} 里的说明）。
     *
     * <p>最终走的是 {@code sh -c 'while [ $# -ge 2 ]; do printf %s "$1" | base64 -d > "$2"; shift 2; done'}，
     * <b>一次 exec 写完所有文件</b>——多文件场景下不用为每个文件往返一次。
     * 内容以 base64 作为独立参数传入，不经过 shell 解析，也不可能注入。
     */
    private void writeFiles(String containerId, SourceBundle source) {
        List<String> command = sandbox.writeFilesCommand(source.files());

        PhaseOutcome outcome = execPhase(containerId, command, SOURCE_WRITE_TIMEOUT_MS);
        if (outcome.timedOut()) {
            throw ApiException.internal("把源码写入容器超时（" + SOURCE_WRITE_TIMEOUT_MS + "ms）");
        }
        if (!outcome.ok()) {
            throw ApiException.internal("把源码写入容器失败（退出码 " + outcome.exitCode()
                    + "）：" + outcome.stderr().trim());
        }
    }

    /**
     * 执行一个阶段（编译或运行），并盯着超时。
     *
     * <p>命令以数组形式下发，Docker 会直接 execve，中间没有 shell。
     * 这一点很重要：如果拼成 {@code sh -c "javac Main.java"} 这种字符串，
     * 文件名或参数里带上 {@code ;} 就能顺出额外的命令。
     */
    private PhaseOutcome execPhase(String containerId, List<String> command, int timeoutMs) {
        ExecCreateCmdResponse exec = docker.execCreateCmd(containerId)
                .withCmd(command.toArray(new String[0]))
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withWorkingDir(SandboxPolicy.WORKSPACE)
                .exec();

        BoundedOutputStream stdout = new BoundedOutputStream(properties.getMaxOutputBytes());
        BoundedOutputStream stderr = new BoundedOutputStream(properties.getMaxOutputBytes());
        ExecStartResultCallback callback = new ExecStartResultCallback(stdout, stderr);
        boolean finished = false;

        try {
            docker.execStartCmd(exec.getId()).withDetach(false).exec(callback);
            finished = callback.awaitCompletion(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(callback);
        }

        boolean truncated = stdout.isTruncated() || stderr.isTruncated();
        if (!finished) {
            // 超时：直接把整个容器 KILL 掉。
            // 比只杀子进程干净——用户代码可能 fork 出一串子进程，杀一个没用。
            killQuietly(containerId, command);
            return PhaseOutcome.timedOut(stdout.text(), stderr.text(), truncated);
        }

        int exitCode = inspectExitCode(exec.getId());
        return PhaseOutcome.finished(exitCode, stdout.text(), stderr.text(), truncated);
    }

    /**
     * 判断容器是不是被内核 OOM 杀掉的。
     *
     * <p>必须问 Docker，不能只看退出码：内存超限的表现是"进程突然没了、退出码 137"，
     * 而 137 也可能是用户自己 kill -9。只有 OOMKilled 标记能区分开。
     */
    private boolean isOomKilled(String containerId) {
        try {
            return Boolean.TRUE.equals(
                    docker.inspectContainerCmd(containerId).exec().getState().getOOMKilled());
        } catch (RuntimeException e) {
            log.debug("读取容器状态失败，按非 OOM 处理：{}", e.getMessage());
            return false;
        }
    }

    private int inspectExitCode(String execId) {
        try {
            Long exitCode = docker.inspectExecCmd(execId).exec().getExitCodeLong();
            return exitCode == null ? -1 : exitCode.intValue();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static ExecutionStatus decide(PhaseOutcome compile, PhaseOutcome run, boolean outOfMemory) {
        if (outOfMemory) {
            return ExecutionStatus.OUT_OF_MEMORY;
        }
        if ((compile != null && compile.timedOut()) || (run != null && run.timedOut())) {
            return ExecutionStatus.TIMEOUT;
        }
        if (compile != null && !compile.ok()) {
            return ExecutionStatus.COMPILE_ERROR;
        }
        if (run != null && !run.ok()) {
            return ExecutionStatus.RUNTIME_ERROR;
        }
        return ExecutionStatus.SUCCESS;
    }

    private static String describe(ExecutionStatus status, LanguageSpec spec, PhaseOutcome compile) {
        return switch (status) {
            case TIMEOUT -> compile != null && compile.timedOut()
                    ? "编译超时（上限 " + spec.getCompileTimeoutMs() + "ms），容器已终止"
                    : "运行超时（上限 " + spec.getRunTimeoutMs() + "ms），容器已终止";
            case OUT_OF_MEMORY -> "内存超过 " + spec.getMemoryMb() + "MB，容器已被内核终止";
            case COMPILE_ERROR -> "编译失败，详见 compileOutput";
            case RUNTIME_ERROR -> "进程退出码非 0，详见 stderr";
            default -> status.getDescription();
        };
    }

    private void killQuietly(String containerId, List<String> command) {
        try {
            docker.killContainerCmd(containerId).withSignal("KILL").exec();
            log.info("已强制终止超时容器 {}（阶段命令：{}）", containerId, String.join(" ", command));
        } catch (RuntimeException e) {
            log.debug("终止容器 {} 失败（可能已自行退出）：{}", containerId, e.getMessage());
        }
    }

    private static void closeQuietly(ExecStartResultCallback callback) {
        try {
            callback.close();
        } catch (IOException e) {
            // 关流失败不影响结果判断，忽略
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
