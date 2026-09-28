package com.chenchen.codeexecutor.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 校验 API Key。纯逻辑，不依赖 Servlet，方便单测。
 *
 * <p>刻意没有引入 Spring Security：本项目只需要"比对一个静态密钥"这一件事，
 * 而 Spring Security 的默认配置里绝大部分是要主动关掉的（表单登录、CSRF、
 * 会话管理……）。与其引入一个必须大量裁剪的框架，不如写几十行能逐行解释的代码。
 *
 * <p><b>这个类不负责的事情</b>（见安全设计文档的残余风险）：
 * <ul>
 *   <li>按调用方限流 / 配额 —— 属于 M2</li>
 *   <li>key 的过期与自动轮换 —— 目前靠配置多个 key 手工轮换</li>
 *   <li>传输加密 —— 必须由反向代理终止 TLS，这个服务自己不做 HTTPS</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "executor.auth.enabled", havingValue = "true", matchIfMissing = true)
public class ApiKeyAuthenticator {

    /**
     * key 的最小长度。
     *
     * <p>低于这个长度直接拒绝启动。理由：一个 6 位数字的 key 保护着一个
     * 持有 Docker socket 的服务，比没有鉴权更危险——它给了你"已经安全了"的错觉。
     */
    public static final int MIN_KEY_LENGTH = 16;

    private final String headerName;
    private final List<AuthProperties.ApiKey> keys;

    public ApiKeyAuthenticator(AuthProperties properties) {
        this.headerName = properties.getHeaderName();
        this.keys = validate(properties.getKeys());
    }

    /**
     * 校验一个候选 key。
     *
     * @return 匹配成功时返回调用方名称；失败返回 {@code null}
     */
    public String authenticate(String presented) {
        if (presented == null || presented.isBlank()) {
            return null;
        }
        byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);

        String matched = null;
        // 注意这里没有 break：即使已经匹配上也要把整个列表走完，
        // 否则响应时间会随"匹配的是第几个 key"变化，泄露配置结构。
        for (AuthProperties.ApiKey key : keys) {
            if (MessageDigest.isEqual(candidate, key.getValue().getBytes(StandardCharsets.UTF_8))) {
                matched = key.getName();
            }
        }
        return matched;
    }

    public String getHeaderName() {
        return headerName;
    }

    /** 已配置的调用方数量，用于启动日志。 */
    public int keyCount() {
        return keys.size();
    }

    private static List<AuthProperties.ApiKey> validate(List<AuthProperties.ApiKey> configured) {
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("""
                    已开启 API Key 鉴权（executor.auth.enabled=true），但没有配置任何 key。

                    两种做法：

                      1) 配置一个（推荐）。先生成一个随机 key：
                           openssl rand -base64 32
                         然后通过环境变量传入：
                           EXECUTOR_AUTH_KEYS_0_NAME=my-app
                           EXECUTOR_AUTH_KEYS_0_VALUE=<上面生成的串>

                      2) 仅限本地开发，显式关闭鉴权：
                           --executor.auth.enabled=false

                    之所以选择启动失败而不是自动降级成不鉴权：
                    这个服务持有 Docker socket（等价于宿主机 root），
                    静默地不安全比明显的启动失败危险得多。""");
        }

        List<AuthProperties.ApiKey> result = new ArrayList<>(configured.size());
        for (int i = 0; i < configured.size(); i++) {
            AuthProperties.ApiKey key = configured.get(i);
            String value = key.getValue() == null ? "" : key.getValue().trim();

            if (value.isEmpty()) {
                throw new IllegalStateException("executor.auth.keys[" + i + "] 的 value 为空。"
                        + "如果是从环境变量注入的，检查环境变量是否真的传进来了。");
            }
            if (value.length() < MIN_KEY_LENGTH) {
                throw new IllegalStateException("executor.auth.keys[" + i + "] 的 value 只有 "
                        + value.length() + " 位，至少需要 " + MIN_KEY_LENGTH + " 位。"
                        + "生成方式：openssl rand -base64 32");
            }

            String name = key.getName() == null || key.getName().isBlank()
                    ? "caller-" + (i + 1)
                    : key.getName().trim();
            AuthProperties.ApiKey normalized = new AuthProperties.ApiKey();
            normalized.setName(name);
            normalized.setValue(value);
            result.add(normalized);
        }
        return List.copyOf(result);
    }
}
