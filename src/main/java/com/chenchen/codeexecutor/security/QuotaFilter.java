package com.chenchen.codeexecutor.security;

import com.chenchen.codeexecutor.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 按调用方扣配额。跑在鉴权之后（{@link ApiKeyAuthFilter} 已经把调用方名字挂到请求属性上）。
 *
 * <p><b>只对"提交执行"扣配额，查询不扣。</b>这一点是刻意的：
 * 异步任务的调用方会高频轮询 {@code GET /api/v1/tasks/{id}}，
 * 如果查询也扣配额，正常轮询几下就把额度用光了，配额反而变成了坑。
 * 也就是说——<b>配额管的是"让执行器干活"，不是"让执行器说话"</b>。
 *
 * <p>⚠️ <b>这里有一个实测踩出来的坑，值得单独记：</b>
 * 过滤器里抛出的异常，{@code @RestControllerAdvice} <b>是捕获不到的</b>——
 * 那个通知只作用于 Controller 层。最初这里直接 {@code throw ApiException}，
 * 结果配额超限返回的是 <b>500（执行器内部错误）</b>而不是 <b>429</b>。
 * 所以过滤器必须自己把错误响应写出去，响应体格式与全局错误处理保持一致。
 */
@Component
@Order(QuotaFilter.ORDER)
@ConditionalOnProperty(name = "executor.auth.enabled", havingValue = "true", matchIfMissing = true)
public class QuotaFilter extends OncePerRequestFilter {

    /** 必须大于 {@link ApiKeyAuthFilter#ORDER}，否则拿不到调用方身份。 */
    public static final int ORDER = ApiKeyAuthFilter.ORDER + 10;

    private static final Logger log = LoggerFactory.getLogger(QuotaFilter.class);

    private final CallerQuota quota;
    private final ObjectMapper objectMapper;

    public QuotaFilter(CallerQuota quota, ObjectMapper objectMapper) {
        this.quota = quota;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (isExecutionSubmission(request)) {
            Object caller = request.getAttribute(ApiKeyAuthFilter.CALLER_ATTRIBUTE);
            if (caller != null) {
                try {
                    quota.checkAndConsume(caller.toString(), "execute");
                } catch (ApiException e) {
                    // 必须在这里写响应：过滤器抛出的异常到不了 @RestControllerAdvice
                    writeError(response, e);
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, ApiException e) throws IOException {
        response.setStatus(e.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Map.of("status", e.getStatus().value(), "message", e.getMessage()));
    }

    /** 是不是"提交一次执行"。只有这类请求消耗配额。 */
    private static boolean isExecutionSubmission(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri.startsWith("/api/v1/executions") || uri.startsWith("/api/v1/tasks");
    }
}
