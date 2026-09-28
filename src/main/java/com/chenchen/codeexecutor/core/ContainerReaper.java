package com.chenchen.codeexecutor.core;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 孤儿容器回收器。
 *
 * <p>为什么必须有这个组件？因为"在 finally 里删容器"只覆盖了正常路径。
 * 下面这些情况都会留下没删掉的容器，而每个容器都占着真实内存：
 * <ul>
 *   <li>执行器进程被 {@code kill -9}、被容器编排系统重启</li>
 *   <li>机器断电 / 蓝屏</li>
 *   <li>删除请求本身失败（Docker daemon 正忙）</li>
 * </ul>
 *
 * <p>清理规则很保守，宁可少删不可误删：
 * <ol>
 *   <li>只认 {@code code-executor.managed=true} 这个标签——别人的容器绝不碰</li>
 *   <li>创建时间必须在保护期（grace-seconds）之前，避免误杀正在执行的容器</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "executor.dry-run", havingValue = "false", matchIfMissing = true)
public class ContainerReaper {

    private static final Logger log = LoggerFactory.getLogger(ContainerReaper.class);

    private final DockerClient docker;
    private final ExecutorProperties properties;

    public ContainerReaper(DockerClient docker, ExecutorProperties properties) {
        this.docker = docker;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${executor.reaper.interval-millis:60000}")
    public void reapOrphans() {
        if (!properties.getReaper().isEnabled()) {
            return;
        }

        List<Container> orphans;
        try {
            orphans = docker.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(SandboxPolicy.MANAGED_LABEL, "true"))
                    .exec();
        } catch (RuntimeException e) {
            // Docker 没启动是常态（比如开发机上），这里只留一行 debug 日志，不要刷屏
            log.debug("跳过本轮容器回收：{}", e.getMessage());
            return;
        }

        if (orphans.isEmpty()) {
            return;
        }

        long cutoff = Instant.now().getEpochSecond() - properties.getReaper().getGraceSeconds();
        int removed = 0;
        for (Container container : orphans) {
            if (createdAt(container) > cutoff) {
                continue;
            }
            try {
                docker.removeContainerCmd(container.getId()).withForce(true).exec();
                removed++;
                log.info("回收孤儿容器 {}（job={}）",
                        shorten(container.getId()), jobIdOf(container));
            } catch (RuntimeException e) {
                log.warn("回收容器 {} 失败：{}", shorten(container.getId()), e.getMessage());
            }
        }
        if (removed > 0) {
            log.info("本轮共回收 {} 个孤儿容器（当前共 {} 个受管容器）", removed, orphans.size());
        }
    }

    /**
     * 容器创建时间。
     *
     * <p>优先用我们自己的标签，因为它和执行器用的是同一个时钟；
     * 标签缺失（老版本创建的容器）时退回容器自带的 Created 字段。
     */
    private static long createdAt(Container container) {
        Map<String, String> labels = container.getLabels();
        if (labels != null) {
            String raw = labels.get(SandboxPolicy.CREATED_AT_LABEL);
            if (raw != null) {
                try {
                    return Long.parseLong(raw);
                } catch (NumberFormatException ignored) {
                    // 标签被人改坏了，退回用容器自己的时间
                }
            }
        }
        Long created = container.getCreated();
        return created == null ? 0L : created;
    }

    private static String jobIdOf(Container container) {
        Map<String, String> labels = container.getLabels();
        return labels == null ? "?" : labels.getOrDefault(SandboxPolicy.JOB_LABEL, "?");
    }

    private static String shorten(String containerId) {
        return containerId != null && containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
    }
}
