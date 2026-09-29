package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.language.LanguageSpec;
import com.github.dockerjava.api.model.HostConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * seccomp 策略的接线。
 *
 * <p>这里只验证"配了会不会真的传下去"。seccomp <b>实际拦住了什么</b>
 * 在 Windows + Docker Desktop 上测不了——守护进程跑在 WSL 虚拟机里，
 * 读不到 Windows 侧的 profile 文件。这一点写在测试报告里，不假装测过。
 */
class SeccompProfileTest {

    @Test
    @DisplayName("不配 seccomp 时，安全选项只有 no-new-privileges")
    void withoutProfileOnlyNoNewPrivileges() {
        HostConfig config = policy(null).hostConfig(spec());

        assertThat(config.getSecurityOpts()).containsExactly("no-new-privileges");
    }

    @Test
    @DisplayName("配了 seccomp 就带上，且不影响 no-new-privileges")
    void withProfileAddsSeccomp() {
        HostConfig config = policy("/etc/code-executor/seccomp-profile.json").hostConfig(spec());

        assertThat(config.getSecurityOpts())
                .containsExactly("no-new-privileges", "seccomp=/etc/code-executor/seccomp-profile.json");
    }

    @Test
    @DisplayName("空白字符串当作没配（避免拼出 seccomp= 这种非法值）")
    void blankProfileIsIgnored() {
        assertThat(policy("   ").hostConfig(spec()).getSecurityOpts())
                .containsExactly("no-new-privileges");
    }

    @Test
    @DisplayName("seccomp 是附加的，不能把其它加固项挤掉")
    void otherHardeningStaysIntact() {
        HostConfig config = policy("/x/seccomp.json").hostConfig(spec());

        assertThat(config.getNetworkMode()).isEqualTo("none");
        assertThat(config.getReadonlyRootfs()).isTrue();
        assertThat(config.getPrivileged()).isFalse();
        assertThat(config.getCapDrop()).isNotNull();
        assertThat(config.getDns()).containsExactly("127.0.0.1");
    }

    private static SandboxPolicy policy(String seccompProfile) {
        SandboxPolicy policy = new SandboxPolicy(new ExecutorProperties());
        ReflectionTestUtils.setField(policy, "seccompProfile", seccompProfile);
        return policy;
    }

    private static LanguageSpec spec() {
        LanguageSpec spec = new LanguageSpec();
        spec.setId("python");
        spec.setSourceFileName("main.py");
        spec.setRunCommand(List.of("python3", "main.py"));
        spec.setMemoryMb(128);
        spec.setPidsLimit(32);
        return spec;
    }
}
