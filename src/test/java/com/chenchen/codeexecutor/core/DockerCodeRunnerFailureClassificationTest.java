package com.chenchen.codeexecutor.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.NoSuchFileException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异常归类的测试。
 *
 * <p>这几条断言看着琐碎，但它们守住的是一个<b>真实踩过的坑</b>：
 * Docker 没启动时，执行器必须返回 503（沙箱不可用），而不是 500（执行器内部错误）。
 * 后者会把调用方引向"我代码写错了"的方向，排查半天才发现是环境问题。
 */
class DockerCodeRunnerFailureClassificationTest {

    @Test
    @DisplayName("裸 RuntimeException 包着 NoSuchFileException（Windows 上 Docker 没启动）→ 判定为连接失败")
    void bareRuntimeExceptionWrappingNoSuchFileIsConnectionFailure() {
        Throwable real = new RuntimeException(
                new NoSuchFileException("\\\\.\\pipe\\dockerDesktopLinuxEngine"));

        assertThat(DockerCodeRunner.hasIoCause(real)).isTrue();
    }

    @Test
    @DisplayName("ConnectException / SocketException 这类也判定为连接失败")
    void connectExceptionIsConnectionFailure() {
        assertThat(DockerCodeRunner.hasIoCause(new RuntimeException(new ConnectException("Connection refused"))))
                .isTrue();
        assertThat(DockerCodeRunner.hasIoCause(new IOException("boom"))).isTrue();
    }

    @Test
    @DisplayName("多层包装也能挖到底")
    void deeplyWrappedIoIsDetected() {
        Throwable nested = new RuntimeException("outer",
                new IllegalStateException("middle",
                        new java.io.UncheckedIOException(new IOException("root"))));

        assertThat(DockerCodeRunner.hasIoCause(nested)).isTrue();
    }

    @Test
    @DisplayName("代码 bug 类异常不会被误判成环境问题——它必须是 500，不是 503")
    void plainBugIsNotConnectionFailure() {
        assertThat(DockerCodeRunner.hasIoCause(new NullPointerException("dockerCmdExecFactory was not specified")))
                .isFalse();
        assertThat(DockerCodeRunner.hasIoCause(new IllegalArgumentException("bad language"))).isFalse();
        assertThat(DockerCodeRunner.hasIoCause(new RuntimeException())).isFalse();
    }

    @Test
    @DisplayName("因果链成环也不会死循环")
    void cyclicCauseChainTerminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);   // 人为造环

        assertThat(DockerCodeRunner.hasIoCause(b)).isFalse();
    }

    @Test
    @DisplayName("rootMessage 取到最底层的信息，而不是无意义的外层包装")
    void rootMessageDigsToTheBottom() {
        Throwable wrapped = new RuntimeException(
                new NoSuchFileException("\\\\.\\pipe\\dockerDesktopLinuxEngine"));

        assertThat(DockerCodeRunner.rootMessage(wrapped))
                .contains("dockerDesktopLinuxEngine")
                .doesNotContain("java.lang.RuntimeException");
    }

    @Test
    @DisplayName("没有 message 时退回异常类名，不会返回 null")
    void rootMessageFallsBackToClassName() {
        assertThat(DockerCodeRunner.rootMessage(new RuntimeException())).isEqualTo("RuntimeException");
    }
}
