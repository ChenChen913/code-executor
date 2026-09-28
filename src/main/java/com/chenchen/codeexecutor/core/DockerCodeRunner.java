package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.error.ApiException;
import com.chenchen.codeexecutor.language.LanguageSpec;
import com.chenchen.codeexecutor.model.ExecutionResponse;
import com.chenchen.codeexecutor.model.ExecutionStatus;
import com.chenchen.codeexecutor.model.PhaseOutcome;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 真刀真枪跑代码的实现：一个请求 = 一个全新容器。
 *
 * <p>流程（对应文档里的业务流程）：
 * <ol>
 *   <li>创建容器（此时还没有代码）</li>
 *   <li>启动容器，它内部只是在 sleep，等着我们塞东西进去</li>
 *   <li>把源码用 tar 流直接写进容器的工作目录——<b>不落宿主机磁盘</b></li>
 *   <li>有编译步骤就先编译，编译不过直接返回，不再运行</li>
 *   <li>运行，同时盯着超时</li>
 *   <li>不管是成功、报错、超时还是抛异常，最后一定删容器</li>
 * </ol>
 *
 * <p>和第 3 步相关的一个取舍：原文的做法是"先写宿主机临时文件，再复制进容器"。
 * 这里改成流式写入，省掉了宿主机临时文件，也就顺带消掉了两个隐患——
 * 文件名被拼成 {@code ../../etc/xxx} 的路径穿越，以及异常路径下临时文件残留。
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

    public DockerCodeRunner(DockerClient docker, ExecutorProperties properties, SandboxPolicy sandbox) {
        this.docker = docker;
        this.properties = properties;
        this.sandbox = sandbox;
    }

    @Override
    public ExecutionResponse run(String jobId, LanguageSpec spec, String code) {
        long startedAt = System.nanoTime();
        String containerId = null;
        PhaseOutcome compile = null;
        PhaseOutcome run = null;

        try {
            containerId = createContainer(jobId, spec);
            docker.startContainerCmd(containerId).exec();
            copySourceIntoContainer(containerId, spec, code.getBytes(StandardCharsets.UTF_8));

            if (spec.hasCompileStep()) {
                compile = execPhase(containerId, spec.getCompileCommand(), spec.getCompileTimeoutMs());
            }
            // 编译没过就不要再运行了：既省时间，也让返回给用户的错误更干净
            if (compile == null || compile.ok()) {
                run = execPhase(containerId, spec.getRunCommand(), spec.getRunTimeoutMs());
            }

            boolean outOfMemory = isOomKilled(containerId);
            ExecutionStatus status = decide(compile, run, outOfMemory);
            long durationMs = elapsedMillis(startedAt);

            log.info("job={} language={} status={} duration={}ms",
                    jobId, spec.getId(), status, durationMs);
            return ExecutionResponse.of(jobId, spec.getId(), status, compile, run,
                    durationMs, describe(status, spec, compile));

        } catch (NotFoundException e) {
            throw ApiException.sandboxUnavailable(
                    "Docker 中找不到镜像 " + properties.getImage()
                            + "。请先在项目根目录执行：docker build -t " + properties.getImage() + " .", e);
        } catch (DockerException e) {
            throw ApiException.sandboxUnavailable(
                    "无法与 Docker 通信：" + e.getMessage() + "。请确认 Docker Desktop 已启动。", e);
        } finally {
            // 放在 finally 里是刻意的：正常返回、抛异常、超时，都必须走到这里
            destroyQuietly(containerId);
        }
    }

    private String createContainer(String jobId, LanguageSpec spec) {
        CreateContainerCmd command = docker.createContainerCmd(properties.getImage())
                .withName("code-executor-" + jobId)
                .withUser(properties.getUser())
                .withWorkingDir(SandboxPolicy.WORKSPACE)
                .withEnv("HOME=/tmp", "TMPDIR=/tmp", "LANG=C.UTF-8")
                .withLabels(sandbox.labels(jobId))
                // 我们不用容器自身的 stdout，所有输出都从 exec 拿
                .withAttachStdout(false)
                .withAttachStderr(false)
                .withCmd("sh", "-c", sandbox.keepAliveCommand(spec))
                .withHostConfig(sandbox.hostConfig(spec));
        return command.exec().getId();
    }

    /**
     * 把源码写进容器。
     *
     * <p>⚠️ 这里不能用 {@code docker cp}。实测结论：{@code docker cp} 是守护进程按
     * "rootfs 里的路径"写的，看不到容器内部的 tmpfs 挂载，会被 {@code --read-only} 拒绝：
     *
     * <pre>
     * Status 400: {"message":"container rootfs is marked read-only"}
     * </pre>
     *
     * <p>也不用 exec 的 stdin：docker-java 那条路会挂住不返回（见
     * {@link SandboxPolicy#sourceWriteCommand}里的说明）。
     *
     * <p>最终走的是 {@code sh -c 'printf %s "$1" | base64 -d > "$2"'}，
     * 源码以 base64 形式作为独立参数传入——不经过 shell 解析，也不可能注入。
     */
    private void copySourceIntoContainer(String containerId, LanguageSpec spec, byte[] code) {
        String base64 = Base64.getEncoder().encodeToString(code);
        List<String> command = sandbox.sourceWriteCommand(spec.getSourceFileName(), base64);

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

    private void destroyQuietly(String containerId) {
        if (containerId == null) {
            return;
        }
        try {
            docker.removeContainerCmd(containerId).withForce(true).exec();
        } catch (RuntimeException e) {
            // 删不掉不算致命，但一定要留日志：静默失败的清理 = 静默的内存泄漏
            log.warn("删除容器 {} 失败，将由回收器兜底：{}", containerId, e.getMessage());
        }
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
