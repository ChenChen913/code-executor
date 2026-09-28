package com.chenchen.codeexecutor;

import com.chenchen.codeexecutor.config.ExecutorProperties;
import com.chenchen.codeexecutor.security.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 在线代码执行器。
 *
 * <p>对外只做一件事：收到一段代码，在加固过的 Docker 容器里跑一遍，把结果回给你。
 *
 * <p>{@code @EnableScheduling} 是给"孤儿容器回收器"用的：执行器自己崩了、被 kill 了，
 * 都可能留下没清理的容器，需要有个定时任务兜底。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({ExecutorProperties.class, AuthProperties.class})
public class CodeExecutorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeExecutorApplication.class, args);
    }
}
