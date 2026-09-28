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

/**
 * 执行入口：做校验、限流，然后把活交给 {@link CodeRunner}。
 *
 * <p>并发控制用的是"拿不到就拒绝"（信号量 + 立即返回 429），而不是排队。
 * 理由：排队会让用户看到的延迟变得不可预测——同一段代码有时 1 秒有时 30 秒，
 * 而这种接口通常是被别的服务调用的，调用方更需要"快速失败"而不是"慢慢等"。
 * 真正需要排队时应该由调用方用消息队列自己做，那样重试和优先级都更好控制。
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

    public ExecutionResponse execute(ExecutionRequest request) {
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

        if (!slots.tryAcquire()) {
            throw ApiException.tooManyRequests(
                    "执行器并发已满（上限 " + properties.getMaxConcurrentExecutions() + "），请稍后重试");
        }

        String jobId = UUID.randomUUID().toString();
        try {
            return codeRunner.run(jobId, spec, source);
        } catch (ApiException e) {
            log.warn("job={} language={} 执行失败：{}", jobId, spec.getId(), e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("job={} language={} 出现未预期错误", jobId, spec.getId(), e);
            throw e;
        } finally {
            slots.release();
        }
    }
}
