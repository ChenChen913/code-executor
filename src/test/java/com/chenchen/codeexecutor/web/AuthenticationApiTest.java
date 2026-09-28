package com.chenchen.codeexecutor.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权的接口级测试。
 *
 * <p>和 {@code ApiKeyAuthenticatorTest} 的分工：那边测纯逻辑，
 * 这边测"过滤器有没有真的接上、状态码和响应体对不对、免鉴权路径是否真的放行"。
 */
@SpringBootTest(properties = {
        "executor.dry-run=true",
        "executor.auth.enabled=true",
        "executor.auth.keys[0].name=test-caller",
        "executor.auth.keys[0].value=test-key-0123456789abcdef"
})
@AutoConfigureMockMvc
class AuthenticationApiTest {

    private static final String KEY = "test-key-0123456789abcdef";
    private static final String BODY = "{\"language\":\"python\",\"code\":\"print(1)\"}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("不带 key 请求执行接口 → 401")
    void missingKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message", containsString("X-API-Key")));
    }

    @Test
    @DisplayName("带错误 key → 401，且响应里不回显这个 key")
    void wrongKeyIsRejectedWithoutEchoingIt() throws Exception {
        String wrongKey = "attacker-supplied-key-000000";

        mockMvc.perform(post("/api/v1/executions")
                        .header("X-API-Key", wrongKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("无效")))
                .andExpect(content().string(not(containsString(wrongKey))));
    }

    @Test
    @DisplayName("带正确 key → 200")
    void correctKeyIsAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/executions")
                        .header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRY_RUN"));
    }

    @Test
    @DisplayName("查询语言列表同样需要鉴权——不能因为它是只读接口就放行")
    void languagesEndpointAlsoRequiresKey() throws Exception {
        mockMvc.perform(get("/api/v1/languages"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/languages").header("X-API-Key", KEY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("健康检查免鉴权——负载均衡器要靠它判断实例存活")
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("鉴权失败发生在进入业务逻辑之前，不会真的去建容器")
    void authFailureHappensBeforeExecution() throws Exception {
        // 这段代码如果被执行了会真的起一个容器；401 说明流程根本没走到那里
        mockMvc.perform(post("/api/v1/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"python\",\"code\":\"print('should not run')\"}"))
                .andExpect(status().isUnauthorized());
    }
}
