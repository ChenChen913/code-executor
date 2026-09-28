package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.language.LanguageSpec;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 沙箱加固参数的测试。
 *
 * <p>这些断言看起来像"把代码抄了一遍"，但它们真正的用途是<b>回归保护</b>：
 * 以后有人为了图方便删掉 {@code --network none}，测试会直接红掉。
 * 加固配置最怕的就是被"顺手简化"，必须有东西拦着。
 */
class SandboxPolicyTest {

    private final SandboxPolicy policy = new SandboxPolicy(new ExecutorProperties());

    @Test
    @DisplayName("容器必须断网——否则用户代码可以扫描内网、SSRF、挖矿")
    void networkIsDisabled() {
        assertThat(policy.hostConfig(pythonSpec()).getNetworkMode()).isEqualTo("none");
    }

    @Test
    @DisplayName("根文件系统只读，只有工作目录和 /tmp 是可写的内存盘")
    void rootFileSystemIsReadOnly() {
        HostConfig hostConfig = policy.hostConfig(pythonSpec());

        assertThat(hostConfig.getReadonlyRootfs()).isTrue();
        assertThat(hostConfig.getTmpFs())
                .containsOnlyKeys(SandboxPolicy.WORKSPACE, "/tmp")
                .allSatisfy((path, options) -> assertThat(options).contains("size="));
    }

    @Test
    @DisplayName("丢弃全部 capability 并要求 no-new-privileges")
    void capabilitiesAreDropped() {
        HostConfig hostConfig = policy.hostConfig(pythonSpec());

        assertThat(hostConfig.getCapDrop()).containsExactly(Capability.ALL);
        assertThat(hostConfig.getSecurityOpts()).contains("no-new-privileges");
        assertThat(hostConfig.getPrivileged()).isFalse();
    }

    @Test
    @DisplayName("内存上限生效，且不给 swap（否则超限会变成'慢得要死'而不是被杀）")
    void memoryIsLimitedWithoutSwap() {
        LanguageSpec spec = pythonSpec();
        spec.setMemoryMb(128);
        HostConfig hostConfig = policy.hostConfig(spec);

        assertThat(hostConfig.getMemory()).isEqualTo(128L * 1024 * 1024);
        assertThat(hostConfig.getMemorySwap()).isEqualTo(hostConfig.getMemory());
    }

    @Test
    @DisplayName("CPU 和进程数都受限——挡挖矿和 fork 炸弹")
    void cpuAndPidsAreLimited() {
        LanguageSpec spec = pythonSpec();
        spec.setPidsLimit(32);
        HostConfig hostConfig = policy.hostConfig(spec);

        assertThat(hostConfig.getCpuCount()).isEqualTo(1L);
        assertThat(hostConfig.getPidsLimit()).isEqualTo(32L);
        assertThat(hostConfig.getUlimits()).isNotEmpty();
    }

    @Test
    @DisplayName("不能开 auto-remove：容器退出后还要 inspect 它，判断是不是被 OOM 杀的")
    void autoRemoveMustStayOff() {
        assertThat(policy.hostConfig(pythonSpec()).getAutoRemove()).isFalse();
    }

    @Test
    @DisplayName("保命时长 = 编译超时 + 运行超时 + 余量，保证容器不会永久泄漏")
    void keepAliveIsBounded() {
        LanguageSpec spec = pythonSpec();
        spec.setCompileTimeoutMs(20_000);
        spec.setRunTimeoutMs(5_000);

        assertThat(policy.keepAliveCommand(spec)).isEqualTo("sleep 55");
    }

    @Test
    @DisplayName("标签齐全：回收器靠 managed 标签只认自己人，靠时间标签判断保护期")
    void labelsAreComplete() {
        Map<String, String> labels = policy.labels("job-1");

        assertThat(labels)
                .containsEntry(SandboxPolicy.MANAGED_LABEL, "true")
                .containsEntry(SandboxPolicy.JOB_LABEL, "job-1")
                .containsKey(SandboxPolicy.CREATED_AT_LABEL);
    }

    @Test
    @DisplayName("写文件的命令：脚本是常量，内容只走 base64 参数，文件名只走另一个参数")
    void writeFilesCommandKeepsChannelsSeparate() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("Main.java", "class Main {}".getBytes(StandardCharsets.UTF_8));
        files.put("Helper.java", "class Helper {}".getBytes(StandardCharsets.UTF_8));

        List<String> command = policy.writeFilesCommand(files);

        assertThat(command).hasSize(4 + 2 * 2);
        assertThat(command.subList(0, 4)).containsExactly(
                "sh", "-c",
                "while [ $# -ge 2 ]; do printf %s \"$1\" | base64 -d > \"$2\"; shift 2; done",
                "sh");
        // 脚本本身是常量，绝不能出现任何文件名或内容
        assertThat(command.get(2))
                .doesNotContain("Main.java")
                .doesNotContain("Helper.java")
                .doesNotContain("Y2xhc3M");
        // 每个文件是"base64 内容 + 文件名"一对，顺序与传入一致
        assertThat(command.get(4)).isEqualTo(
                Base64.getEncoder().encodeToString("class Main {}".getBytes(StandardCharsets.UTF_8)));
        assertThat(command.get(5)).isEqualTo("Main.java");
        assertThat(command.get(6)).isEqualTo(
                Base64.getEncoder().encodeToString("class Helper {}".getBytes(StandardCharsets.UTF_8)));
        assertThat(command.get(7)).isEqualTo("Helper.java");
    }

    @Test
    @DisplayName("stdin 重定向：脚本是常量，用户内容只走文件，不进命令行")
    void stdinRedirectKeepsUserDataOutOfTheCommandLine() {
        List<String> command = policy.withStdinRedirect(List.of("python3", "-I", "main.py"));

        assertThat(command).containsExactly(
                "sh", "-c", "exec \"$@\" < /workspace/.stdin", "sh", "python3", "-I", "main.py");
    }

    @Test
    @DisplayName("base64 长度换算正确，用来提前判断会不会撑爆单个参数")
    void base64LengthMatchesEncoder() {
        for (int size : new int[] {0, 1, 2, 3, 100, 65536}) {
            byte[] raw = new byte[size];
            assertThat(SandboxPolicy.base64Length(size))
                    .as("size=%d", size)
                    .isEqualTo(java.util.Base64.getEncoder().encodeToString(raw).length());
        }
    }

    @Test
    @DisplayName("演练模式渲染出的命令里能直接看到关键加固项")
    void describeRunPlanShowsHardening() {
        String plan = policy.describeRunPlan("job-1", pythonSpec(), bundleOf("print(1)"));

        assertThat(plan)
                .contains("--network none")
                .contains("--read-only")
                .contains("--cap-drop ALL")
                .contains("no-new-privileges")
                .contains("--memory 128m")
                .contains("--pids-limit 32")
                .contains("演练模式");
    }

    @Test
    @DisplayName("演练模式的步骤编号连续，不会因为跳过编译而断号")
    void describeRunPlanNumbersStepsContinuously() {
        String plan = policy.describeRunPlan("job-1", pythonSpec(), bundleOf("print(1)"));

        assertThat(plan).contains("# 1)").contains("# 2)").contains("# 3)");
        assertThat(plan).doesNotContain("# 4)");
    }

    @Test
    @DisplayName("演练模式会说明有多少文件、有没有 stdin，但不会把 base64 刷屏")
    void describeRunPlanSummarisesFiles() {
        SourceBundle bundle = SourceBundle.assemble(
                "main.py",
                "print(1)".getBytes(StandardCharsets.UTF_8),
                Map.of("helper.py", "x = 1"),
                "hello",
                16, 65536, 8192);

        String plan = policy.describeRunPlan("job-1", pythonSpec(), bundle);

        assertThat(plan)
                .contains("附加文件")
                .contains("标准输入      : 5 字节")
                .contains("标准输入已重定向")
                .doesNotContain("cHJpbnQoMSk");   // 不出现真实的 base64
    }

    private static SourceBundle bundleOf(String code) {
        return SourceBundle.assemble("main.py", code.getBytes(StandardCharsets.UTF_8),
                null, null, 16, 65536, 8192);
    }

    private static LanguageSpec pythonSpec() {
        LanguageSpec spec = new LanguageSpec();
        spec.setId("python");
        spec.setDisplayName("Python 3");
        spec.setSourceFileName("main.py");
        spec.setCompileCommand(List.of());
        spec.setRunCommand(List.of("python3", "-I", "main.py"));
        spec.setCompileTimeoutMs(10_000);
        spec.setRunTimeoutMs(5_000);
        spec.setMemoryMb(128);
        spec.setPidsLimit(32);
        return spec;
    }
}
