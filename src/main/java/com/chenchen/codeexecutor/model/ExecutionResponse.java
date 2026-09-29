package com.chenchen.codeexecutor.model;

/**
 * 执行结果，也是对外 API 的响应体。
 *
 * <p>字段是为"用户拿到结果后想干什么"设计的，不是为"数据库里存什么"设计的：
 * <ul>
 *   <li>{@code status} —— 一句话说清这次是成功、编译错、运行错、超时还是 OOM</li>
 *   <li>{@code stdout} / {@code stderr} —— 运行阶段的输出</li>
 *   <li>{@code compileOutput} —— 编译阶段的输出，和运行输出分开，方便定位问题</li>
 *   <li>{@code truncated} —— 输出被截断过，你看到的不是全部</li>
 * </ul>
 */
public record ExecutionResponse(
        String jobId,
        String language,
        ExecutionStatus status,
        boolean success,
        Integer exitCode,
        String stdout,
        String stderr,
        String compileOutput,
        boolean truncated,
        boolean timedOut,
        long durationMs,
        String message,
        /**
         * 本次的编译是否来自缓存（编译产物缓存命中）。
         *
         * <p>刻意暴露出来：调用方看到"编译 0ms 完成"时应该知道是缓存命中，
         * 而不是以为编译器突然变快了。不暴露的话，这个优化就成了一个
         * 说不清来源的行为差异。
         */
        boolean compileCached) {

    /**
     * 由两个阶段的结果拼出最终响应。
     *
     * <p>用工厂方法而不是在调用处 new 一堆参数，是因为这里参数太多，
     * 位置写错编译器不会报错，但会把 stdout 塞进 stderr 里，很难查。
     *
     * @param compile 编译阶段结果，不需要编译的语言为 null
     * @param run     运行阶段结果，编译失败时可能为 null
     */
    public static ExecutionResponse of(String jobId,
                                       String language,
                                       ExecutionStatus status,
                                       PhaseOutcome compile,
                                       PhaseOutcome run,
                                       long durationMs,
                                       String message) {
        return of(jobId, language, status, compile, run, durationMs, message, false);
    }

    /** 同上，额外的 {@code compileCached} 表示编译是否来自缓存。 */
    public static ExecutionResponse of(String jobId,
                                       String language,
                                       ExecutionStatus status,
                                       PhaseOutcome compile,
                                       PhaseOutcome run,
                                       long durationMs,
                                       String message,
                                       boolean compileCached) {
        String stdout = run == null ? "" : nullToEmpty(run.stdout());
        String stderr = run == null ? "" : nullToEmpty(run.stderr());

        // 编译失败时没有运行阶段，退出码取编译阶段的，用户才知道编译是为什么挂的
        Integer exitCode = run != null ? run.exitCode() : (compile != null ? compile.exitCode() : null);

        return new ExecutionResponse(
                jobId,
                language,
                status,
                status.isSuccess(),
                exitCode,
                stdout,
                stderr,
                joinOutput(compile),
                (compile != null && compile.truncated()) || (run != null && run.truncated()),
                (compile != null && compile.timedOut()) || (run != null && run.timedOut()),
                durationMs,
                message,
                compileCached);
    }

    private static String joinOutput(PhaseOutcome phase) {
        if (phase == null) {
            return null;
        }
        String out = nullToEmpty(phase.stdout());
        String err = nullToEmpty(phase.stderr());
        if (err.isBlank()) {
            return out;
        }
        if (out.isBlank()) {
            return err;
        }
        return out.endsWith("\n") ? out + err : out + "\n" + err;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
