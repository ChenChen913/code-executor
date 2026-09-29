package com.chenchen.codeexecutor.error;

import org.springframework.http.HttpStatus;

/**
 * 带 HTTP 状态码的业务异常。
 *
 * <p>只用一个异常类型，是因为这些错误的处理方式完全一样（映射成状态码 + message 返回），
 * 没必要为每种情况写一个类。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** 400：请求本身有问题（语言不支持、代码太长……）。 */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    /** 500：执行器自己出了问题（比如往容器里写源码失败）。 */
    public static ApiException internal(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    /** 404：资源不存在（比如任务 id 查不到，或结果已过期被清理）。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    /** 409：资源当前状态不允许这个操作（比如取消一个已经在跑的任务）。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /** 429：执行器忙不过来。 */
    public static ApiException tooManyRequests(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    /** 503：Docker 不可用（没启动、镜像没构建……）。 */
    public static ApiException sandboxUnavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    /** 503：Docker 不可用。 */
    public static ApiException sandboxUnavailable(String message, Throwable cause) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }

    public HttpStatus getStatus() {
        return status;
    }
}
