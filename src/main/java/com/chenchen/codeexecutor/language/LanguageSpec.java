package com.chenchen.codeexecutor.language;

import java.util.List;

/**
 * 一门语言的"执行配方"。
 *
 * <p>这是整个项目最核心的抽象。不同语言跑一段代码的差别，说到底只有三件事：
 * <ol>
 *   <li><b>编译命令</b>——C/Java 要编译，Python/JS 不用（那就配成空数组）</li>
 *   <li><b>运行命令</b>——有的语言产出可执行文件直接跑，有的必须靠解释器/虚拟机拉起</li>
 *   <li><b>文件名</b>——Java 的 public 类名必须和文件名一致，所以文件名不能由用户决定</li>
 * </ol>
 *
 * <p>光有这三件事还不够——原文只列了这三个字段，实际实现时还得补上
 * {@code compileTimeoutMs} / {@code runTimeoutMs} / {@code memoryMb} / {@code pidsLimit}：
 * 因为"编译 C++"和"跑一段 Python"该给的资源完全不是一个量级，
 * 把这些也放进配置，加语言时才真的不用改代码。
 *
 * <p>命令一律是 {@code List<String>} 而不是一整条字符串。这不是风格问题：
 * 数组形式下 Docker 会直接 execve，不经过 shell，用户就不可能靠
 * 文件名/参数拼接出 {@code ; rm -rf /} 这类额外命令。见 {@code SandboxFactory}。
 */
public class LanguageSpec {

    /**
     * 语言 id，等于 languages.yml 里的 key。
     *
     * <p>由 {@link LanguageRegistry} 在启动时填进来，配置文件里不用写。
     */
    private String id;

    /** 展示名，例如 "Java 17"。 */
    private String displayName;

    /** 关掉的语言不会出现在 /languages，提交时报 400。 */
    private boolean enabled = true;

    /** 代码在容器里保存成什么文件名，例如 Main.java / main.py。 */
    private String sourceFileName;

    /** 编译命令；空表示不需要编译。 */
    private List<String> compileCommand = List.of();

    /** 运行命令。 */
    private List<String> runCommand = List.of();

    /** 编译阶段超时（毫秒）。 */
    private int compileTimeoutMs = 10_000;

    /** 运行阶段超时（毫秒）。 */
    private int runTimeoutMs = 5_000;

    /** 容器内存上限（MB）。 */
    private int memoryMb = 128;

    /** 容器内最大进程/线程数，防 fork 炸弹。 */
    private long pidsLimit = 64L;

    public boolean hasCompileStep() {
        return compileCommand != null && !compileCommand.isEmpty();
    }

    /** 编译 + 运行两个阶段的超时之和（毫秒）。 */
    public int totalTimeoutMs() {
        return compileTimeoutMs + runTimeoutMs;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public List<String> getCompileCommand() {
        return compileCommand;
    }

    public void setCompileCommand(List<String> compileCommand) {
        this.compileCommand = compileCommand;
    }

    public List<String> getRunCommand() {
        return runCommand;
    }

    public void setRunCommand(List<String> runCommand) {
        this.runCommand = runCommand;
    }

    public int getCompileTimeoutMs() {
        return compileTimeoutMs;
    }

    public void setCompileTimeoutMs(int compileTimeoutMs) {
        this.compileTimeoutMs = compileTimeoutMs;
    }

    public int getRunTimeoutMs() {
        return runTimeoutMs;
    }

    public void setRunTimeoutMs(int runTimeoutMs) {
        this.runTimeoutMs = runTimeoutMs;
    }

    public int getMemoryMb() {
        return memoryMb;
    }

    public void setMemoryMb(int memoryMb) {
        this.memoryMb = memoryMb;
    }

    public long getPidsLimit() {
        return pidsLimit;
    }

    public void setPidsLimit(long pidsLimit) {
        this.pidsLimit = pidsLimit;
    }
}
