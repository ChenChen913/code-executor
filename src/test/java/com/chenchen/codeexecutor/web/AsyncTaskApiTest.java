package com.chenchen.codeexecutor.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异步任务接口的接口级测试。
 *
 * <p>用演练模式跑，所以任务会很快完成——这正好能把"提交 → 轮询 → 拿到结果"
 * 这条主链路一次性测完，而不需要真的起容器。
 */
@SpringBootTest(properties = {
        "executor.dry-run=true",
        "executor.auth.keys[0].name=test-caller",
        "executor.auth.keys[0].value=" + AsyncTaskApiTest.TEST_KEY
})
@AutoConfigureMockMvc
class AsyncTaskApiTest {

    static final String TEST_KEY = "test-key-0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class DefaultAuthHeader {
        @Bean
        MockMvcBuilderCustomizer defaultApiKeyHeader() {
            return builder -> builder.defaultRequest(get("/").header("X-API-Key", TEST_KEY));
        }
    }

    @Test
    @DisplayName("提交任务返回 202 和 taskId，而不是结果")
    void submitReturns202WithTaskId() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"python\",\"code\":\"print(1)\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isNotEmpty())
                .andExpect(jsonPath("$.language").value("python"));
    }

    @Test
    @DisplayName("轮询能拿到最终结果，且任务状态与代码状态是两层")
    void pollingEventuallyReturnsResult() throws Exception {
        String body = mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"python\",\"code\":\"print(1)\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String taskId = com.jayway.jsonpath.JsonPath.read(body, "$.taskId");

        // 演练模式执行很快，但仍然是异步的，轮询几次
        String last = null;
        for (int i = 0; i < 50; i++) {
            last = mockMvc.perform(get("/api/v1/tasks/{id}", taskId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            if (last.contains("\"COMPLETED\"")) {
                break;
            }
            Thread.sleep(100);
        }

        org.assertj.core.api.Assertions.assertThat(last)
                .as("任务应最终完成")
                .contains("\"status\":\"COMPLETED\"")
                // 任务状态是 COMPLETED，代码结果是 DRY_RUN —— 两层状态各说各的
                .contains("DRY_RUN");

        mockMvc.perform(get("/api/v1/tasks/{id}", taskId))
                .andExpect(jsonPath("$.result.status").value("DRY_RUN"))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty())
                .andExpect(jsonPath("$.waitedMs").isNotEmpty());
    }

    @Test
    @DisplayName("校验是同步做的：语言不存在时立刻 400，而不是先给个 taskId 再失败")
    void validationHappensSynchronously() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"cobol\",\"code\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不支持的语言")));
    }

    @Test
    @DisplayName("文件名非法也在入队前就被拒")
    void illegalFileNameRejectedBeforeQueueing() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"python\",\"code\":\"print(1)\","
                                + "\"files\":{\"../evil.py\":\"x\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不合法")));
    }

    @Test
    @DisplayName("查询不存在的任务返回 404，且提示会说清可能是过期了")
    void unknownTaskReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("不存在")));
    }

    @Test
    @DisplayName("取消已经完成的任务返回 409，而不是假装成功")
    void cancellingFinishedTaskReturns409() throws Exception {
        String body = mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"python\",\"code\":\"print(1)\"}"))
                .andReturn().getResponse().getContentAsString();
        String taskId = com.jayway.jsonpath.JsonPath.read(body, "$.taskId");

        for (int i = 0; i < 50; i++) {
            String view = mockMvc.perform(get("/api/v1/tasks/{id}", taskId))
                    .andReturn().getResponse().getContentAsString();
            if (view.contains("\"COMPLETED\"")) {
                break;
            }
            Thread.sleep(100);
        }

        mockMvc.perform(delete("/api/v1/tasks/{id}", taskId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("无法取消")));
    }

    @Test
    @DisplayName("任务接口同样需要鉴权，且响应里不会回显错误 key")
    void taskEndpointsRequireAuth() throws Exception {
        String wrongKey = "attacker-key-000000000000";

        String response = mockMvc.perform(post("/api/v1/tasks")
                        .header("X-API-Key", wrongKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"python\",\"code\":\"print(1)\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response).doesNotContain(wrongKey);
        org.assertj.core.api.Assertions.assertThat(response).doesNotContain("taskId");
    }

    @Test
    @DisplayName("异步接口不会因为同步接口的存在而泄露内部结构")
    void taskViewDoesNotLeakInternals() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.message", not(containsString("java."))));
    }
}
