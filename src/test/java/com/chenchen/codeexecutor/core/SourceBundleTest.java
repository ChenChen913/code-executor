package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多文件 + 标准输入的组装与校验。
 *
 * <p>这个类守的是<b>路径穿越</b>：文件名最终会被拼进容器内的路径，
 * 所以它必须在创建容器之前、在一切 Docker 操作之前把非法名字拦下来。
 */
class SourceBundleTest {

    private static final int MAX_FILES = 16;
    private static final long MAX_SOURCE = 65536;
    private static final long MAX_STDIN = 8192;

    @Test
    @DisplayName("只有入口文件时，清单里就一个文件")
    void entryFileOnly() {
        SourceBundle bundle = assemble("Main.java", "class Main {}", null, null);

        assertThat(bundle.files()).containsOnlyKeys("Main.java");
        assertThat(bundle.sourceFileCount()).isEqualTo(1);
        assertThat(bundle.hasStdin()).isFalse();
    }

    @Test
    @DisplayName("入口文件永远排第一，附加文件按提交顺序跟在后面")
    void entryFileAlwaysFirst() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Helper.java", "class Helper {}");
        extra.put("Util.java", "class Util {}");

        SourceBundle bundle = assemble("Main.java", "class Main {}", extra, null);

        assertThat(bundle.files().keySet())
                .containsExactly("Main.java", "Helper.java", "Util.java");
    }

    @Test
    @DisplayName("带了 stdin 时，stdin 文件排在最后")
    void stdinGoesLast() {
        SourceBundle bundle = assemble("main.py", "print(1)", Map.of("h.py", "x=1"), "hello");

        assertThat(bundle.files().keySet()).containsExactly("main.py", "h.py", SourceBundle.STDIN_FILE);
        assertThat(bundle.hasStdin()).isTrue();
        // stdin 不计入"源文件"
        assertThat(bundle.sourceFileCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("空字符串 stdin 视同没有 stdin，不产生空文件")
    void emptyStdinIsIgnored() {
        assertThat(assemble("main.py", "print(1)", null, "").hasStdin()).isFalse();
        assertThat(assemble("main.py", "print(1)", null, null).hasStdin()).isFalse();
    }

    @Test
    @DisplayName("路径穿越的文件名一律拒绝——这是这个类存在的首要理由")
    void pathTraversalIsRejected() {
        for (String bad : new String[]{
                "../../etc/passwd",
                "a/b.py",
                "a\\b.py",
                "..",
                ".hidden",
                "",
                "   ",
                "x".repeat(65)}) {

            assertThatThrownBy(() -> assemble("main.py", "print(1)", Map.of(bad, "x"), null))
                    .as("文件名「%s」应该被拒绝", bad)
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("不合法");
        }
    }

    @Test
    @DisplayName("附加文件不能顶掉入口文件")
    void extraFileCannotShadowEntryFile() {
        assertThatThrownBy(() -> assemble("main.py", "print(1)", Map.of("main.py", "print(2)"), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("入口文件");
    }

    @Test
    @DisplayName("也不能伪造内部的 .stdin 文件——那个名字被 FileNames 拦掉了")
    void cannotForgeInternalStdinFile() {
        assertThatThrownBy(() -> assemble("main.py", "print(1)", Map.of(".stdin", "evil"), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不合法");
    }

    @Test
    @DisplayName("文件数超限会被拒绝")
    void tooManyFilesIsRejected() {
        Map<String, String> extra = new LinkedHashMap<>();
        for (int i = 0; i < MAX_FILES; i++) {
            extra.put("f" + i + ".py", "x=1");
        }

        assertThatThrownBy(() -> assemble("main.py", "print(1)", extra, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("文件数");
    }

    @Test
    @DisplayName("所有源文件合计超限会被拒绝，而不只是入口文件")
    void totalSourceSizeIsChecked() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("a.py", "x".repeat(40000));
        extra.put("b.py", "y".repeat(40000));

        assertThatThrownBy(() -> assemble("main.py", "print(1)", extra, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("合计");
    }

    @Test
    @DisplayName("stdin 超限会被拒绝，且报错里说清是 stdin 而不是代码")
    void oversizedStdinIsRejected() {
        assertThatThrownBy(() -> assemble("main.py", "print(1)", null, "z".repeat((int) MAX_STDIN + 1)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("stdin");
    }

    @Test
    @DisplayName("空代码直接拒绝")
    void emptyCodeIsRejected() {
        assertThatThrownBy(() -> assemble("main.py", "", null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("code 不能为空");
    }

    @Test
    @DisplayName("附加文件内容为 null 会被拒绝，而不是静默变成空文件")
    void nullFileContentIsRejected() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("a.py", null);

        assertThatThrownBy(() -> assemble("main.py", "print(1)", extra, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能为 null");
    }

    @Test
    @DisplayName("中文内容按 UTF-8 计字节，不是按字符数")
    void contentIsMeasuredInUtf8Bytes() {
        SourceBundle bundle = assemble("main.py", "中文", null, null);

        // 两个汉字 = 6 字节
        assertThat(bundle.sourceBytes()).isEqualTo(6);
        assertThat(bundle.files().get("main.py")).isEqualTo("中文".getBytes(StandardCharsets.UTF_8));
    }

    private static SourceBundle assemble(String entryName, String entryCode,
                                         Map<String, String> extra, String stdin) {
        return SourceBundle.assemble(entryName, entryCode.getBytes(StandardCharsets.UTF_8),
                extra, stdin, MAX_FILES, MAX_SOURCE, MAX_STDIN);
    }
}
