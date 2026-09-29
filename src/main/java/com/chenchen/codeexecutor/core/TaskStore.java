package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.error.ApiException;
import com.chenchen.codeexecutor.model.ExecutionResponse;
import com.chenchen.codeexecutor.model.TaskStatus;
import com.chenchen.codeexecutor.model.TaskView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步任务的内存存储。
 *
 * <p>为什么放在内存里而不是数据库：这个服务的任务生命周期很短（秒级到分钟级），
 * 丢了也不可惜——调用方轮询不到就重新提交。引入数据库会把"跑一段代码"
 * 变成"运维一个存储"，性价比极低。
 *
 * <p>代价是<b>重启即丢</b>，而且多实例部署时各存各的（要跨实例轮询就得加粘性路由或共享存储）。
 * 这两点都写在文档的已知不足里。
 *
 * <p>两道容量闸门，防止任务堆积把内存吃光：
 * <ol>
 *   <li>保留的终态任务总数上限 {@code max-retained-tasks}</li>
 *   <li>终态任务保留时长 {@code task-ttl-seconds}，到点由定时任务清掉</li>
 * </ol>
 */
@Component
public class TaskStore {

    private static final Logger log = LoggerFactory.getLogger(TaskStore.class);

    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final ExecutorProperties properties;

    public TaskStore(ExecutorProperties properties) {
        this.properties = properties;
    }

    /** 建一个新任务。存储满了会先尝试清理，还满就拒绝。 */
    public TaskRecord create(String taskId, String language) {
        if (tasks.size() >= properties.getAsync().getMaxRetainedTasks()) {
            sweep();   // 先试着清掉过期的
            if (tasks.size() >= properties.getAsync().getMaxRetainedTasks()) {
                throw ApiException.tooManyRequests(
                        "任务存储已满（上限 " + properties.getAsync().getMaxRetainedTasks()
                                + " 个）。这是一道防止任务堆积吃光内存的闸门，请稍后重试。");
            }
        }
        TaskRecord record = new TaskRecord(taskId, language);
        tasks.put(taskId, record);
        return record;
    }

    public Optional<TaskRecord> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /** 扫描并清理过期的终态任务。 */
    @Scheduled(fixedDelayString = "${executor.async.sweep-interval-millis:30000}")
    public void sweep() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(properties.getAsync().getTaskTtlSeconds()));
        int before = tasks.size();
        tasks.entrySet().removeIf(e -> {
            TaskRecord r = e.getValue();
            return r.status.isTerminal() && r.finishedAt != null && r.finishedAt.isBefore(cutoff);
        });
        int removed = before - tasks.size();
        if (removed > 0) {
            log.debug("清理了 {} 个过期任务，当前保留 {} 个", removed, tasks.size());
        }
    }

    /** 当前保留的任务数，用于测试与指标。 */
    public int size() {
        return tasks.size();
    }

    /** 按状态统计，用于指标。 */
    public Map<TaskStatus, Long> countByStatus() {
        Map<TaskStatus, Long> counts = new java.util.EnumMap<>(TaskStatus.class);
        for (TaskRecord r : tasks.values()) {
            counts.merge(r.status, 1L, Long::sum);
        }
        return counts;
    }

    /** 最老的几个排队任务，调试用。 */
    public List<TaskRecord> oldestPending(int limit) {
        return tasks.values().stream()
                .filter(r -> r.status == TaskStatus.PENDING)
                .sorted(Comparator.comparing(r -> r.submittedAt))
                .limit(limit)
                .toList();
    }

    /**
     * 单个任务的可变记录。
     *
     * <p>字段用 {@code volatile} 是因为它们会被执行线程写、被 HTTP 线程读，
     * 而这个对象本身不整体加锁——状态转换只有几处，用 volatile 比锁更轻。
     */
    public static final class TaskRecord {

        private final String taskId;
        private final String language;
        private final Instant submittedAt = Instant.now();

        private volatile TaskStatus status = TaskStatus.PENDING;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Long waitedMs;
        private volatile ExecutionResponse result;
        private volatile String message = TaskStatus.PENDING.getDescription();

        TaskRecord(String taskId, String language) {
            this.taskId = taskId;
            this.language = language;
        }

        public String getTaskId() {
            return taskId;
        }

        public TaskStatus getStatus() {
            return status;
        }

        synchronized void markRunning() {
            if (status != TaskStatus.PENDING) {
                return;   // 已经被取消了就别再改
            }
            this.startedAt = Instant.now();
            this.waitedMs = Duration.between(submittedAt, startedAt).toMillis();
            this.status = TaskStatus.RUNNING;
            this.message = TaskStatus.RUNNING.getDescription();
        }

        synchronized void complete(ExecutionResponse result) {
            if (status == TaskStatus.CANCELLED) {
                return;
            }
            this.result = result;
            this.status = TaskStatus.COMPLETED;
            this.finishedAt = Instant.now();
            this.message = "执行完毕，代码结果见 result.status=" + result.status();
        }

        synchronized void fail(String reason) {
            if (status == TaskStatus.CANCELLED) {
                return;
            }
            this.status = TaskStatus.FAILED;
            this.finishedAt = Instant.now();
            this.message = reason;
        }

        /** 只有还在排队的任务能取消。已经开始执行的返回 false。 */
        synchronized boolean cancel() {
            if (status != TaskStatus.PENDING) {
                return false;
            }
            this.status = TaskStatus.CANCELLED;
            this.finishedAt = Instant.now();
            this.message = "已在排队阶段取消";
            return true;
        }

        TaskView toView() {
            return new TaskView(taskId, language, status, submittedAt, startedAt,
                    finishedAt, waitedMs, result, message);
        }
    }
}
