package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.language.LanguageRegistry;
import com.chenchen.codeexecutor.language.LanguageSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 容器预热池：<b>预热，但不复用。</b>
 *
 * <h2>为什么不做"复用"</h2>
 *
 * 容器池有两种做法，它们在安全上<b>差别巨大</b>：
 *
 * <table border="1">
 *   <caption>两种池子</caption>
 *   <tr><th></th><th>复用型（常见做法）</th><th>本实现</th></tr>
 *   <tr>
 *     <td>做法</td>
 *     <td>跑完重置容器（清工作目录、杀残留进程）再还回池子</td>
 *     <td>池子里只放<b>从未被使用过</b>的容器；用完照旧销毁，永不归还</td>
 *   </tr>
 *   <tr>
 *     <td>省下的</td>
 *     <td>创建 + 启动 + 销毁</td>
 *     <td>创建 + 启动</td>
 *   </tr>
 *   <tr>
 *     <td>代价</td>
 *     <td><b>容器跨请求存活</b>：前一个用户留下的文件、进程、内存状态都可能影响下一个用户；
 *         一旦重置逻辑有疏漏，就是跨用户的信息泄露</td>
 *     <td>池子里可能堆着用不上的容器（浪费一点内存）</td>
 *   </tr>
 * </table>
 *
 * <p>实测下来，一次执行里<b>创建+启动占了 300~900ms</b>，而销毁只占几十毫秒。
 * 也就是说：复用能省的那点额外收益很小，但它引入的跨请求状态泄漏风险很大。
 * 所以这里选择只预热、不复用——<b>用同样的性能收益，换回完整的"一请求一容器"</b>。
 *
 * <h2>怎么保证池子不变成负担</h2>
 * <ul>
 *   <li>每种语言各自一个池，容量是 {@code warm-per-language}</li>
 *   <li>池里的容器有年龄上限（{@code warm-keep-alive-seconds}），到点主动销毁重建</li>
 *   <li>租用前会 inspect 一次，确认它还活着；死了就丢掉换下一个</li>
 *   <li>回收器会跳过池子里的容器，不会误杀（见 {@link ContainerReaper}）</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "executor.dry-run", havingValue = "false", matchIfMissing = true)
public class ContainerPool {

    private static final Logger log = LoggerFactory.getLogger(ContainerPool.class);

    /** 一个已经创建并启动、但从未被使用过的容器。 */
    public record Warm(String languageId, String containerId, Instant createdAt) {
    }

    private final ContainerFactory factory;
    private final LanguageRegistry languages;
    private final ExecutorProperties properties;

    /** languageId → 先进先出队列。先热身的先用掉，避免有的容器永远躺在池底直到超时。 */
    private final Map<String, Deque<Warm>> pools = new ConcurrentHashMap<>();

    /**
     * 已经租出去、正在执行用户代码的容器。
     *
     * <p>存在的唯一理由是让回收器知道"别碰这些"。
     * 没有它，回收器会把正在执行的容器当孤儿删掉——实测踩过，见 {@link #isInUse}。
     */
    private final java.util.Set<String> busy = ConcurrentHashMap.newKeySet();

    /**
     * 正在补货的语言。
     *
     * <p>用来防"补货踩踏"：高并发下十几个请求同时取走容器，如果每个都独立去创建替代品，
     * 就会瞬间拉起远超需要的容器。同一语言同一时刻只允许一个补货任务在跑。
     */
    private final java.util.Set<String> refilling = ConcurrentHashMap.newKeySet();

    /**
     * 补货用的线程池。
     *
     * <p>补货必须<b>异步</b>：它是为了"下一个请求"准备的，
     * 如果让当前请求等它，那就把省下来的时间又还回去了。
     */
    private final java.util.concurrent.ExecutorService refillExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "code-exec-pool-refill");
                t.setDaemon(true);
                return t;
            });

    public ContainerPool(ContainerFactory factory, LanguageRegistry languages, ExecutorProperties properties,
                         io.micrometer.core.instrument.MeterRegistry registry) {
        this.factory = factory;
        this.languages = languages;
        this.properties = properties;
        // 池子大小做成指标：它能直接回答"预热是不是在正常工作"
        registry.gauge("code_executor.pool.size", this, ContainerPool::size);
        if (properties.getPool().isEnabled()) {
            log.info("容器预热池已启用：每种语言预热 {} 个，容器保活 {}s，补充间隔 {}ms",
                    properties.getPool().getWarmPerLanguage(),
                    properties.getPool().getWarmKeepAliveSeconds(),
                    properties.getPool().getRefillIntervalMillis());
        }
    }

    /**
     * 取一个已经热身好的容器。
     *
     * <p>取不到不是错误——调用方会自己现场创建一个，只是慢一点。
     * 池子是<b>优化</b>，不是<b>依赖</b>：池子坏了服务照样能用。
     */
    public Optional<Warm> acquire(LanguageSpec spec) {
        if (!properties.getPool().isEnabled()) {
            return Optional.empty();
        }
        Deque<Warm> pool = pools.get(spec.getId());
        if (pool == null) {
            return Optional.empty();
        }
        for (Warm candidate = pool.pollFirst(); candidate != null; candidate = pool.pollFirst()) {
            if (factory.isRunning(candidate.containerId())) {
                log.debug("命中预热容器 {}（{}）", candidate.containerId(), spec.getId());
                // 取走一个就立刻异步补一个。
                // 不补的话，下一个请求要等到定时任务那一轮（默认 5 秒）才可能命中——
                // 而请求是连续的，那点补充速度根本追不上，命中率会低得没有意义。
                refillAsync(spec);
                return Optional.of(candidate);
            }
            // 判定为"已经不在运行"——不管是真的死了还是 inspect 偶发失败，
            // 都必须【确保销毁】再丢掉。
            // 早期版本这里只把它从队列里摘掉、不销毁：如果 isRunning 是误判，
            // 那个容器就永远留在 Docker 里，而池子还会再补一个新的 —— 于是缓慢累积。
            log.warn("预热容器 {} 状态异常，销毁后重新预热", candidate.containerId());
            factory.destroy(candidate.containerId());
            refillAsync(spec);
        }
        return Optional.empty();
    }

    /** 异步把某种语言的池子补到目标数量。 */
    private void refillAsync(LanguageSpec spec) {
        fill(spec, true);
    }

    /**
     * 把某种语言的池子补到目标数量。
     *
     * <p><b>必须串行化</b>：定时补充和"取走即补"是两条独立路径，
     * 它们并发跑就会各自看到"池子还是空的"，于是各创建一个——
     * 实测出现过 {@code warm-per-language=1} 却有 2 个 Java 容器的情况。
     * 这种慢慢累积的容器泄漏在生产上极难查，必须在源头堵住。
     *
     * <p>用 {@code refilling} 集合做每语言一把的互斥，而不是全局锁——
     * 不同语言之间没必要互相阻塞。
     *
     * @param async true 表示丢到后台线程池去补（不阻塞当前请求）
     */
    private void fill(LanguageSpec spec, boolean async) {
        if (!properties.getPool().isEnabled()) {
            return;
        }
        if (!refilling.add(spec.getId())) {
            return;   // 已经有补货任务在跑，它会补够的
        }
        Runnable task = () -> {
            try {
                Deque<Warm> pool = pools.computeIfAbsent(spec.getId(), k -> new ArrayDeque<>());
                int target = properties.getPool().getWarmPerLanguage();
                while (pool.size() < target) {
                    pool.addLast(createWarm(spec));
                }
            } catch (RuntimeException e) {
                log.debug("补充 {} 的预热容器失败：{}", spec.getId(), ContainerFactory.rootMessage(e));
            } finally {
                refilling.remove(spec.getId());
            }
        };
        if (async) {
            refillExecutor.execute(task);
        } else {
            task.run();
        }
    }

    /**
     * 把一个<b>没被用过</b>的容器还回池子。
     *
     * <p>只有"取出来了但因为某种原因没用上"才应该还回来。
     * <b>跑过用户代码的容器绝不能还回来</b>——那正是上面说的复用问题。
     */
    public void release(Warm warm) {
        if (!properties.getPool().isEnabled()) {
            factory.destroy(warm.containerId());
            return;
        }
        Deque<Warm> pool = pools.computeIfAbsent(warm.languageId(), k -> new ArrayDeque<>());
        // 必须检查容量：否则"还回来"这个动作本身就能让池子超过目标数量
        if (isExpired(warm) || pool.size() >= properties.getPool().getWarmPerLanguage()) {
            factory.destroy(warm.containerId());
            return;
        }
        pool.addFirst(warm);
    }

    /** 销毁一个池中容器（不计入"已使用"）。 */
    public void discard(Warm warm) {
        factory.destroy(warm.containerId());
    }

    /**
     * 这个容器当前是不是"池子在管的"——包括<b>池中待用</b>和<b>已租出正在用</b>两种。
     *
     * <p>⚠️ <b>这里必须把"已租出"也算进来，否则回收器会杀掉正在执行的请求。</b>
     * 实测踩过：预热容器被租走后就不在池子的队列里了，
     * 于是"名字像预热容器但不在记账里"的对账逻辑把它当成泄漏删掉——
     * 删的正是当前那次执行，表现为"死循环程序跑 2 秒就异常退出"这种极难归因的现象。
     *
     * <p>同理，年龄检查也必须跳过它：预热容器可以合法地存活 300 秒，
     * 远超回收器 120 秒的保护期。
     */
    public boolean isInUse(String containerId) {
        if (containerId == null) {
            return false;
        }
        if (busy.contains(containerId)) {
            return true;
        }
        for (Deque<Warm> pool : pools.values()) {
            for (Warm warm : pool) {
                if (containerId.equals(warm.containerId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 标记为"已租出、正在被使用"。必须在 {@link #acquire} 之后立刻调用。 */
    public void markBusy(String containerId) {
        if (containerId != null) {
            busy.add(containerId);
        }
    }

    /** 解除"正在被使用"标记。容器用完即毁，所以这一步只是让记账干净。 */
    public void unmarkBusy(String containerId) {
        if (containerId != null) {
            busy.remove(containerId);
        }
    }

    /** 当前正在被使用的容器个数，用于指标。 */
    public int busyCount() {
        return busy.size();
    }

    /** 池中容器总数，用于指标。 */
    public int size() {
        return pools.values().stream().mapToInt(Deque::size).sum();
    }

    /** 某种语言池中的数量，用于指标与测试。 */
    public int size(String languageId) {
        Deque<Warm> pool = pools.get(languageId);
        return pool == null ? 0 : pool.size();
    }

    /**
     * 定期补充池子：先清掉过期/死掉的，再补到目标数量。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：补充动作本身要调 Docker，
     * 万一慢了我们不希望任务堆积。
     */
    @Scheduled(fixedDelayString = "${executor.pool.refill-interval-millis:5000}",
            initialDelayString = "${executor.pool.initial-delay-millis:3000}")
    public void refill() {
        if (!properties.getPool().isEnabled()) {
            return;
        }

        for (Map.Entry<String, LanguageSpec> entry : languages.enabledLanguages()) {
            String languageId = entry.getKey();
            LanguageSpec spec = entry.getValue();
            Deque<Warm> pool = pools.computeIfAbsent(languageId, k -> new ArrayDeque<>());

            // 1) 清掉过期和已经死掉的
            pool.removeIf(warm -> {
                if (isExpired(warm) || !factory.isRunning(warm.containerId())) {
                    factory.destroy(warm.containerId());
                    return true;
                }
                return false;
            });

            // 2) 补到目标数量（走同一个互斥，避免和"取走即补"并发超量）
            fill(spec, false);
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        refillExecutor.shutdownNow();
        // 池子里的容器交给回收器处理；这里不主动删，避免关服务时卡住
    }

    /** 启动时预热一轮，让第一批请求就能命中。 */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmUpOnce() {
        log.info("开始预热容器…");
        refill();
        log.info("预热完成，池中现有 {} 个容器", size());
    }

    private Warm createWarm(LanguageSpec spec) {
        String name = "warm-" + spec.getId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String containerId = factory.createAndStart(
                name, spec, properties.getPool().getWarmKeepAliveSeconds());
        // 用 INFO 而不是 DEBUG：这是低频事件（池子健康时根本不发生），
        // 而它一旦变得高频，就说明池子在反复重建 —— 那正是运维需要看到的信号
        log.info("预热容器已就绪：{}（{}），该语言池中现有 {} 个",
                containerId, spec.getId(), size(spec.getId()));
        return new Warm(spec.getId(), containerId, Instant.now());
    }

    private boolean isExpired(Warm warm) {
        return warm.createdAt().isBefore(
                Instant.now().minus(Duration.ofSeconds(properties.getPool().getWarmKeepAliveSeconds())));
    }
}
