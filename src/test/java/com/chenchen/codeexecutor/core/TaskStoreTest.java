package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.error.ApiException;
import com.chenchen.codeexecutor.model.ExecutionResponse;
import com.chenchen.codeexecutor.model.ExecutionStatus;
import com.chenchen.codeexecutor.model.PhaseOutcome;
import com.chenchen.codeexecutor.model.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务状态机与容量闸门。
 *
 * <p>这些用例盯的是两件事：
 * <ol>
 *   <li><b>状态迁移不会被"取消"绕过</b>——取消之后不能再被覆盖成完成</li>
 *   <li><b>内存不会无限涨</b>——保留数量有上限、终态结果会过期清理</li>
 * </ol>
 */
class TaskStoreTest {

    @Test
    @DisplayName("新建的任务处于排队中，且没有开始/结束时间")
    void newTaskIsPending() {
        TaskStore.TaskRecord record = store(100, 600, 3600).create("t1", "python");

        assertThat(record.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(record.toView().taskId()).isEqualTo("t1");
        assertThat(record.toView().startedAt()).isNull();
        assertThat(record.toView().finishedAt()).isNull();
        assertThat(record.toView().result()).isNull();
    }

    @Test
    @DisplayName("完整状态迁移：排队 → 执行 → 完成，并记录排队时长")
    void fullTransition() throws InterruptedException {
        TaskStore.TaskRecord record = store(100, 600, 3600).create("t1", "java");

        Thread.sleep(15);   // 让排队时长可观测
        record.markRunning();
        assertThat(record.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(record.toView().waitedMs()).isNotNull().isGreaterThanOrEqualTo(10);

        record.complete(successResponse("t1"));
        assertThat(record.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(record.toView().result()).isNotNull();
        assertThat(record.toView().finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("排队期间可以取消")
    void pendingCanBeCancelled() {
        TaskStore.TaskRecord record = store(100, 600, 3600).create("t1", "python");

        assertThat(record.cancel()).isTrue();
        assertThat(record.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    @DisplayName("已经开始执行的任务不能取消——杀容器是另一套机制，不在这个接口的承诺里")
    void runningCannotBeCancelled() {
        TaskStore.TaskRecord record = store(100, 600, 3600).create("t1", "python");
        record.markRunning();

        assertThat(record.cancel()).isFalse();
        assertThat(record.getStatus()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    @DisplayName("取消之后，后续的 complete 不能把它覆盖回去")
    void cancelledIsTerminal() {
        TaskStore.TaskRecord record = store(100, 600, 3600).create("t1", "python");
        record.cancel();

        record.complete(successResponse("t1"));
        record.fail("不该覆盖");

        assertThat(record.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(record.toView().result()).isNull();
    }

    @Test
    @DisplayName("被取消的任务不会再被 markRunning 拉起来")
    void cancelledTaskIsNotMarkedRunning() {
        TaskStore.TaskRecord record = store(100, 600, 3600).create("t1", "python");
        record.cancel();

        record.markRunning();

        assertThat(record.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    @DisplayName("终态判定：只有完成/失败/取消/过期算终态")
    void terminalStates() {
        assertThat(TaskStatus.PENDING.isTerminal()).isFalse();
        assertThat(TaskStatus.RUNNING.isTerminal()).isFalse();
        assertThat(TaskStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(TaskStatus.FAILED.isTerminal()).isTrue();
        assertThat(TaskStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(TaskStatus.EXPIRED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("保留数量到达上限且无过期任务时，拒绝新任务——这是防内存被吃光的闸门")
    void rejectsWhenFull() {
        TaskStore store = store(3, 600, 3600);
        store.create("t1", "python");
        store.create("t2", "python");
        store.create("t3", "python");

        assertThatThrownBy(() -> store.create("t4", "python"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("任务存储已满");
    }

    @Test
    @DisplayName("到达上限时会先清理过期任务，清理出空间就还能继续接")
    void sweepsBeforeRejecting() {
        // TTL 用负数，cutoff 就落在"未来"，任何已结束的任务都算过期。
        // 用 0 是不行的：finishedAt 和 cutoff 可能落在同一个时钟刻度上，
        // isBefore 会返回 false，测试就变成看运气了。
        TaskStore store = store(2, -1, 3600);
        TaskStore.TaskRecord a = store.create("t1", "python");
        a.complete(successResponse("t1"));
        store.create("t2", "python");

        // 第三个进来时先 sweep：t1 已完成且已过期，会被清掉
        TaskStore.TaskRecord c = store.create("t3", "python");

        assertThat(c.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(store.find("t1")).isEmpty();
    }

    @Test
    @DisplayName("过期清理只动终态任务，排队中和执行中的绝不能清")
    void sweepLeavesActiveTasksAlone() {
        TaskStore store = store(100, -1, 3600);   // 负 TTL = 一切都已过期，见上一个用例的说明
        store.create("pending", "python");
        TaskStore.TaskRecord running = store.create("running", "python");
        running.markRunning();
        TaskStore.TaskRecord done = store.create("done", "python");
        done.complete(successResponse("done"));

        store.sweep();

        assertThat(store.find("pending")).isPresent();
        assertThat(store.find("running")).isPresent();
        assertThat(store.find("done")).isEmpty();
    }

    @Test
    @DisplayName("按状态统计正确，可用于指标")
    void countsByStatus() {
        TaskStore store = store(100, 600, 3600);
        store.create("a", "python");
        TaskStore.TaskRecord b = store.create("b", "python");
        b.markRunning();
        TaskStore.TaskRecord c = store.create("c", "python");
        c.complete(successResponse("c"));

        assertThat(store.countByStatus())
                .containsEntry(TaskStatus.PENDING, 1L)
                .containsEntry(TaskStatus.RUNNING, 1L)
                .containsEntry(TaskStatus.COMPLETED, 1L);
    }

    @Test
    @DisplayName("失败原因会写进 message，和用户代码的结果分开")
    void failureMessageIsSeparateFromCodeResult() {
        TaskStore.TaskRecord record = store(100, 600, 3600).create("t1", "python");

        record.fail("连不上 Docker 守护进程");

        assertThat(record.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(record.toView().message()).contains("连不上 Docker");
        assertThat(record.toView().result()).isNull();
    }

    private static TaskStore store(int maxRetained, long ttlSeconds, long sweepMillis) {
        ExecutorProperties properties = new ExecutorProperties();
        properties.getAsync().setMaxRetainedTasks(maxRetained);
        properties.getAsync().setTaskTtlSeconds(ttlSeconds);
        properties.getAsync().setSweepIntervalMillis(sweepMillis);
        return new TaskStore(properties);
    }

    private static ExecutionResponse successResponse(String jobId) {
        PhaseOutcome run = PhaseOutcome.finished(0, "ok", "", false);
        return ExecutionResponse.of(jobId, "python", ExecutionStatus.SUCCESS, null, run, 12L, "运行成功");
    }
}
