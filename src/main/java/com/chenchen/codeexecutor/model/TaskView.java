package com.chenchen.codeexecutor.model;

import java.time.Instant;

/**
 * 异步任务的对外视图。
 *
 * @param taskId       任务 id，用来轮询
 * @param language     语言 id
 * @param status       任务状态（不是代码状态）
 * @param submittedAt  提交时间
 * @param startedAt    开始执行时间；还在排队时为 null
 * @param finishedAt   结束时间；未结束时为 null
 * @param waitedMs     排队等了多久（毫秒）；还在排队时为 null
 * @param result       代码执行结果；未完成时为 null
 * @param message      人类可读的说明。任务失败时这里写的是<b>执行器</b>的失败原因
 */
public record TaskView(
        String taskId,
        String language,
        TaskStatus status,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        Long waitedMs,
        ExecutionResponse result,
        String message) {
}
