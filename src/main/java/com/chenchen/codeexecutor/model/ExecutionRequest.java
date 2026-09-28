package com.chenchen.codeexecutor.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 执行请求。
 *
 * <p>字段刻意保持精简。每多一个用户可控的输入，就多一条要防守的路径——
 * 所以这里没有"自定义编译参数""自定义环境变量"这类看起来很方便、实际上会把沙箱捅穿的东西。
 *
 * <p>文件名的合法性、总大小、文件个数都在 {@code SourceBundle} 里统一校验，
 * 而且是在<b>创建容器之前</b>。
 */
public class ExecutionRequest {

    /** 语言 id，对应 languages.yml 里的 key，例如 java / python / javascript。 */
    @NotBlank(message = "language 不能为空")
    private String language;

    /**
     * 入口文件的内容。写到容器里的文件名由语言配置决定
     * （例如 Java 的 {@code Main.java}），不是用户说了算。
     */
    @NotBlank(message = "code 不能为空")
    @Size(max = 200_000, message = "code 过长")
    private String code;

    /**
     * 附加文件：容器内文件名 → 内容。
     *
     * <p>用于多文件场景（Java 多类、C 多文件）。文件名必须是纯文件名，
     * 不能含路径成分，也不能和入口文件重名。
     */
    private Map<String, String> files;

    /** 标准输入。非空时会被写进容器并由运行命令重定向读取。 */
    private String stdin;

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Map<String, String> getFiles() {
        return files;
    }

    public void setFiles(Map<String, String> files) {
        this.files = files;
    }

    public String getStdin() {
        return stdin;
    }

    public void setStdin(String stdin) {
        this.stdin = stdin;
    }
}
