package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.language.LanguageSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编译结果缓存。
 *
 * <p>这个类最该被质疑的地方是<b>安全性</b>：跨用户复用编译产物，会不会让 A 拿到 B 的东西？
 * 所以下面一半的用例都在盯"缓存键到底把什么算进去了"——
 * 漏掉任何一项输入，就可能命中到不该命中的产物。
 */
class CompileCacheTest {

    @Test
    @DisplayName("相同输入算出相同的键（这是缓存能命中的前提）")
    void sameInputSameKey() {
        assertThat(CompileCache.key(javaSpec(), bundle("class Main {}")))
                .isEqualTo(CompileCache.key(javaSpec(), bundle("class Main {}")));
    }

    @Test
    @DisplayName("源码改一个字符，键就变——绝不能命中到旧产物")
    void sourceChangeChangesKey() {
        assertThat(CompileCache.key(javaSpec(), bundle("class Main {}")))
                .isNotEqualTo(CompileCache.key(javaSpec(), bundle("class Main { }")));
    }

    @Test
    @DisplayName("附加文件的内容也参与算键")
    void extraFileContentChangesKey() {
        SourceBundle a = SourceBundle.assemble("Main.java", "class Main {}".getBytes(StandardCharsets.UTF_8),
                Map.of("Helper.java", "class Helper { int x = 1; }"), null, 16, 65536, 8192);
        SourceBundle b = SourceBundle.assemble("Main.java", "class Main {}".getBytes(StandardCharsets.UTF_8),
                Map.of("Helper.java", "class Helper { int x = 2; }"), null, 16, 65536, 8192);

        assertThat(CompileCache.key(javaSpec(), a)).isNotEqualTo(CompileCache.key(javaSpec(), b));
    }

    @Test
    @DisplayName("编译命令参与算键：改了编译 flag 就不能复用旧产物")
    void compileCommandChangesKey() {
        LanguageSpec withFlag = javaSpec();
        withFlag.setCompileCommand(List.of("javac", "-g", "Main.java"));

        assertThat(CompileCache.key(javaSpec(), bundle("class Main {}")))
                .isNotEqualTo(CompileCache.key(withFlag, bundle("class Main {}")));
    }

    @Test
    @DisplayName("语言 id 参与算键")
    void languageChangesKey() {
        LanguageSpec other = javaSpec();
        other.setId("kotlin");

        assertThat(CompileCache.key(javaSpec(), bundle("class Main {}")))
                .isNotEqualTo(CompileCache.key(other, bundle("class Main {}")));
    }

    @Test
    @DisplayName("文件名参与算键：Java 的文件名会影响编译结果")
    void fileNameChangesKey() {
        SourceBundle a = SourceBundle.assemble("Main.java", "class X {}".getBytes(StandardCharsets.UTF_8),
                null, null, 16, 65536, 8192);
        SourceBundle b = SourceBundle.assemble("Other.java", "class X {}".getBytes(StandardCharsets.UTF_8),
                null, null, 16, 65536, 8192);

        assertThat(CompileCache.key(javaSpec(), a)).isNotEqualTo(CompileCache.key(javaSpec(), b));
    }

    @Test
    @DisplayName("键的拼接有分隔符，防止 a+bc 与 ab+c 撞哈希")
    void keyIsNotAmbiguous() {
        SourceBundle a = SourceBundle.assemble("Main.java", "class Main {}".getBytes(StandardCharsets.UTF_8),
                Map.of("A.java", "B"), null, 16, 65536, 8192);
        SourceBundle b = SourceBundle.assemble("Main.java", "class Main {}".getBytes(StandardCharsets.UTF_8),
                Map.of("AB.java", ""), null, 16, 65536, 8192);

        assertThat(CompileCache.key(javaSpec(), a)).isNotEqualTo(CompileCache.key(javaSpec(), b));
    }

    @Test
    @DisplayName("存取一轮能拿到同一份字节")
    void putThenGet() {
        CompileCache cache = cache(8, 1024, true);
        byte[] artifact = "假装这是 class 文件".getBytes(StandardCharsets.UTF_8);

        cache.put("k1", artifact, "java");

        assertThat(cache.get("k1", "java")).contains(artifact);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("没命中返回空，而不是抛异常——缓存是优化，不该让执行失败")
    void missReturnsEmpty() {
        assertThat(cache(8, 1024, true).get("不存在", "java")).isEmpty();
    }

    @Test
    @DisplayName("超过大小上限的产物被丢掉，而且不报错")
    void oversizedArtifactIsDropped() {
        CompileCache cache = cache(8, 10, true);

        cache.put("k1", new byte[100], "java");

        assertThat(cache.get("k1", "java")).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("LRU：超出条数上限时淘汰最久未使用的")
    void evictsLeastRecentlyUsed() {
        CompileCache cache = cache(2, 1024, true);
        cache.put("k1", new byte[]{1}, "java");
        cache.put("k2", new byte[]{2}, "java");

        // 访问 k1，让它变成"最近使用"
        assertThat(cache.get("k1", "java")).isPresent();

        cache.put("k3", new byte[]{3}, "java");

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("k1", "java")).as("刚被访问过，应该还在").isPresent();
        assertThat(cache.get("k2", "java")).as("最久未使用，应该被淘汰").isEmpty();
    }

    @Test
    @DisplayName("总开关关掉之后既不存也不取")
    void disabledCacheDoesNothing() {
        CompileCache cache = cache(8, 1024, false);

        cache.put("k1", new byte[]{1}, "java");

        assertThat(cache.size()).isZero();
        assertThat(cache.get("k1", "java")).isEmpty();
    }

    @Test
    @DisplayName("空产物不缓存（没有任何可复用的东西）")
    void emptyArtifactIsNotCached() {
        CompileCache cache = cache(8, 1024, true);

        cache.put("k1", new byte[0], "java");
        cache.put("k2", null, "java");

        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("命中与未命中会分别计数，指标上能看出缓存有没有在工作")
    void recordsHitAndMissMetrics() {
        RecordingMetrics metrics = new RecordingMetrics();
        CompileCache cache = new CompileCache(properties(8, 1024, true), metrics);

        cache.get("missing", "java");
        cache.put("k1", new byte[]{1}, "java");
        cache.get("k1", "java");

        assertThat(metrics.misses).isEqualTo(1);
        assertThat(metrics.hits).isEqualTo(1);
    }

    private static final class RecordingMetrics implements CompileCache.Metrics {
        private int hits;
        private int misses;

        @Override
        public void compileCacheHit(String language) {
            hits++;
        }

        @Override
        public void compileCacheMiss(String language) {
            misses++;
        }
    }

    private static CompileCache cache(int maxEntries, int maxArtifactBytes, boolean enabled) {
        return new CompileCache(properties(maxEntries, maxArtifactBytes, enabled), new RecordingMetrics());
    }

    private static ExecutorProperties properties(int maxEntries, int maxArtifactBytes, boolean enabled) {
        ExecutorProperties properties = new ExecutorProperties();
        properties.getCompileCache().setEnabled(enabled);
        properties.getCompileCache().setMaxEntries(maxEntries);
        properties.getCompileCache().setMaxArtifactBytes(maxArtifactBytes);
        return properties;
    }

    private static SourceBundle bundle(String code) {
        return SourceBundle.assemble("Main.java", code.getBytes(StandardCharsets.UTF_8),
                null, null, 16, 65536, 8192);
    }

    private static LanguageSpec javaSpec() {
        LanguageSpec spec = new LanguageSpec();
        spec.setId("java");
        spec.setSourceFileName("Main.java");
        spec.setCompileCommand(List.of("javac", "-encoding", "UTF-8", "-d", ".", "Main.java"));
        spec.setRunCommand(List.of("java", "-cp", ".", "Main"));
        return spec;
    }
}
