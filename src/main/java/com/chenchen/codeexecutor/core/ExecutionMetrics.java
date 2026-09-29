package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.model.ExecutionStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 执行相关的指标。
 *
 * <p>为什么值得单独做：现在排查线上问题只能翻日志，而日志回答不了
 * "最近 5 分钟成功率是多少""超时主要发生在哪个语言""P99 延迟涨了没有"。
 * 这些问题必须靠指标。
 *
 * <p>指标设计遵循"标签要能回答上面那些问题"的原则：
 * <ul>
 *   <li>{@code code_executor_executions_total} —— 带 {@code language} 和 {@code status} 标签，
 *       所以能按语言、按结局拆分</li>
 *   <li>{@code code_executor_execution_duration} —— 带 {@code language} 标签的计时器，
 *       自带 count/sum/max，接上 Prometheus 就有分位数</li>
 *   <li>{@code code_executor_active_executions} —— 当前正在执行的个数，
 *       用于判断"是不是被占满了"</li>
 * </ul>
 *
 * <p><b>标签基数是刻意控制的</b>：只用了 {@code language}（个位数）和 {@code status}
 * （固定枚举）两个维度。绝不能把 jobId、调用方名字之类的东西做成标签——
 * 那是 Prometheus 内存爆炸的经典原因。调用方维度走配额与审计日志，不走指标。
 */
@Component
public class ExecutionMetrics {

    public static final String EXECUTIONS = "code_executor.executions";
    public static final String DURATION = "code_executor.execution.duration";
    public static final String ACTIVE = "code_executor.active.executions";
    public static final String REJECTED = "code_executor.rejected";

    private final MeterRegistry registry;
    private final AtomicInteger active = new AtomicInteger();

    public ExecutionMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge(ACTIVE, active, AtomicInteger::get);
    }

    /** 一次执行结束（不管成功还是失败）时调用。 */
    public void recordExecution(String language, ExecutionStatus status, long durationMs) {
        registry.counter(EXECUTIONS, "language", language, "status", status.name()).increment();
        Timer.builder(DURATION)
                .tag("language", language)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    /** 请求被拒绝（并发满、配额超限、参数非法……）时调用。 */
    public void recordRejected(String reason) {
        registry.counter(REJECTED, "reason", reason).increment();
    }

    /** 进入执行。 */
    public void executionStarted() {
        active.incrementAndGet();
    }

    /** 离开执行。放在 finally 里，保证配平。 */
    public void executionFinished() {
        active.decrementAndGet();
    }

    public int activeCount() {
        return active.get();
    }
}
