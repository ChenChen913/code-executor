package com.chenchen.codeexecutor.model;

/**
 * 异步任务的生命周期状态。
 *
 * <p><b>注意 {@link #COMPLETED} 的含义</b>：它表示"任务跑完了，有结果了"，
 * <b>不代表用户代码执行成功</b>。用户代码跑得好不好，看
 * {@code TaskView.result.status}（那才是 SUCCESS / COMPILE_ERROR / TIMEOUT…）。
 *
 * <p>这两层状态是刻意分开的：一层描述"任务有没有被处理完"，
 * 另一层描述"代码跑得怎么样"。合并成一个字段的话，
 * 调用方就没法区分"任务还在跑"和"代码超时了"。
 */
public enum TaskStatus {

    /** 已提交，在排队等执行名额。 */
    PENDING("排队中"),

    /** 已拿到名额，正在执行。 */
    RUNNING("执行中"),

    /** 执行完毕，结果见 result 字段。 */
    COMPLETED("已执行完毕，代码结果见 result"),

    /** 执行器自身失败（Docker 不可用等），不是用户代码的问题。 */
    FAILED("执行器失败"),

    /** 排队期间被调用方取消。 */
    CANCELLED("已取消"),

    /** 结果已超过保留时间被清理，或从来没有过这个 id。 */
    EXPIRED("结果已过期");

    private final String description;

    TaskStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /** 是不是终态（不会再变了）。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }
}
