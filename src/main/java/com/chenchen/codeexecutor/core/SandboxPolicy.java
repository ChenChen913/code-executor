package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.language.LanguageSpec;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ulimit;
import com.chenchen.codeexecutor.config.ExecutorProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙箱策略：所有"怎么把容器关起来"的规则都集中在这一个类里。
 *
 * <p>为什么集中在一处？因为加固选项是"一荣俱荣、一损俱损"的：
 * 少写一行 {@code --network none}，用户代码就能连外网；少写一行
 * {@code cap-drop=ALL}，容器里的进程就多了几十项特权。
 * 散落在业务代码里迟早会漏，集中起来才可能一眼看全、也才好写测试。
 *
 * <p>这个类不碰 Docker 客户端，只负责"算出应该下发什么配置"，
 * 所以演练模式（dry-run）和单元测试都能直接用。
 */
@Component
public class SandboxPolicy {

    /** 标记这个容器是执行器创建的，回收器只认这个标签，绝不会误删别人的容器。 */
    public static final String MANAGED_LABEL = "code-executor.managed";

    /** 哪一次请求创建的，排查问题用。 */
    public static final String JOB_LABEL = "code-executor.job-id";

    /** 创建时间（epoch 秒），回收器判断"是否过了保护期"用。 */
    public static final String CREATED_AT_LABEL = "code-executor.created-at";

    /** 容器内的工作目录，同时是代码文件落地的地方。 */
    public static final String WORKSPACE = "/workspace";

    private final ExecutorProperties properties;

    public SandboxPolicy(ExecutorProperties properties) {
        this.properties = properties;
    }

    /**
     * 构造容器的资源与权限限制。
     *
     * <p>下面每一项都不是可选项，逐条对应一类真实攻击：
     * <ul>
     *   <li>{@code networkMode=none} —— 断网。否则用户代码可以扫描内网、SSRF、挖矿</li>
     *   <li>{@code dns=127.0.0.1} —— 不把宿主机的 DNS 地址泄露给用户代码</li>
     *   <li>{@code readonlyRootfs} —— 根文件系统只读，改不了 /etc、装不了后门</li>
     *   <li>{@code tmpFs} —— 唯一可写的地方是内存里的临时盘，容器一停就没了</li>
     *   <li>{@code capDrop=ALL} —— 丢弃全部 Linux capability，进程就是个普通进程</li>
     *   <li>{@code no-new-privileges} —— 就算代码里有 setuid 二进制也提不了权</li>
     *   <li>{@code memory/memorySwap} —— 内存上限，且不给 swap，超了直接杀</li>
     *   <li>{@code cpuCount=1} —— 只给一个核，挖矿也挖不动</li>
     *   <li>{@code pidsLimit} —— 进程数上限，挡住 fork 炸弹</li>
     *   <li>{@code ulimits} —— 文件句柄数、进程数、单文件大小再各加一道保险</li>
     * </ul>
     */
    public HostConfig hostConfig(LanguageSpec spec) {
        long memoryBytes = spec.getMemoryMb() * 1024L * 1024L;

        return HostConfig.newHostConfig()
                .withNetworkMode("none")
                // 断网之后 Docker 依然会把宿主机的 nameserver 写进容器的 /etc/resolv.conf。
                // 两个问题：
                //   1) 那行配置本身就是信息泄露——用户代码 cat /etc/resolv.conf
                //      就能读到宿主机所在的 DNS 地址（往往是个内网 IP）
                //   2) 那个地址在 --network none 下路由不到，域名解析会一直重试到超时
                // 指向回环地址可以解决第 1 点。
                // 第 2 点解决不掉：实测 musl（Alpine）会按自己的重试节奏一直等到运行超时，
                // 换 --dns 也拦不住。好在"解析不出来"这个安全属性本来就是成立的，
                // 只是错误信息会表现为 TIMEOUT 而不是 DNS 错误。
                .withDns("127.0.0.1")
                .withReadonlyRootfs(true)
                // 工作目录要可执行：编译型语言产出的是可执行文件，得能跑起来
                .withTmpFs(Map.of(
                        WORKSPACE, "rw,exec,nosuid,nodev,size=" + properties.getWorkspaceSizeMb() + "m,mode=1777",
                        "/tmp", "rw,noexec,nosuid,nodev,size=" + properties.getTmpSizeMb() + "m,mode=1777"))
                .withPrivileged(false)
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges"))
                .withMemory(memoryBytes)
                // 和 memory 相等 = 禁用 swap，否则"内存超限"会被 swap 悄悄吃掉变成"慢得要死"
                .withMemorySwap(memoryBytes)
                .withCpuCount(1L)
                .withPidsLimit(spec.getPidsLimit())
                .withUlimits(List.of(
                        new Ulimit("nofile", 256L, 256L),
                        new Ulimit("nproc", spec.getPidsLimit(), spec.getPidsLimit()),
                        new Ulimit("fsize", 8L * 1024 * 1024, 8L * 1024 * 1024)))
                // 不能用 --rm：容器退出后我们还要 inspect 它，判断是不是被 OOM 杀的
                .withAutoRemove(false);
    }

    /**
     * 容器的常驻命令：单纯睡一觉，睡够了自己退出。
     *
     * <p>这是"保命"设计。正常流程里执行器会主动删容器，但如果执行器进程被 kill -9、
     * 机器断电、或者清理逻辑抛异常，容器就会一直占着内存。把它写成
     * {@code sleep (编译超时 + 运行超时 + 余量)} 之后，最坏情况也只是多活几十秒。
     */
    public String keepAliveCommand(LanguageSpec spec) {
        int seconds = spec.totalTimeoutMs() / 1000 + properties.getContainerLifetimeSlackSeconds();
        return "sleep " + seconds;
    }

    /** 给容器打的标签，回收器靠它认出"这是自己人"。 */
    public Map<String, String> labels(String jobId) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(MANAGED_LABEL, "true");
        labels.put(JOB_LABEL, jobId);
        labels.put(CREATED_AT_LABEL, String.valueOf(Instant.now().getEpochSecond()));
        return labels;
    }

    /**
     * 单个参数的长度上限（Linux 的 {@code MAX_ARG_STRLEN} 是 128 KiB）。
     * 留一点余量，超过就提前用 400 拒绝，而不是让内核抛 E2BIG。
     */
    public static final int MAX_ARGUMENT_BYTES = 120 * 1024;

    /**
     * 把源码写进容器所用的命令。
     *
     * <p><b>为什么不用 {@code docker cp}？</b>踩过坑，记录下来：
     * {@code docker cp} 是 Docker 守护进程按"rootfs 里的路径"去写的，
     * 它<b>看不到容器内部的 tmpfs 挂载</b>，会被 {@code --read-only} 直接拒绝：
     *
     * <pre>
     * Status 400: {"message":"container rootfs is marked read-only"}
     * </pre>
     *
     * <p><b>为什么不用 exec 的 stdin？</b>也试过。docker-java 的
     * {@code ExecStartCmd.withStdIn(...)} 配合 {@code awaitCompletion} 会一直挂住不返回
     * （stdin 写完之后 {@code cat} 等不到 EOF），每次都要等满超时才失败。
     * 与其和这个 API 较劲，不如换一条确定能走通的路。
     *
     * <p><b>最终方案：base64 走参数，字节不经过 shell 解析。</b>
     * 三个东西各走各的通道：
     * <ul>
     *   <li>脚本 {@code printf %s "$1" | base64 -d > "$2"} 是我们写死的常量，
     *       里面没有任何字符串拼接</li>
     *   <li>源码以 base64 形式作为 {@code $1}。base64 字母表只有
     *       {@code A-Za-z0-9+/=}，<b>不含任何 shell 元字符</b>，即便不加引号也无法逃逸</li>
     *   <li>文件名作为 {@code $2}，在配置加载时就校验过（不含 {@code /} {@code \} {@code ..}）</li>
     * </ul>
     *
     * <p>实测验证过：把 {@code /workspace/a;id} 当文件名传进去，
     * 结果是创建了一个<b>名字叫 {@code a;id} 的文件</b>，而不是执行了 {@code id} 命令。
     *
     * @param fileName       容器内的目标文件名（纯文件名，不含路径）
     * @param base64Content  源码的 base64 编码
     */
    public List<String> sourceWriteCommand(String fileName, String base64Content) {
        return List.of("sh", "-c", "printf %s \"$1\" | base64 -d > \"$2\"", "sh", base64Content, fileName);
    }

    /** base64 编码后的长度（用于提前判断会不会超过参数长度上限）。 */
    public static long base64Length(int rawBytes) {
        return ((rawBytes + 2L) / 3L) * 4L;
    }

    /**
     * 渲染出等价的 docker 命令行，供演练模式和文档使用。
     *
     * <p>对不熟悉 Docker 的人，这一段比任何文字解释都直观：
     * 直接看到"我们到底给容器加了哪些限制"。
     */
    public String describeRunPlan(String jobId, LanguageSpec spec, String code) {
        List<String> lines = new ArrayList<>();
        lines.add("# 演练模式：以下命令没有被真正执行");
        lines.add("# 语言          : " + spec.getDisplayName() + "（" + spec.getId() + "）");
        lines.add("# 代码大小      : " + code.getBytes(StandardCharsets.UTF_8).length + " 字节");
        lines.add("# 容器内文件名  : " + spec.getSourceFileName());
        lines.add("");
        lines.add("docker run -d \\");
        lines.add("  --name code-executor-" + jobId + " \\");
        lines.add("  --user " + properties.getUser() + " \\");
        lines.add("  --workdir " + WORKSPACE + " \\");
        lines.add("  --network none \\");
        lines.add("  --read-only \\");
        lines.add("  --tmpfs " + WORKSPACE + ":rw,exec,nosuid,nodev,size=" + properties.getWorkspaceSizeMb() + "m,mode=1777 \\");
        lines.add("  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=" + properties.getTmpSizeMb() + "m,mode=1777 \\");
        lines.add("  --cap-drop ALL \\");
        lines.add("  --security-opt no-new-privileges \\");
        lines.add("  --memory " + spec.getMemoryMb() + "m --memory-swap " + spec.getMemoryMb() + "m \\");
        lines.add("  --cpus 1 \\");
        lines.add("  --pids-limit " + spec.getPidsLimit() + " \\");
        lines.add("  --ulimit nofile=256:256 --ulimit nproc=" + spec.getPidsLimit() + ":" + spec.getPidsLimit() + " \\");
        lines.add("  --label " + MANAGED_LABEL + "=true --label " + JOB_LABEL + "=" + jobId + " \\");
        lines.add("  " + properties.getImage() + " \\");
        lines.add("  sh -c \"" + keepAliveCommand(spec) + "\"");
        lines.add("");

        // 步骤编号按实际有没有编译阶段动态算，避免出现"1) 然后 3)"这种断号
        List<String> captions = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        captions.add("把源码写进容器（base64 走参数，不落宿主机磁盘，也不需要 docker cp）");
        commands.add("docker exec code-executor-" + jobId + " "
                + String.join(" ", sourceWriteCommand(spec.getSourceFileName(), "<base64>")));
        if (spec.hasCompileStep()) {
            captions.add("编译");
            commands.add("docker exec code-executor-" + jobId + " " + String.join(" ", spec.getCompileCommand()));
        }
        captions.add("运行");
        commands.add("docker exec code-executor-" + jobId + " " + String.join(" ", spec.getRunCommand()));
        captions.add("清理（正常路径走这里；崩了则由回收器兜底）");
        commands.add("docker rm -f code-executor-" + jobId);

        for (int i = 0; i < commands.size(); i++) {
            if (i > 0) {
                lines.add("");
            }
            lines.add("# " + (i + 1) + ") " + captions.get(i));
            lines.add(commands.get(i));
        }
        return String.join("\n", lines);
    }
}
