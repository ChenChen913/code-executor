package com.chenchen.codeexecutor.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * API Key 鉴权过滤器。
 *
 * <p>在请求进入 Controller 之前拦截。失败返回 {@code 401}，
 * 响应体与项目其它错误保持一致（{@code {"status":..., "message":"..."}}），
 * 这样调用方只需要解析一套错误语义。
 *
 * <p>日志方面有两条纪律：
 * <ul>
 *   <li><b>绝不记录 key 本身</b>，连前缀也不记——即使是校验失败的 key，
 *       它可能只是调用方把真 key 打错了一个字符</li>
 *   <li>校验失败时记录来源 IP，用于排查"谁在猜 key"</li>
 * </ul>
 */
@Component
@org.springframework.core.annotation.Order(ApiKeyAuthFilter.ORDER)
@ConditionalOnProperty(name = "executor.auth.enabled", havingValue = "true", matchIfMissing = true)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** 鉴权必须跑在配额检查之前——配额是按调用方算的，得先知道是谁。 */
    public static final int ORDER = 10;

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** 鉴权通过后，把调用方名称挂到请求属性上，日志和后续审计可以用。 */
    public static final String CALLER_ATTRIBUTE = "code-executor.caller";

    private final ApiKeyAuthenticator authenticator;
    private final AuthProperties properties;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ApiKeyAuthFilter(ApiKeyAuthenticator authenticator,
                            AuthProperties properties,
                            ObjectMapper objectMapper) {
        this.authenticator = authenticator;
        this.properties = properties;
        this.objectMapper = objectMapper;
        log.info("API Key 鉴权已开启：请求头={} 已配置 {} 个调用方 免鉴权路径={}",
                authenticator.getHeaderName(), authenticator.keyCount(), properties.getPublicPaths());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (isPublic(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String headerName = authenticator.getHeaderName();
        String presented = request.getHeader(headerName);

        if (presented == null || presented.isBlank()) {
            reject(response, "缺少 " + headerName + " 请求头");
            return;
        }

        String caller = authenticator.authenticate(presented);
        if (caller == null) {
            log.warn("API Key 校验失败：来源={} 路径={}", clientIp(request), request.getRequestURI());
            reject(response, "API Key 无效");
            return;
        }

        request.setAttribute(CALLER_ATTRIBUTE, caller);
        filterChain.doFilter(request, response);
    }

    private boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String pattern : properties.getPublicPaths()) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Map.of("status", HttpStatus.UNAUTHORIZED.value(), "message", message));
    }

    /**
     * 取客户端 IP。
     *
     * <p>只看 {@code X-Forwarded-For} 的第一段，且仅用于日志。
     * <b>不能</b>用它做任何鉴权或限流决策——这个头是客户端可以随便伪造的，
     * 除非前面有可信的反向代理并且配置了相应的过滤器。
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }
}
