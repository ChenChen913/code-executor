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

        // 按字节算而不是按字符算：String.length() 数的是 char，
        // 一个汉字是 3 字节，"10 万个字符"的代码可能占 300KB 内存
        int codeBytes = request.getCode().getBytes(StandardCharsets.UTF_8).length;
        if (codeBytes > properties.getMaxCodeBytes()) {
            throw ApiException.badRequest(
                    "代码 " + codeBytes + " 字节，超过上限 " + properties.getMaxCodeBytes() + " 字节");
        }

        // 源码是以 base64 形式作为一个命令行参数传进容器的，
        // 而 Linux 单个参数最长 128 KiB。提前挡住，而不是让内核抛 E2BIG。
        long encodedBytes = SandboxPolicy.base64Length(codeBytes);
        if (encodedBytes > SandboxPolicy.MAX_ARGUMENT_BYTES) {
            throw ApiException.badRequest(
                    "代码编码后 " + encodedBytes + " 字节，超过单个参数的传递上限 "
                            + SandboxPolicy.MAX_ARGUMENT_BYTES + " 字节，请缩短代码");
        }

        if (!slots.tryAcquire()) {
            throw ApiException.tooManyRequests(
                    "执行器并发已满（上限 " + properties.getMaxConcurrentExecutions() + "），请稍后重试");
        }

        String jobId = UUID.randomUUID().toString();
        try {
            return codeRunner.run(jobId, spec, request.getCode());
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
