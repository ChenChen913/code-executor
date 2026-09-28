package com.chenchen.codeexecutor.config;

import com.chenchen.codeexecutor.language.LanguageSpec;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行器的全部可调参数，对应 application.yml 里的 {@code executor.*}。
 *
 * <p>把参数集中在一个类里，是为了让"哪些东西能调"一目了然；
 * 尤其是下面这些和资源/安全相关的数字，都属于上线前必须按机器规格重新算一遍的。
 */
@ConfigurationProperties(prefix = "executor")
public class ExecutorProperties {

    /** 运行用户代码的镜像。 */
    private String image = "code-executor-runner:1";

    /** 演练模式：只渲染将要下发的命令，不真的创建容器。 */
    private boolean dryRun = false;

    /** 容器内运行代码的用户（必须非 root）。 */
    private String user = "10001:10001";

    /** 单次提交的代码上限（UTF-8 字节数）。 */
    private int maxCodeBytes = 64 * 1024;

    /** stdout / stderr 各自最多回传多少字节。 */
    private int maxOutputBytes = 64 * 1024;

    /** 最大并发执行数，超出直接拒绝（不排队）。 */
    private int maxConcurrentExecutions = 4;

    /** 容器工作目录（tmpfs）大小，单位 MB。 */
    private int workspaceSizeMb = 64;

    /** 容器 /tmp（tmpfs）大小，单位 MB。 */
    private int tmpSizeMb = 16;

    /** 容器保命时长在"编译超时 + 运行超时"之上额外加多少秒。 */
    private int containerLifetimeSlackSeconds = 30;

    private Docker docker = new Docker();
    private Reaper reaper = new Reaper();

    /** 语言配置，key 是语言 id（java / python / ...）。 */
    private Map<String, LanguageSpec> languages = new LinkedHashMap<>();

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public int getMaxCodeBytes() {
        return maxCodeBytes;
    }

    public void setMaxCodeBytes(int maxCodeBytes) {
        this.maxCodeBytes = maxCodeBytes;
    }

    public int getMaxOutputBytes() {
        return maxOutputBytes;
    }

    public void setMaxOutputBytes(int maxOutputBytes) {
        this.maxOutputBytes = maxOutputBytes;
    }

    public int getMaxConcurrentExecutions() {
        return maxConcurrentExecutions;
    }

    public void setMaxConcurrentExecutions(int maxConcurrentExecutions) {
        this.maxConcurrentExecutions = maxConcurrentExecutions;
    }

    public int getWorkspaceSizeMb() {
        return workspaceSizeMb;
    }

    public void setWorkspaceSizeMb(int workspaceSizeMb) {
        this.workspaceSizeMb = workspaceSizeMb;
    }

    public int getTmpSizeMb() {
        return tmpSizeMb;
    }

    public void setTmpSizeMb(int tmpSizeMb) {
        this.tmpSizeMb = tmpSizeMb;
    }

    public int getContainerLifetimeSlackSeconds() {
        return containerLifetimeSlackSeconds;
    }

    public void setContainerLifetimeSlackSeconds(int containerLifetimeSlackSeconds) {
        this.containerLifetimeSlackSeconds = containerLifetimeSlackSeconds;
    }

    public Docker getDocker() {
        return docker;
    }

    public void setDocker(Docker docker) {
        this.docker = docker;
    }

    public Reaper getReaper() {
        return reaper;
    }

    public void setReaper(Reaper reaper) {
        this.reaper = reaper;
    }

    public Map<String, LanguageSpec> getLanguages() {
        return languages;
    }

    public void setLanguages(Map<String, LanguageSpec> languages) {
        this.languages = languages;
    }

    /** Docker 连接相关配置。 */
    public static class Docker {

        /** 留空 = 使用 DOCKER_HOST 环境变量或 Docker 默认 socket。 */
        private String host = "";

        /** 与 Docker daemon 建立连接的超时（秒）。 */
        private int connectTimeoutSeconds = 10;

        /** 单次 Docker API 调用的响应超时（秒）。
         *  要留得比"最长的一次容器操作"还长——拉镜像、复制大文件都可能花几十秒。 */
        private int responseTimeoutSeconds = 120;

        /** Docker 客户端连接池大小。 */
        private int maxConnections = 32;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getResponseTimeoutSeconds() {
            return responseTimeoutSeconds;
        }

        public void setResponseTimeoutSeconds(int responseTimeoutSeconds) {
            this.responseTimeoutSeconds = responseTimeoutSeconds;
        }

        public int getMaxConnections() {
            return maxConnections;
        }

        public void setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
        }
    }

    /** 孤儿容器回收。 */
    public static class Reaper {

        private boolean enabled = true;

        private long intervalMillis = 60_000L;

        /** 容器创建后至少存活这么久才允许被回收，避免误杀正在执行的容器。 */
        private long graceSeconds = 120L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getIntervalMillis() {
            return intervalMillis;
        }

        public void setIntervalMillis(long intervalMillis) {
            this.intervalMillis = intervalMillis;
        }

        public long getGraceSeconds() {
            return graceSeconds;
        }

        public void setGraceSeconds(long graceSeconds) {
            this.graceSeconds = graceSeconds;
        }
    }
}
