package com.chenchen.codeexecutor.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * API Key 鉴权配置。
 *
 * <p><b>默认开启。</b>这是刻意的：执行器持有 Docker socket（等价于宿主机 root），
 * 一个没有鉴权的执行器暴露在内网里，风险远大于"配置麻烦一点"。
 *
 * <p>没配 key 时应用会<b>启动失败</b>，而不是降级成不鉴权——
 * 静默的不安全比明显的启动失败危险得多。
 */
@ConfigurationProperties(prefix = "executor.auth")
public class AuthProperties {

    private boolean enabled = true;

    /** 从哪个请求头取 key。 */
    private String headerName = "X-API-Key";

    /**
     * 不需要鉴权的路径（Ant 风格）。
     *
     * <p>默认只放行健康检查：负载均衡器和容器编排要靠它判断实例是否存活，
     * 而它只返回 UP/DOWN，不泄露任何业务信息。
     */
    private List<String> publicPaths = new ArrayList<>(List.of("/actuator/health"));

    /**
     * 允许的 key 列表。
     *
     * <p>设计成列表而不是单个字符串，是为了让 key 轮换可行：
     * 先加新 key、部署、把调用方切过去，再删掉旧 key——全程不用停机。
     */
    private List<ApiKey> keys = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    public List<ApiKey> getKeys() {
        return keys;
    }

    public void setKeys(List<ApiKey> keys) {
        this.keys = keys;
    }

    /** 一个调用方的凭据。 */
    public static class ApiKey {

        /** 调用方标识。只用于日志与审计，不参与鉴权。 */
        private String name;

        /** 密钥本身。 */
        private String value;

        /** 该调用方每分钟最多提交多少次执行。0 = 用全局默认，-1 = 不限。 */
        private Integer requestsPerMinute = 0;

        /** 该调用方每天最多提交多少次执行。0 = 用全局默认，-1 = 不限。 */
        private Long dailyQuota = 0L;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        /** 该调用方每分钟最多提交多少次执行。0 = 用全局默认，-1 = 不限。 */
        public Integer getRequestsPerMinute() {
            return requestsPerMinute;
        }

        public void setRequestsPerMinute(Integer requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }

        /** 该调用方每天最多提交多少次执行。0 = 用全局默认，-1 = 不限。 */
        public Long getDailyQuota() {
            return dailyQuota;
        }

        public void setDailyQuota(Long dailyQuota) {
            this.dailyQuota = dailyQuota;
        }
    }
}
