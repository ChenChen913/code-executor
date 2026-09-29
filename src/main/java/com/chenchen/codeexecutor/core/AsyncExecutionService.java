package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.error.ApiException;
import com.chenchen.codeexecutor.model.ExecutionRequest;
import com.chenchen.codeexecutor.model.ExecutionResponse;
import com.chenchen.codeexecutor.model.TaskView;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步执行：提交后立刻返回 taskId，调用方轮询取结果。
 *
 * <p><b>什么场景该用它？</b>编译型语言（C++、Rust）或者判题类场景，
 * 一次执行可能要几秒到几十秒。同步接口会让调用方的连接一直挂着，
 * 遇到网关超时还得重试——而重试意味着又跑一遍。
 *
 * <p><b>关于 webhook：刻意没有实现。</b>路线图写的是"轮询<b>或</b> webhook 回调"，
 * 这里只做了轮询。原因是 webhook 会让执行器主动向一个<b>调用方指定的 URL</b> 发请求，
 * 而执行器通常跑在内网、持有 Docker socket——这是一个现成的 SSRF 通道，
 * 攻击者可以用它探测内网、甚至打到云元数据接口。
 * 要做 webhook 就必须配一套回调地址白名单，那属于额外的运维负担，
 * 而轮询已经能满足全部功能需求。
 */
@Service
@ConditionalOnProperty(name = "executor.async.enabled", havingValue = "true", matchIfMissing = true)
public class AsyncExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AsyncExecutionService.class);

    private final ExecutionService executionService;
    private final TaskStore taskStore;
    private final ExecutorProperties properties;
    private final ThreadPoolExecutor workers;
    private final int queueCapacity;

    public AsyncExecutionService(ExecutionService executionService,
                                 TaskStore taskStore,
                                 ExecutorProperties properties) {
        this.executionService = executionService;
        this.taskStore = taskStore;
        this.properties = properties;
        this.queueCapacity = Math.max(1, properties.getAsync().getQueueCapacity());
        int threads = Math.max(1, properties.getAsync().getWorkerThreads());

        this.workers = new ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new NamedThreadFactory(),
                // 队列满了直接拒绝，交给调用方重试——和同步路径的"快速失败"是同一个思路
                new ThreadPoolExecutor.AbortPolicy());

        log.info("异步执行已启用：工作线程={} 队列容量={} 最长排队={}ms 结果保留={}s",
                threads, queueCapacity,
                properties.getAsync().getMaxQueueWaitMs(),
                properties.getAsync().getTaskTtlSeconds());

        if (threads < properties.getMaxConcurrentExecutions()) {
            log.warn("async.worker-threads({}) 小于 max-concurrent-executions({})，"
                            + "并发名额会用不满，建议把工作线程数调到不小于并发上限",
                    threads, properties.getMaxConcurrentExecutions());
        }
    }

    /**
     * 提交任务。
     *
     * <p>校验是<b>同步做完</b>的：语言名拼错、文件名非法、代码超长，
     * 这些都会立刻返回 400，而不是先给一个 taskId、轮询半天才发现失败。
     */
    public TaskView submit(ExecutionRequest request) {
        ExecutionService.PreparedExecution prepared = executionService.prepare(request);

        String taskId = UUID.randomUUID().toString();
        TaskStore.TaskRecord record = taskStore.create(taskId, prepared.spec().getId());

        try {
            workers.execute(() -> runTask(record, prepared));
        } catch (RejectedExecutionException e) {
            record.fail("任务队列已满（上限 " + queueCapacity + " 个），本次未被执行");
            throw ApiException.tooManyRequests(
                    "异步任务队列已满（上限 " + queueCapacity + " 个），请稍后重试");
        }

        log.debug("task={} language={} 已入队", taskId, prepared.spec().getId());
        return record.toView();
    }

    /** 查任务。找不到（含已过期）抛 404。 */
    public TaskView get(String taskId) {
        return require(taskId).toView();
    }

    /**
     * 取消任务。
     *
     * <p><b>只对还在排队的任务有效</b>。已经在执行的会返回 409——
     * 因为"中断一个正在容器里跑的程序"需要另一套机制（杀容器），
     * 而收益很小：它本来就受运行超时约束。
     */
    public TaskView cancel(String taskId) {
        TaskStore.TaskRecord record = require(taskId);
        if (!record.cancel()) {
            throw ApiException.conflict(
                    "任务当前状态是 " + record.getStatus() + "，无法取消。只有排队中（PENDING）的任务可以取消。");
        }
        log.info("task={} 已在排队阶段取消", taskId);
        return record.toView();
    }

    private TaskStore.TaskRecord require(String taskId) {
        return taskStore.find(taskId).orElseThrow(() -> ApiException.notFound(
                "任务 " + taskId + " 不存在，或结果已超过 "
                        + properties.getAsync().getTaskTtlSeconds() + " 秒的保留期被清理"));
    }

    private void runTask(TaskStore.TaskRecord record, ExecutionService.PreparedExecution prepared) {
        record.markRunning();
        // markRunning 对"已取消"的任务不生效，状态还是 CANCELLED —— 那就别跑了
        if (record.getStatus() != com.chenchen.codeexecutor.model.TaskStatus.RUNNING) {
            log.debug("task={} 在排队期间已被取消，跳过执行", record.getTaskId());
            return;
        }

        try {
            ExecutionResponse result = executionService.executeQueued(
                    record.getTaskId(), prepared, properties.getAsync().getMaxQueueWaitMs());
            record.complete(result);
            log.info("task={} language={} status={} duration={}ms",
                    record.getTaskId(), prepared.spec().getId(), result.status(), result.durationMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record.fail("执行线程被中断");
        } catch (ApiException e) {
            // 排队超时、Docker 不可用……都属于"执行器没能把它跑起来"
            record.fail(e.getMessage());
            log.warn("task={} 执行失败：{}", record.getTaskId(), e.getMessage());
        } catch (RuntimeException e) {
            record.fail("执行器内部错误：" + e.getClass().getSimpleName());
            log.error("task={} 出现未预期错误", record.getTaskId(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 带序号的线程名，排查问题时一眼能看出是第几个工作线程。 */
    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "code-exec-async-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
