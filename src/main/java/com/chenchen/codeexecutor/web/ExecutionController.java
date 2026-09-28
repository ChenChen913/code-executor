package com.chenchen.codeexecutor.web;

import com.chenchen.codeexecutor.core.ExecutionService;
import com.chenchen.codeexecutor.language.LanguageRegistry;
import com.chenchen.codeexecutor.language.LanguageSpec;
import com.chenchen.codeexecutor.model.ExecutionRequest;
import com.chenchen.codeexecutor.model.ExecutionResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 对外接口。
 *
 * <p>关于状态码的一个决定：只要执行本身完成了，就返回 <b>200</b>，
 * 哪怕用户代码编译失败、超时、被 OOM 杀掉——这些都是"正常结果"，写在响应体的
 * {@code status} 字段里。只有"请求没能被执行"才用 4xx/5xx。
 * 这样调用方不用同时解析 HTTP 状态码和响应体两套语义。
 */
@RestController
@RequestMapping("/api/v1")
public class ExecutionController {

    private final ExecutionService executionService;
    private final LanguageRegistry languageRegistry;

    public ExecutionController(ExecutionService executionService, LanguageRegistry languageRegistry) {
        this.executionService = executionService;
        this.languageRegistry = languageRegistry;
    }

    /**
     * 执行一段代码。
     *
     * <pre>
     * curl -X POST http://localhost:8080/api/v1/executions \
     *   -H 'Content-Type: application/json' \
     *   -d '{"language":"python","code":"print(1+1)"}'
     * </pre>
     */
    @PostMapping("/executions")
    public ExecutionResponse execute(@Valid @RequestBody ExecutionRequest request) {
        return executionService.execute(request);
    }

    /** 当前支持哪些语言，以及各自的资源配额。 */
    @GetMapping("/languages")
    public List<LanguageView> languages() {
        List<LanguageView> result = new ArrayList<>();
        for (var entry : languageRegistry.enabledLanguages()) {
            LanguageSpec spec = entry.getValue();
            result.add(new LanguageView(
                    entry.getKey(),
                    spec.getDisplayName(),
                    spec.getSourceFileName(),
                    spec.hasCompileStep(),
                    spec.getCompileTimeoutMs(),
                    spec.getRunTimeoutMs(),
                    spec.getMemoryMb(),
                    spec.getPidsLimit()));
        }
        return result;
    }

    /** 语言的对外视图，刻意不暴露容器内部的命令细节。 */
    public record LanguageView(
            String id,
            String displayName,
            String sourceFileName,
            boolean needsCompile,
            int compileTimeoutMs,
            int runTimeoutMs,
            int memoryMb,
            long pidsLimit) {
    }
}
