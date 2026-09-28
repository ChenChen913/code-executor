package com.chenchen.codeexecutor.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyAuthenticatorTest {

    private static final String GOOD_KEY = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("正确的 key 返回调用方名称")
    void correctKeyReturnsCallerName() {
        ApiKeyAuthenticator authenticator = authenticator(key("my-app", GOOD_KEY));

        assertThat(authenticator.authenticate(GOOD_KEY)).isEqualTo("my-app");
    }

    @Test
    @DisplayName("错误的 key 返回 null")
    void wrongKeyReturnsNull() {
        ApiKeyAuthenticator authenticator = authenticator(key("my-app", GOOD_KEY));

        assertThat(authenticator.authenticate("wrong-key-0123456789abcdef")).isNull();
    }

    @Test
    @DisplayName("空 key / null 都返回 null，而不是抛异常")
    void blankKeyReturnsNull() {
        ApiKeyAuthenticator authenticator = authenticator(key("my-app", GOOD_KEY));

        assertThat(authenticator.authenticate(null)).isNull();
        assertThat(authenticator.authenticate("")).isNull();
        assertThat(authenticator.authenticate("   ")).isNull();
    }

    @Test
    @DisplayName("配置多个 key 时，每一个都能通过——这是 key 轮换的基础")
    void multipleKeysAllWork() {
        ApiKeyAuthenticator authenticator = authenticator(
                key("old-caller", "aaaaaaaaaaaaaaaa1111"),
                key("new-caller", "bbbbbbbbbbbbbbbb2222"));

        assertThat(authenticator.authenticate("aaaaaaaaaaaaaaaa1111")).isEqualTo("old-caller");
        assertThat(authenticator.authenticate("bbbbbbbbbbbbbbbb2222")).isEqualTo("new-caller");
        assertThat(authenticator.keyCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("没配置任何 key 时直接抛异常——宁可起不来，也不要静默地不鉴权")
    void noKeysFailsFast() {
        AuthProperties properties = new AuthProperties();
        properties.setKeys(new ArrayList<>());

        assertThatThrownBy(() -> new ApiKeyAuthenticator(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有配置任何 key")
                .hasMessageContaining("executor.auth.enabled=false");
    }

    @Test
    @DisplayName("key 太短时拒绝启动，并给出生成方式")
    void tooShortKeyFailsFast() {
        assertThatThrownBy(() -> authenticator(key("my-app", "123456")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少需要 16 位")
                .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    @DisplayName("key 为空时拒绝启动，并提示很可能是环境变量没传进来")
    void blankKeyValueFailsFast() {
        assertThatThrownBy(() -> authenticator(key("my-app", "   ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("环境变量");
    }

    @Test
    @DisplayName("没写 name 时自动编号，方便排查是哪一条配置出的问题")
    void unnamedKeyGetsGeneratedName() {
        ApiKeyAuthenticator authenticator = authenticator(key(null, GOOD_KEY));

        assertThat(authenticator.authenticate(GOOD_KEY)).isEqualTo("caller-1");
    }

    @Test
    @DisplayName("key 两端的空白会被去掉（从文件或环境变量读进来时常带换行）")
    void keyIsTrimmed() {
        ApiKeyAuthenticator authenticator = authenticator(key("my-app", "  " + GOOD_KEY + "\n"));

        assertThat(authenticator.authenticate(GOOD_KEY)).isEqualTo("my-app");
    }

    @Test
    @DisplayName("默认请求头是 X-API-Key")
    void defaultHeaderName() {
        assertThat(authenticator(key("my-app", GOOD_KEY)).getHeaderName()).isEqualTo("X-API-Key");
    }

    private static AuthProperties.ApiKey key(String name, String value) {
        AuthProperties.ApiKey key = new AuthProperties.ApiKey();
        key.setName(name);
        key.setValue(value);
        return key;
    }

    private static ApiKeyAuthenticator authenticator(AuthProperties.ApiKey... keys) {
        AuthProperties properties = new AuthProperties();
        properties.setKeys(new ArrayList<>(List.of(keys)));
        return new ApiKeyAuthenticator(properties);
    }
}
