package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.language.LanguageSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 编译结果缓存。
 *
 * <h2>解决什么</h2>
 * 编译型语言每次执行都要跑一遍编译器。实测 Java 一次执行约 750ms，
 * 其中 {@code javac} 占了 600ms 以上——而大部分时间花在 JVM 启动上，
 * 不是在编译本身。同一段代码重复提交时，这部分完全是白跑的。
 *
 * <h2>怎么做的</h2>
 * 编译成功后，把容器工作目录打成一个 tar 包、base64 编码、搬到宿主机内存里存着；
 * 下次遇到<b>相同源码</b>时，直接把它塞回容器，跳过编译。
 *
 * <h2>为什么这样是安全的（这一点必须论证清楚）</h2>
 * 缓存键是 {@code SHA-256(语言 + 全部源文件 + 编译命令)}。于是：
 * <ul>
 *   <li><b>相同键 = 相同输入</b>。命中缓存拿到的产物，和真跑一遍编译器得到的<b>完全一致</b>，
 *       不存在"缓存里的东西和源码对不上"的可能</li>
 *   <li><b>投毒不可行</b>。想让别人拿到恶意产物，就得构造出与目标源码同哈希的另一段源码——
 *       那是 SHA-256 的原像攻击</li>
 *   <li><b>跨用户复用是安全的</b>。命中缓存的前提是源码逐字节相同，
 *       也就是说对方本来就能猜到你会跑出什么</li>
 * </ul>
 *
 * <p><b>但仍然有一个残余风险</b>：编译产物本身是"用户代码编译出来的东西"，
 * 它会被执行。如果攻击者能改宿主机内存里的这份字节，就能注入。
 * 这要求他已经拿到了执行器进程的内存——那时候游戏已经结束了。记录在案。
 *
 * <h2>刻意不做的事</h2>
 * <ul>
 *   <li><b>不落磁盘</b>。纯内存 LRU，重启即冷。落盘会引入"缓存文件清理"和
 *       "磁盘上的可执行产物"两个新问题，收益不值</li>
 *   <li><b>不缓存大产物</b>。超过 {@code max-artifact-bytes} 的直接放弃——
 *       产物要塞回容器时是走命令行参数的，而单个参数上限是 128 KiB</li>
 *   <li><b>只缓存编译产物，不缓存执行结果</b>。执行是有副作用的
 *       （虽然我们断了网，但时间和资源消耗是真实的），
 *       按源码缓存执行结果等于让"打印当前时间"这类程序返回错误答案</li>
 * </ul>
 */
@Component
public class CompileCache {

    private static final Logger log = LoggerFactory.getLogger(CompileCache.class);

    private final ExecutorProperties properties;
    private final Map<String, byte[]> entries;
    private final Metrics metrics;

    /** 缓存命中/未命中的计数接口，由 ExecutionMetrics 实现。 */
    public interface Metrics {
        void compileCacheHit(String language);

        void compileCacheMiss(String language);
    }

    public CompileCache(ExecutorProperties properties, Metrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
        int max = Math.max(1, properties.getCompileCache().getMaxEntries());
        // accessOrder=true + 重写 removeEldestEntry = 一个现成的 LRU
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > max;
            }
        };
    }

    /**
     * 算缓存键。
     *
     * <p>把语言、全部源文件、编译命令都算进去——少任何一项都可能让
     * "改了语言参数"或"加了编译 flag"之后命中到不该命中的产物。
     * 文件名也参与，因为改文件名可能改变编译结果（比如 Java 的 public 类名）。
     */
    public static String key(LanguageSpec spec, SourceBundle source) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
        update(digest, spec.getId());
        update(digest, spec.getSourceFileName());
        update(digest, String.join("\u0000", spec.getCompileCommand()));
        // SourceBundle 保证顺序稳定（入口文件在最前），所以这里不用排序
        source.files().forEach((name, content) -> {
            update(digest, name);
            digest.update(content);
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 取缓存的编译产物。 */
    public Optional<byte[]> get(String key, String language) {
        if (!properties.getCompileCache().isEnabled()) {
            return Optional.empty();
        }
        byte[] artifact;
        synchronized (entries) {
            artifact = entries.get(key);
        }
        if (artifact == null) {
            metrics.compileCacheMiss(language);
            return Optional.empty();
        }
        metrics.compileCacheHit(language);
        return Optional.of(artifact);
    }

    /**
     * 存一份编译产物。
     *
     * <p>超过大小上限的会<b>被丢掉而不是报错</b>——缓存是优化，不该让
     * "产物太大"变成一次执行失败的理由。
     */
    public void put(String key, byte[] artifact, String language) {
        if (!properties.getCompileCache().isEnabled() || artifact == null || artifact.length == 0) {
            return;
        }
        int maxBytes = properties.getCompileCache().getMaxArtifactBytes();
        if (artifact.length > maxBytes) {
            log.debug("编译产物 {} 字节，超过缓存上限 {} 字节，不缓存（{}）",
                    artifact.length, maxBytes, language);
            return;
        }
        synchronized (entries) {
            entries.put(key, artifact);
        }
    }

    /** 当前缓存了多少份产物。 */
    public int size() {
        return entries.size();
    }

    /** 清空（测试与运维用）。 */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);   // 分隔符，防止 "a"+"bc" 和 "ab"+"c" 撞哈希
    }
}
