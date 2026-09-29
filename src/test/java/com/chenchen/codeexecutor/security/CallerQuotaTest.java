package com.chenchen.codeexecutor.security;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 按调用方的配额。
 *
 * <p>这些用例盯的是"配额到底拦住了谁"：
 * 超限的调用方要被挡住，没超限的不能被误伤，而且<b>查询操作不该扣配额</b>——
 * 否则异步调用方正常轮询几下就把额度用光了。
 */
class CallerQuotaTest {

    @Test
    @DisplayName("在额度内正常放行")
    void withinLimitPasses() {
        CallerQuota quota = quota(3, 100, null);

        assertThatCode(() -> {
            quota.checkAndConsume("caller", "execute");
            quota.checkAndConsume("caller", "execute");
            quota.checkAndConsume("caller", "execute");
        }).doesNotThrowAnyException();

        assertThat(quota.usedToday("caller")).isEqualTo(3);
    }

    @Test
    @DisplayName("超过每分钟上限就拒绝，且报错里说清是哪道限制")
    void perMinuteLimitIsEnforced() {
        CallerQuota quota = quota(2, 100, null);
        quota.checkAndConsume("caller", "execute");
        quota.checkAndConsume("caller", "execute");

        assertThatThrownBy(() -> quota.checkAndConsume("caller", "execute"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("每分钟最多 2 次");
    }

    @Test
    @DisplayName("超过每日上限也拒绝，提示和每分钟那条区分得开")
    void dailyLimitIsEnforced() {
        CallerQuota quota = quota(100, 2, null);
        quota.checkAndConsume("caller", "execute");
        quota.checkAndConsume("caller", "execute");

        assertThatThrownBy(() -> quota.checkAndConsume("caller", "execute"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("每天最多 2 次");
    }

    @Test
    @DisplayName("配额按调用方隔离：A 用光了不影响 B")
    void quotaIsPerCaller() {
        CallerQuota quota = quota(1, 100, null);
        quota.checkAndConsume("caller-a", "execute");

        assertThatThrownBy(() -> quota.checkAndConsume("caller-a", "execute"))
                .isInstanceOf(ApiException.class);
        assertThatCode(() -> quota.checkAndConsume("caller-b", "execute")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("不同端点各算各的：一个端点用光不影响另一个")
    void quotaIsPerEndpoint() {
        CallerQuota quota = quota(1, 100, null);
        quota.checkAndConsume("caller", "execute");

        assertThatCode(() -> quota.checkAndConsume("caller", "other"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("单个 key 上的配置能覆盖全局默认")
    void perKeyOverrideWins() {
        AuthProperties.ApiKey key = new AuthProperties.ApiKey();
        key.setName("vip");
        key.setValue("x".repeat(20));
        key.setRequestsPerMinute(1);
        key.setDailyQuota(100L);

        CallerQuota quota = quota(100, 100, key);
        quota.checkAndConsume("vip", "execute");

        assertThatThrownBy(() -> quota.checkAndConsume("vip", "execute"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("每分钟最多 1 次");
    }

    @Test
    @DisplayName("-1 表示不限，怎么打都不拦")
    void minusOneMeansUnlimited() {
        AuthProperties.ApiKey key = new AuthProperties.ApiKey();
        key.setName("internal");
        key.setValue("x".repeat(20));
        key.setRequestsPerMinute(-1);
        key.setDailyQuota(-1L);

        CallerQuota quota = quota(1, 1, key);
        assertThatCode(() -> {
            for (int i = 0; i < 50; i++) {
                quota.checkAndConsume("internal", "execute");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("总开关关掉之后完全不管")
    void disabledSkipsEverything() {
        ExecutorProperties properties = new ExecutorProperties();
        properties.getQuota().setEnabled(false);
        properties.getQuota().setDefaultRequestsPerMinute(1);

        CallerQuota quota = new CallerQuota(properties, new AuthProperties());
        assertThatCode(() -> {
            for (int i = 0; i < 20; i++) {
                quota.checkAndConsume("caller", "execute");
            }
        }).doesNotThrowAnyException();
    }

    private static CallerQuota quota(int perMinute, long perDay, AuthProperties.ApiKey key) {
        ExecutorProperties properties = new ExecutorProperties();
        properties.getQuota().setDefaultRequestsPerMinute(perMinute);
        properties.getQuota().setDefaultDailyQuota(perDay);

        AuthProperties auth = new AuthProperties();
        List<AuthProperties.ApiKey> keys = new ArrayList<>();
        if (key != null) {
            keys.add(key);
        }
        auth.setKeys(keys);
        return new CallerQuota(properties, auth);
    }
}
