package com.chenchen.codeexecutor.model;

/**
 * 一次执行的最终状态。
 *
 * <p>刻意把 {@link #COMPILE_ERROR} 和 {@link #RUNTIME_ERROR} 分开：
 * 用户最关心的第一件事就是"是没编译过，还是跑起来挂了"，
 * 这两种情况该看 stderr 还是看 compileOutput 完全不同。
 *
 * <p>也刻意没有把 {@link #TIMEOUT} 和 {@link #OUT_OF_MEMORY} 合并成"失败"：
 * 这两个是安全兜底触发的，用户需要知道是自己的代码有问题，还是配额太小。
 */
public enum ExecutionStatus {

    SUCCESS("运行成功"),
    COMPILE_ERROR("编译失败"),
    RUNTIME_ERROR("运行出错"),
    TIMEOUT("超时，进程已被强制终止"),
    OUT_OF_MEMORY("内存超限，容器已被强制终止"),
    DRY_RUN("演练模式：只渲染命令，未真正执行"),
    INTERNAL_ERROR("执行器内部错误");

    private final String description;

    ExecutionStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
