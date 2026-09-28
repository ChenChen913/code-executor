package com.chenchen.codeexecutor.language;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 语言注册表：启动时把 languages.yml 读进来并校验，运行期只读。
 *
 * <p>校验放在启动时而不是请求时，是因为配置写错属于"部署事故"：
 * 宁可服务起不来（日志里写得清清楚楚），也不要等用户提交代码了才发现编译命令是空的。
 */
@Component
public class LanguageRegistry {

    private static final Logger log = LoggerFactory.getLogger(LanguageRegistry.class);

    /** 文件名长度上限，纯属防呆。 */
    private static final int MAX_FILE_NAME_LENGTH = 64;

    private final Map<String, LanguageSpec> languages;

    public LanguageRegistry(ExecutorProperties properties) {
        Map<String, LanguageSpec> configured = properties.getLanguages();
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("没有配置任何语言，请检查 languages.yml 里的 executor.languages");
        }
        Map<String, LanguageSpec> validated = new LinkedHashMap<>();
        configured.forEach((id, spec) -> {
            String key = normalize(id);
            validate(key, spec);
            spec.setId(key);
            validated.put(key, spec);
        });
        this.languages = Collections.unmodifiableMap(validated);

        List<String> enabled = new ArrayList<>();
        this.languages.forEach((id, spec) -> {
            if (spec.isEnabled()) {
                enabled.add(id);
            }
        });
        log.info("已加载 {} 种语言（启用 {} 种）：{}", this.languages.size(), enabled.size(), enabled);
    }

    /**
     * 取一门语言的配置，取不到就抛 400。
     *
     * @throws ApiException 语言不存在或已被禁用
     */
    public LanguageSpec require(String language) {
        LanguageSpec spec = language == null ? null : languages.get(normalize(language));
        if (spec == null) {
            throw ApiException.badRequest("不支持的语言：" + language + "，当前支持 " + languages.keySet());
        }
        if (!spec.isEnabled()) {
            throw ApiException.badRequest("语言 " + language + " 已被禁用");
        }
        return spec;
    }

    /** 所有启用的语言（按配置顺序）。 */
    public Collection<Map.Entry<String, LanguageSpec>> enabledLanguages() {
        List<Map.Entry<String, LanguageSpec>> result = new ArrayList<>();
        languages.forEach((id, spec) -> {
            if (spec.isEnabled()) {
                result.add(Map.entry(id, spec));
            }
        });
        return result;
    }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    private static void validate(String id, LanguageSpec spec) {
        String where = "语言[" + id + "]";

        if (spec.getSourceFileName() == null || spec.getSourceFileName().isBlank()) {
            throw new IllegalStateException(where + " 缺少 source-file-name");
        }
        // 关键的安全校验：文件名要拼进容器里的路径，绝不能带目录分隔符或 ..
        String fileName = spec.getSourceFileName();
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")
                || fileName.startsWith(".") || fileName.length() > MAX_FILE_NAME_LENGTH) {
            throw new IllegalStateException(
                    where + " 的 source-file-name 必须是不含路径的纯文件名，当前为：" + fileName);
        }

        if (spec.isEnabled() && (spec.getRunCommand() == null || spec.getRunCommand().isEmpty())) {
            throw new IllegalStateException(where + " 缺少 run-command");
        }
        if (spec.getCompileTimeoutMs() <= 0 || spec.getRunTimeoutMs() <= 0) {
            throw new IllegalStateException(where + " 的超时时间必须大于 0");
        }
        if (spec.getMemoryMb() < 16 || spec.getMemoryMb() > 4096) {
            throw new IllegalStateException(where + " 的 memory-mb 必须在 16~4096 之间");
        }
        if (spec.getPidsLimit() < 8 || spec.getPidsLimit() > 1024) {
            throw new IllegalStateException(where + " 的 pids-limit 必须在 8~1024 之间");
        }
    }
}
