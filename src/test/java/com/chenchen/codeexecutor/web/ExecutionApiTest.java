package com.chenchen.codeexecutor.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口测试。
 *
 * <p>用演练模式跑（{@code executor.dry-run=true}），所以不需要 Docker 也能验证
 * 参数校验、错误码、响应结构这些真正容易写错的部分。
 */
@SpringBootTest(properties = "executor.dry-run=true")
@AutoConfigureMockMvc
class ExecutionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("演练模式下走通完整链路，并返回渲染好的 docker 命令")
    void executesInDryRunMode() throws Exception {
        mockMvc.perform(post("/api/v1/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"python\",\"code\":\"print(1+1)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRY_RUN"))
                .andExpect(jsonPath("$.language").value("python"))
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andExpect(jsonPath("$.stdout", containsString("--network none")))
                .andExpect(jsonPath("$.stdout", containsString("--cap-drop ALL")));
    }

    @Test
    @DisplayName("Java 这类需要编译的语言，渲染出的命令里会多一步 javac")
    void javaPlanIncludesCompileStep() throws Exception {
        mockMvc.perform(post("/api/v1/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"java\",\"code\":\"public class Main{}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stdout", containsString("javac")))
                .andExpect(jsonPath("$.stdout", containsString("Main.java")));
    }

    @Test
    @DisplayName("不支持的语言返回 400，而不是 500")
    void unknownLanguageReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"cobol\",\"code\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message", containsString("不支持的语言")));
    }

    @Test
    @DisplayName("缺少 code 字段返回 400")
    void blankCodeReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"python\",\"code\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("请求体不是 JSON 时返回 400 而不是 500")
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("这不是 JSON"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("语言列表能查出来，且不暴露容器内部命令")
    void listsLanguages() throws Exception {
        mockMvc.perform(get("/api/v1/languages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value("java"))
                .andExpect(jsonPath("$[0].needsCompile").value(true))
                .andExpect(jsonPath("$[1].id").value("python"))
                .andExpect(jsonPath("$[1].needsCompile").value(false))
                .andExpect(jsonPath("$[0].compileCommand").doesNotExist());
    }
}
