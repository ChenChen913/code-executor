package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.error.ApiException;
import com.chenchen.codeexecutor.language.LanguageRegistry;
import com.chenchen.codeexecutor.language.LanguageSpec;
import com.chenchen.codeexecutor.model.ExecutionRequest;
import com.chenchen.codeexecutor.model.ExecutionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 执行入口：做校验、限流，然后把活交给 {@link CodeRunner}。
 *
 * <p>这里同时服务两条路径，它们的排队策略<b>刻意不同</b>：
 *
 * <table border="1">
 *   <caption>同步 vs 异步</caption>
 *   <tr><th></th><th>拿不到名额时</th><th>为什么</th></tr>
 *   <tr>
 *     <td>同步 {@link #execute}</td>
 *     <td>立刻返回 429</td>
 *     <td>调用方在等结果，让它挂 30 秒比让它快速失败再重试更糟</td>
 *   </tr>
 *   <tr>
 *     <td>异步 {@link #executeQueued}</td>
 *     <td>排队等待（有上限）</td>
 *     <td>调用方已经拿到 taskId 走了，排队对它没有体验损失</td>
 *   </tr>
 * </table>
 *
 * <p>两条路径共用同一个信号量——否则异步任务可以把同步请求饿死。
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final LanguageRegistry languageRegistry;
    private final CodeRunner codeRunner;
    private final ExecutorProperties properties;
    private final Semaphore slots;

    public ExecutionService(LanguageRegistry languageRegistry,
                            CodeRunner codeRunner,
                            ExecutorProperties properties) {
        this.languageRegistry = languageRegistry;
        this.codeRunner = codeRunner;
        this.properties = properties;
        this.slots = new Semaphore(Math.max(1, properties.getMaxConcurrentExecutions()));
    }

    /** 校验过、随时可以执行的一次请求。 */
    public record PreparedExecution(LanguageSpec spec, SourceBundle source) {
    }

    /**
     * 校验并组装请求，但不占用任何资源。
     *
     * <p>把这一步单独拆出来，是为了让异步路径能<b>在入队时就完成校验</b>——
     * 一个语言名拼错的请求应该立刻收到 400，而不是先拿到一个 taskId，
     * 轮询半天之后才发现任务失败了。
     */
    public PreparedExecution prepare(ExecutionRequest request) {
        LanguageSpec spec = languageRegistry.require(request.getLanguage());

        // 组装 + 校验所有要写进容器的文件。
        // 这一步刻意放在抢并发名额和创建容器【之前】：
        // 一个不合法的请求不该占用配额，更不该有机会碰到 Docker。
        SourceBundle source = SourceBundle.assemble(
                spec.getSourceFileName(),
                request.getCode().getBytes(StandardCharsets.UTF_8),
                request.getFiles(),
                request.getStdin(),
                properties.getMaxFiles(),
                properties.getMaxCodeBytes(),
                properties.getMaxStdinBytes());

        // 源码和 stdin 都是以 base64 形式作为命令行参数传进容器的，
        // 而 Linux 单个参数最长 128 KiB、全部参数合计也有上限。
        // 提前挡住，而不是让内核抛 E2BIG（那个错误信息完全看不懂）。
        for (Map.Entry<String, byte[]> file : source.files().entrySet()) {
            long encoded = SandboxPolicy.base64Length(file.getValue().length);
            if (encoded > SandboxPolicy.MAX_ARGUMENT_BYTES) {
                throw ApiException.badRequest(
                        "文件「" + file.getKey() + "」编码后 " + encoded + " 字节，超过单个参数的传递上限 "
                                + SandboxPolicy.MAX_ARGUMENT_BYTES + " 字节，请拆分或缩短");
            }
        }
        long totalEncoded = source.files().values().stream()
                .mapToLong(f -> SandboxPolicy.base64Length(f.length))
                .sum();
        if (totalEncoded > SandboxPolicy.MAX_TOTAL_ARGUMENT_BYTES) {
            throw ApiException.badRequest(
                    "所有文件编码后合计 " + totalEncoded + " 字节，超过命令行传递总量上限 "
                            + SandboxPolicy.MAX_TOTAL_ARGUMENT_BYTES + " 字节");
        }

        return new PreparedExecution(spec, source);
    }

    /**
     * 同步执行：拿不到名额就立刻拒绝。
     *
     * @throws ApiException 429，当并发已满时
     */
    public ExecutionResponse execute(ExecutionRequest request) {
        PreparedExecution prepared = prepare(request);

        if (!slots.tryAcquire()) {
            throw ApiException.tooManyRequests(
                    "执行器并发已满（上限 " + properties.getMaxConcurrentExecutions()
                            + "），请稍后重试。如果不想被拒绝，可以改用异步接口 POST /api/v1/tasks");
        }

        try {
            return runPrepared(UUID.randomUUID().toString(), prepared);
        } finally {
            slots.release();
        }
    }

    /**
     * 排队执行：最多等 {@code maxWaitMs} 毫秒拿名额，拿不到才失败。
     *
     * <p>供异步路径使用。注意这里用的是 {@code tryAcquire(timeout)} 而不是
     * 无限期的 {@code acquire()}——排队也必须有个上限，
     * 否则一个卡住的任务会把调用方的轮询永远吊着。
     *
     * @throws ApiException 429，当等待超时时
     */
    public ExecutionResponse executeQueued(String jobId, PreparedExecution prepared, long maxWaitMs)
            throws InterruptedException {

        if (!slots.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS)) {
            throw ApiException.tooManyRequests(
                    "排队等待执行名额超时（" + maxWaitMs + "ms）。当前并发上限 "
                            + properties.getMaxConcurrentExecutions());
        }
        try {
            return runPrepared(jobId, prepared);
        } finally {
            slots.release();
        }
    }

    private ExecutionResponse runPrepared(String jobId, PreparedExecution prepared) {
        LanguageSpec spec = prepared.spec();
        try {
            return codeRunner.run(jobId, spec, prepared.source());
        } catch (ApiException e) {
            log.warn("job={} language={} 执行失败：{}", jobId, spec.getId(), e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("job={} language={} 出现未预期错误", jobId, spec.getId(), e);
            throw e;
        }
    }
}
