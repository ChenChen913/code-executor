package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动自检：Docker 通不通、运行镜像在不在。
 *
 * <p>刻意"只警告、不拦截启动"。理由：起不来的服务连一句有用的错误都告诉不了你；
 * 能起来、日志里写清楚"去执行 docker build"，反而更好排查。
 * 真的有问题时，执行请求会返回 503 并带上同样的提示。
 */
@Component
@ConditionalOnProperty(name = "executor.dry-run", havingValue = "false", matchIfMissing = true)
public class DockerStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(DockerStartupCheck.class);

    private final DockerClient docker;
    private final ExecutorProperties properties;

    public DockerStartupCheck(DockerClient docker, ExecutorProperties properties) {
        this.docker = docker;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        try {
            docker.pingCmd().exec();
        } catch (RuntimeException e) {
            log.warn("""
                    连不上 Docker：{}
                    执行器已启动，但所有执行请求都会返回 503。
                    排查顺序：1) Docker Desktop 是否已启动  2) docker ps 能否正常输出  \
                    3) executor.docker.host 配置是否正确
                    如果本机就是没有 Docker，可以用演练模式跑通整个流程：--executor.dry-run=true""",
                    rootMessage(e));
            return;
        }

        try {
            docker.inspectImageCmd(properties.getImage()).exec();
            log.info("运行镜像已就绪：{}", properties.getImage());
        } catch (NotFoundException e) {
            log.warn("""
                    找不到运行镜像：{}
                    请先在项目根目录执行：docker build -t {} .""",
                    properties.getImage(), properties.getImage());
        } catch (RuntimeException e) {
            log.warn("检查运行镜像失败：{}", rootMessage(e));
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}
