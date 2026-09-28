package com.chenchen.codeexecutor.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Docker 客户端。
 *
 * <p><b>这里有一个真实踩过的坑，值得写下来：</b>
 * docker-java 3.7 的 {@code DockerClientImpl.getInstance(config)} <b>不会</b>自动装配传输层。
 * 用它拿到的客户端看起来一切正常——能创建 Bean、能启动应用、
 * 演练模式下也毫无问题——但<b>第一次真正下发命令时</b>会抛：
 *
 * <pre>
 * NullPointerException: dockerCmdExecFactory was not specified
 *   at DockerClientImpl.createContainerCmd
 * </pre>
 *
 * <p>这个坑的特点是：单元测试发现不了，dry-run 发现不了，只有真的去创建一个容器才会暴露。
 * 正确做法是显式构造一个 {@link DockerHttpClient} 再传进去。
 *
 * <p>传输层选 {@code docker-java-transport-zerodep}（自包含、依赖全部 relocate 过），
 * 而不是 jersey/netty 那两个：后者会各自带进一份 Jackson 或 Netty，
 * 和 Spring Boot 管理的版本容易打架。
 *
 * <p>另外注意：<b>这里不发任何连接请求</b>。{@code getInstance} 只是把配置组装好，
 * 真正连 Docker 是在第一次下发命令时。这一点很重要——Docker Desktop 没启动时，
 * 执行器依然能正常启动、能返回清晰的错误，而不是起不来。
 *
 * <p>整个 Bean 在演练模式下不存在，所以 {@code executor.dry-run=true} 时
 * 进程里没有任何一行代码会去碰 Docker。
 */
@Configuration
@ConditionalOnProperty(name = "executor.dry-run", havingValue = "false", matchIfMissing = true)
public class DockerClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DockerClientConfiguration.class);

    @Bean(destroyMethod = "close")
    public DockerClient dockerClient(ExecutorProperties properties) {
        ExecutorProperties.Docker dockerProperties = properties.getDocker();

        DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder();
        String host = dockerProperties.getHost();
        if (host != null && !host.isBlank()) {
            builder.withDockerHost(host);
        }
        DefaultDockerClientConfig config = builder.build();

        Duration connectionTimeout = Duration.ofSeconds(dockerProperties.getConnectTimeoutSeconds());
        Duration responseTimeout = Duration.ofSeconds(dockerProperties.getResponseTimeoutSeconds());

        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(dockerProperties.getMaxConnections())
                .connectionTimeout(connectionTimeout)
                .responseTimeout(responseTimeout)
                .build();

        log.info("Docker 客户端已配置：host={} 连接超时={}s 响应超时={}s 最大连接={}",
                config.getDockerHost(),
                connectionTimeout.toSeconds(),
                responseTimeout.toSeconds(),
                dockerProperties.getMaxConnections());

        return DockerClientImpl.getInstance(config, httpClient);
    }
}
