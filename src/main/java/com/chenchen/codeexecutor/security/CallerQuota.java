package com.chenchen.codeexecutor.security;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按调用方的配额。
 *
 * <p>为什么需要：全局并发上限只能保证"执行器不被压垮"，
 * 保证不了"某个调用方不把配额吃光"。一个写错循环的调用方可以在几秒内
 * 把全部名额占满，其他调用方全部收到 429——而他们什么都没做错。
 *
 * <p>两道限制，各有各的用途：
 * <table border="1">
 *   <caption>配额的两道限制</caption>
 *   <tr><th></th><th>挡住什么</th><th>窗口</th></tr>
 *   <tr><td>每分钟请求数</td><td>突发流量、写错的循环</td><td>滑动窗口（令牌桶）</td></tr>
 *   <tr><td>每日执行数</td><td>慢速堆积、被刷</td><td>自然日</td></tr>
 * </table>
 *
 * <p>两者都是<b>在提交时计数</b>，所以同步和异步一视同仁——
 * 异步任务提交那一刻就扣配额，而不是等它跑完。
 *
 * <p>状态存在内存里：重启即清零，多实例各算各的。这两点写在文档的已知不足里。
 */
@Component
public class CallerQuota {

    private static final Logger log = LoggerFactory.getLogger(CallerQuota.class);

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final ExecutorProperties properties;
    private final AuthProperties authProperties;

    public CallerQuota(ExecutorProperties properties, AuthProperties authProperties) {
        this.properties = properties;
        this.authProperties = authProperties;
    }

    /**
     * 扣一次配额。超限就抛 429。
     *
     * @param callerName 调用方名字（来自 API Key 配置）
     * @param endpoint   端点名，用于区分"执行"和"查任务"之类的操作
     */
    public void checkAndConsume(String callerName, String endpoint) {
        if (!properties.getQuota().isEnabled()) {
            return;
        }
        AuthProperties.ApiKey key = findKey(callerName);
        int perMinute = resolve(key == null ? null : key.getRequestsPerMinute(),
                properties.getQuota().getDefaultRequestsPerMinute());
        long perDay = resolve(key == null ? null : key.getDailyQuota(),
                properties.getQuota().getDefaultDailyQuota());

        Window window = windows.computeIfAbsent(callerName + "|" + endpoint, k -> new Window());
        window.rollIfNeeded();

        if (perMinute > 0 && !window.minute.tryConsume(perMinute)) {
            log.warn("调用方 {} 超出每分钟配额（{} 次/分钟，端点 {}）", callerName, perMinute, endpoint);
            throw ApiException.tooManyRequests(
                    "调用方 " + callerName + " 超过配额：每分钟最多 " + perMinute + " 次。请稍后重试。");
        }
        if (perDay > 0 && !window.day.tryConsume(perDay)) {
            log.warn("调用方 {} 超出每日配额（{} 次/天，端点 {}）", callerName, perDay, endpoint);
            throw ApiException.tooManyRequests(
                    "调用方 " + callerName + " 超过配额：每天最多 " + perDay + " 次，今日额度已用完。");
        }
    }

    /** 某个调用方当天已用多少，用于运维查询。 */
    public long usedToday(String callerName) {
        return windows.entrySet().stream()
                .filter(e -> e.getKey().startsWith(callerName + "|"))
                .mapToLong(e -> e.getValue().day.usedToday())
                .sum();
    }

    private AuthProperties.ApiKey findKey(String callerName) {
        if (authProperties.getKeys() == null) {
            return null;
        }
        return authProperties.getKeys().stream()
                .filter(k -> callerName.equals(k.getName()))
                .findFirst()
                .orElse(null);
    }

    /** 0 表示"用全局默认"，-1 表示"不限"。 */
    private static int resolve(Integer perKey, int globalDefault) {
        if (perKey == null || perKey == 0) {
            return globalDefault;
        }
        return perKey;
    }

    private static long resolve(Long perKey, long globalDefault) {
        if (perKey == null || perKey == 0L) {
            return globalDefault;
        }
        return perKey;
    }

    private static final class Window {
        private final TokenBucket minute = new TokenBucket();
        private final DailyCounter day = new DailyCounter();

        void rollIfNeeded() {
            minute.rollIfNeeded();
            day.rollIfNeeded();
        }
    }

    /**
     * 简单的滑动窗口计数器。
     *
     * <p>用"固定窗口 + 记录窗口起点"而不是真正的令牌桶：
     * 在这个场景下两者效果一样，而固定窗口的实现能一眼看懂、没有隐藏状态。
     */
    private static final class TokenBucket {
        private Instant windowStart = Instant.now();
        private final AtomicInteger count = new AtomicInteger();

        synchronized void rollIfNeeded() {
            if (Instant.now().isAfter(windowStart.plus(Duration.ofMinutes(1)))) {
                windowStart = Instant.now();
                count.set(0);
            }
        }

        synchronized boolean tryConsume(int limit) {
            rollIfNeeded();
            return count.incrementAndGet() <= limit;
        }
    }

    private static final class DailyCounter {
        private LocalDate day = LocalDate.now();
        private final AtomicInteger count = new AtomicInteger();

        synchronized void rollIfNeeded() {
            LocalDate today = LocalDate.now();
            if (!today.equals(day)) {
                day = today;
                count.set(0);
            }
        }

        synchronized boolean tryConsume(long limit) {
            rollIfNeeded();
            return count.incrementAndGet() <= limit;
        }

        synchronized long usedToday() {
            rollIfNeeded();
            return count.get();
        }
    }
}
