package com.chenchen.codeexecutor.model;

/**
 * 一个阶段（编译 或 运行）的执行结果。
 *
 * <p>单独抽出来是因为编译和运行要分开看：编译挂了就不该再去运行，
 * 而且两者的输出要分别回传给用户。
 *
 * @param exitCode  进程退出码；拿不到时为 -1
 * @param stdout    标准输出（已按上限截断）
 * @param stderr    标准错误（已按上限截断）
 * @param truncated 输出是否被截断过
 * @param timedOut  是否因为超时被强制终止
 */
public record PhaseOutcome(
        Integer exitCode,
        String stdout,
        String stderr,
        boolean truncated,
        boolean timedOut) {

    /** 成功 = 没超时，且退出码是 0。 */
    public boolean ok() {
        return !timedOut && exitCode != null && exitCode == 0;
    }

    public static PhaseOutcome finished(int exitCode, String stdout, String stderr, boolean truncated) {
        return new PhaseOutcome(exitCode, stdout, stderr, truncated, false);
    }

    public static PhaseOutcome timedOut(String stdout, String stderr, boolean truncated) {
        return new PhaseOutcome(-1, stdout, stderr, truncated, true);
    }
}
