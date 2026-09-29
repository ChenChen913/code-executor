package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.error.ApiException;
import com.chenchen.codeexecutor.language.LanguageSpec;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 创建与销毁容器。
 *
 * <p>抽出来是因为有<b>两个地方</b>要创建容器：正常执行流程，以及预热池。
 * 两处必须用完全相同的加固参数——如果池子里的容器少了一个 {@code --network none}，
 * 那就是一个只在"命中预热"时才出现的漏洞，极其难查。
 *
 * <p>这里也集中了 Docker 异常到 HTTP 语义的翻译（找不到镜像 → 503、
 * 连不上守护进程 → 503、其他 → 原样上抛交给 500）。
 */
@Component
@ConditionalOnProperty(name = "executor.dry-run", havingValue = "false", matchIfMissing = true)
public class ContainerFactory {

    private static final Logger log = LoggerFactory.getLogger(ContainerFactory.class);

    private final DockerClient docker;
    private final ExecutorProperties properties;
    private final SandboxPolicy sandbox;

    public ContainerFactory(DockerClient docker, ExecutorProperties properties, SandboxPolicy sandbox) {
        this.docker = docker;
        this.properties = properties;
        this.sandbox = sandbox;
    }

    /**
     * 创建并启动一个容器。
     *
     * @param name            容器名的一部分，便于在 {@code docker ps} 里认出来
     * @param spec            决定资源限制（内存、pids）的语言配方
     * @param keepAliveSeconds 容器的"保命时长"：睡够这么久就自己退出
     * @return 容器 id
     */
    public String createAndStart(String name, LanguageSpec spec, int keepAliveSeconds) {
        try {
            CreateContainerCmd command = docker.createContainerCmd(properties.getImage())
                    .withName("code-executor-" + name)
                    .withUser(properties.getUser())
                    .withWorkingDir(SandboxPolicy.WORKSPACE)
                    .withEnv("HOME=/tmp", "TMPDIR=/tmp", "LANG=C.UTF-8")
                    .withLabels(sandbox.labels(name))
                    // 我们不用容器自身的 stdout，所有输出都从 exec 拿
                    .withAttachStdout(false)
                    .withAttachStderr(false)
                    .withCmd("sh", "-c", "sleep " + keepAliveSeconds)
                    .withHostConfig(sandbox.hostConfig(spec));

            String containerId = command.exec().getId();
            docker.startContainerCmd(containerId).exec();
            return containerId;

        } catch (NotFoundException e) {
            throw ApiException.sandboxUnavailable(
                    "Docker 中找不到镜像 " + properties.getImage()
                            + "。请先在项目根目录执行：docker build -t " + properties.getImage() + " .", e);
        } catch (DockerException e) {
            throw ApiException.sandboxUnavailable("Docker 返回错误：" + rootMessage(e), e);
        } catch (RuntimeException e) {
            if (hasIoCause(e)) {
                throw ApiException.sandboxUnavailable(
                        "连不上 Docker 守护进程：" + rootMessage(e)
                                + "。请确认 Docker 已启动、executor.docker.host 配置正确。", e);
            }
            throw e;
        }
    }

    /** 容器还在运行吗。用于租用前校验预热容器是否还活着。 */
    public boolean isRunning(String containerId) {
        try {
            return Boolean.TRUE.equals(
                    docker.inspectContainerCmd(containerId).exec().getState().getRunning());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 强制删除容器。删不掉不抛异常，交给回收器兜底。 */
    public void destroy(String containerId) {
        if (containerId == null) {
            return;
        }
        try {
            docker.removeContainerCmd(containerId).withForce(true).exec();
        } catch (RuntimeException e) {
            log.warn("删除容器 {} 失败，将由回收器兜底：{}", containerId, e.getMessage());
        }
    }

    /**
     * 判断一个异常是不是"连不上守护进程"这一类。
     *
     * <p>判据是<b>因果链里有没有 {@link java.io.IOException}</b>，而不是看异常类型。
     * docker-java 在连不上时抛的是裸 {@code RuntimeException} 包着
     * {@code NoSuchFileException} / {@code ConnectException}，都不是 {@code DockerException}。
     * 这个判据顺带把"环境问题"（该 503）和"代码 bug"（该 500）分开了。
     */
    public static boolean hasIoCause(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof java.io.IOException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    /** 取因果链最底层的 message，避免只看到无信息量的外层包装。 */
    public static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current.getCause() != null && current.getCause() != current && depth < 16; depth++) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}
