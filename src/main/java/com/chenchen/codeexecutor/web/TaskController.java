package com.chenchen.codeexecutor.web;

import com.chenchen.codeexecutor.core.AsyncExecutionService;
import com.chenchen.codeexecutor.model.ExecutionRequest;
import com.chenchen.codeexecutor.model.TaskView;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步任务接口。
 *
 * <p>和同步接口 {@code POST /api/v1/executions} 是一对：
 * <ul>
 *   <li>同步：一次请求拿到结果，适合快语言（Python / JS）</li>
 *   <li>异步：先拿 taskId 再轮询，适合编译型语言和判题场景</li>
 * </ul>
 *
 * <p>两个资源分开是因为它们的<b>生命周期模型不同</b>：
 * 同步接口的响应是一次性的，而任务有"排队→执行→完成"的状态迁移，
 * 塞进同一个路径下会让响应体变得又大又含糊。
 */
@RestController
@RequestMapping("/api/v1/tasks")
@ConditionalOnProperty(name = "executor.async.enabled", havingValue = "true", matchIfMissing = true)
public class TaskController {

    private final AsyncExecutionService asyncExecutionService;

    public TaskController(AsyncExecutionService asyncExecutionService) {
        this.asyncExecutionService = asyncExecutionService;
    }

    /**
     * 提交任务。请求体和同步接口完全一样。
     *
     * <pre>
     * curl -X POST http://localhost:8080/api/v1/tasks \
     *   -H 'Content-Type: application/json' -H "X-API-Key: $KEY" \
     *   -d '{"language":"java","code":"public class Main{}"}'
     * </pre>
     *
     * <p>返回 {@code 202 Accepted}：请求收下了，但还没跑完。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView submit(@Valid @RequestBody ExecutionRequest request) {
        return asyncExecutionService.submit(request);
    }

    /**
     * 轮询任务。
     *
     * <p>建议的轮询节奏：先 200ms，之后逐步退避到 1~2 秒。
     * 任务结果默认保留 10 分钟，过期后返回 404。
     */
    @GetMapping("/{taskId}")
    public TaskView get(@PathVariable String taskId) {
        return asyncExecutionService.get(taskId);
    }

    /**
     * 取消任务。只对<b>排队中</b>的任务有效；已在执行的返回 409。
     */
    @DeleteMapping("/{taskId}")
    public TaskView cancel(@PathVariable String taskId) {
        return asyncExecutionService.cancel(taskId);
    }
}
