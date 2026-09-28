package com.chenchen.codeexecutor.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 执行请求。
 *
 * <p>只有语言和代码两个字段。v1 刻意不支持 stdin、不支持多文件、不支持自定义命令行参数——
 * 每多一个用户可控的输入，就多一条要防守的路径。这些留给后续版本。
 */
public class ExecutionRequest {

    /** 语言 id，对应 languages.yml 里的 key，例如 java / python / javascript。 */
    @NotBlank(message = "language 不能为空")
    private String language;

    /** 用户代码。真正的上限按 UTF-8 字节数在 Service 里再校验一次。 */
    @NotBlank(message = "code 不能为空")
    @Size(max = 200_000, message = "code 过长")
    private String code;

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
}
