package com.chenchen.codeexecutor.web;

import com.chenchen.codeexecutor.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 把异常统一转成一份 JSON，而不是让调用方去面对 HTML 错误页。
 *
 * <p>返回体里不回显堆栈、不回显容器 id、不回显宿主机路径——
 * 这些东西对调用方没用，对攻击者却很有用。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getStatus().value(), e.getMessage()));
    }

    /** 参数校验失败（language 为空、code 为空……）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("；"));
        return ResponseEntity.badRequest().body(new ApiError(400, message.isBlank() ? "请求参数不合法" : message));
    }

    /** 请求体不是合法 JSON。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ApiError(400, "请求体不是合法的 JSON"));
    }

    /**
     * 兜底。
     *
     * <p>遇到 Spring 自己抛的、本身就带状态码的异常（404、405 等）时按原状态码返回，
     * 不要一律吞成 500——否则调用方会以为是服务端炸了。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatusCode statusCode = errorResponse.getStatusCode();
            return ResponseEntity.status(statusCode)
                    .body(new ApiError(statusCode.value(), "请求无法处理：" + statusCode));
        }
        log.error("未预期错误", e);
        return ResponseEntity.internalServerError()
                .body(new ApiError(500, "执行器内部错误：" + e.getClass().getSimpleName()));
    }

    /** 统一的错误响应体。 */
    public record ApiError(int status, String message) {
    }
}
