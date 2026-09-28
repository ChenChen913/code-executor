package com.chenchen.codeexecutor.language;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanguageRegistryTest {

    @Test
    @DisplayName("能读出配置好的语言，并自动填上 id")
    void loadsLanguagesAndFillsId() {
        LanguageRegistry registry = registry(Map.of("python", pythonSpec()));

        LanguageSpec spec = registry.require("python");
        assertThat(spec.getId()).isEqualTo("python");
        assertThat(spec.getSourceFileName()).isEqualTo("main.py");
        assertThat(spec.hasCompileStep()).isFalse();
    }

    @Test
    @DisplayName("语言 id 大小写不敏感、前后空格无所谓")
    void languageIdIsCaseInsensitive() {
        LanguageRegistry registry = registry(Map.of("python", pythonSpec()));

        assertThat(registry.require("  PyThOn ").getId()).isEqualTo("python");
    }

    @Test
    @DisplayName("不支持的语言抛 400")
    void unknownLanguageIsBadRequest() {
        LanguageRegistry registry = registry(Map.of("python", pythonSpec()));

        assertThatThrownBy(() -> registry.require("brainfuck"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不支持的语言");
    }

    @Test
    @DisplayName("被禁用的语言同样拒绝执行")
    void disabledLanguageIsRejected() {
        LanguageSpec spec = pythonSpec();
        spec.setEnabled(false);
        LanguageRegistry registry = registry(Map.of("python", spec));

        assertThatThrownBy(() -> registry.require("python"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已被禁用");
        assertThat(registry.enabledLanguages()).isEmpty();
    }

    @Test
    @DisplayName("文件名带路径分隔符时启动即失败——挡住路径穿越")
    void rejectsFileNameWithPathSeparator() {
        for (String bad : List.of("../../etc/passwd", "a/b.py", "a\\b.py", ".hidden")) {
            LanguageSpec spec = pythonSpec();
            spec.setSourceFileName(bad);

            assertThatThrownBy(() -> registry(Map.of("python", spec)))
                    .as("文件名 %s 应该被拒绝", bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("纯文件名");
        }
    }

    @Test
    @DisplayName("启用但没有运行命令时启动即失败")
    void rejectsEnabledLanguageWithoutRunCommand() {
        LanguageSpec spec = pythonSpec();
        spec.setRunCommand(List.of());

        assertThatThrownBy(() -> registry(Map.of("python", spec)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run-command");
    }

    @Test
    @DisplayName("内存配额越界时启动即失败")
    void rejectsInsaneMemoryQuota() {
        LanguageSpec spec = pythonSpec();
        spec.setMemoryMb(0);

        assertThatThrownBy(() -> registry(Map.of("python", spec)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("memory-mb");
    }

    @Test
    @DisplayName("一个语言都没配时启动即失败，而不是等到请求进来才发现")
    void emptyConfigurationFailsFast() {
        assertThatThrownBy(() -> registry(new LinkedHashMap<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有配置任何语言");
    }

    private static LanguageRegistry registry(Map<String, LanguageSpec> languages) {
        ExecutorProperties properties = new ExecutorProperties();
        properties.setLanguages(new LinkedHashMap<>(languages));
        return new LanguageRegistry(properties);
    }

    private static LanguageSpec pythonSpec() {
        LanguageSpec spec = new LanguageSpec();
        spec.setDisplayName("Python 3");
        spec.setSourceFileName("main.py");
        spec.setCompileCommand(List.of());
        spec.setRunCommand(List.of("python3", "-I", "main.py"));
        return spec;
    }
}
