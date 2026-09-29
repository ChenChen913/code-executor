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

    /** 单次提交的文件数上限（含入口文件与标准输入文件）。 */
    private int maxFiles = 16;

    /** 标准输入的字节上限。 */
    private int maxStdinBytes = 8 * 1024;

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
    private Async async = new Async();
    private Pool pool = new Pool();
    private Quota quota = new Quota();

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

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public int getMaxStdinBytes() {
        return maxStdinBytes;
    }

    public void setMaxStdinBytes(int maxStdinBytes) {
        this.maxStdinBytes = maxStdinBytes;
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

    public Async getAsync() {
        return async;
    }

    public void setAsync(Async async) {
        this.async = async;
    }

    public Pool getPool() {
        return pool;
    }

    public void setPool(Pool pool) {
        this.pool = pool;
    }

    public Quota getQuota() {
        return quota;
    }

    public void setQuota(Quota quota) {
        this.quota = quota;
    }

    /**
     * 按调用方的配额。
     *
     * <p>全局并发上限保证"执行器不被压垮"，配额保证"某个调用方不把名额吃光"。
     * 两者缺一不可：只有全局上限时，一个写错循环的调用方能让其他所有人收到 429。
     */
    public static class Quota {

        private boolean enabled = true;

        /** 调用方默认的每分钟执行次数上限。-1 = 不限，0 = 关闭这道限制。 */
        private int defaultRequestsPerMinute = 60;

        /** 调用方默认的每日执行次数上限。-1 = 不限，0 = 关闭这道限制。 */
        private long defaultDailyQuota = 5000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDefaultRequestsPerMinute() {
            return defaultRequestsPerMinute;
        }

        public void setDefaultRequestsPerMinute(int defaultRequestsPerMinute) {
            this.defaultRequestsPerMinute = defaultRequestsPerMinute;
        }

        public long getDefaultDailyQuota() {
            return defaultDailyQuota;
        }

        public void setDefaultDailyQuota(long defaultDailyQuota) {
            this.defaultDailyQuota = defaultDailyQuota;
        }
    }

    /**
     * 容器预热池配置。
     *
     * <p>池子里放的是"已经创建并启动、但从未被使用过"的容器。
     * <b>用过的容器一律销毁，绝不归还</b>——省的是创建+启动的延迟，
     * 而不是拿隔离性去换性能。
     */
    public static class Pool {

        private boolean enabled = true;

        /** 每种语言预热几个。1 个就能挡掉大部分冷启动；调大更抗并发，代价是常驻内存。 */
        private int warmPerLanguage = 1;

        /**
         * 预热容器的保活时长（秒）。
         *
         * <p>比正常请求的保命时长长得多，否则容器还没被用上就自己退出了。
         * 代价：执行器若崩溃，这些容器最多多活这么久（回收器会在重启后接手）。
         */
        private int warmKeepAliveSeconds = 300;

        /** 补充池子的扫描间隔（毫秒）。 */
        private long refillIntervalMillis = 5_000L;

        /** 启动后多久开始预热（毫秒），给 Dokcer 和镜像检查留点时间。 */
        private long initialDelayMillis = 3_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getWarmPerLanguage() {
            return warmPerLanguage;
        }

        public void setWarmPerLanguage(int warmPerLanguage) {
            this.warmPerLanguage = warmPerLanguage;
        }

        public int getWarmKeepAliveSeconds() {
            return warmKeepAliveSeconds;
        }

        public void setWarmKeepAliveSeconds(int warmKeepAliveSeconds) {
            this.warmKeepAliveSeconds = warmKeepAliveSeconds;
        }

        public long getRefillIntervalMillis() {
            return refillIntervalMillis;
        }

        public void setRefillIntervalMillis(long refillIntervalMillis) {
            this.refillIntervalMillis = refillIntervalMillis;
        }

        public long getInitialDelayMillis() {
            return initialDelayMillis;
        }

        public void setInitialDelayMillis(long initialDelayMillis) {
            this.initialDelayMillis = initialDelayMillis;
        }
    }

    /**
     * 异步任务（{@code POST /api/v1/tasks}）的配置。
     *
     * <p>关掉之后 {@code /api/v1/tasks} 下的接口会直接不存在（返回 404），
     * 同步接口不受影响。
     */
    public static class Async {

        private boolean enabled = true;

        /**
         * 工作线程数。
         *
         * <p><b>不应小于 {@code max-concurrent-executions}</b>：工作线程抢到并发名额才执行，
         * 线程比名额少就意味着名额永远用不满。启动时会检查并告警。
         */
        private int workerThreads = 4;

        /** 队列容量。满了直接拒绝（429），不无限堆积。 */
        private int queueCapacity = 64;

        /** 排队等待执行名额的最长时间（毫秒），超时则任务失败。 */
        private long maxQueueWaitMs = 60_000L;

        /** 任务结果保留时长（秒）。过期后查询返回 404。 */
        private long taskTtlSeconds = 600L;

        /** 保留的任务总数上限，防止堆积吃光内存。 */
        private int maxRetainedTasks = 1000;

        /** 过期任务清理的扫描间隔（毫秒）。 */
        private long sweepIntervalMillis = 30_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public long getMaxQueueWaitMs() {
            return maxQueueWaitMs;
        }

        public void setMaxQueueWaitMs(long maxQueueWaitMs) {
            this.maxQueueWaitMs = maxQueueWaitMs;
        }

        public long getTaskTtlSeconds() {
            return taskTtlSeconds;
        }

        public void setTaskTtlSeconds(long taskTtlSeconds) {
            this.taskTtlSeconds = taskTtlSeconds;
        }

        public int getMaxRetainedTasks() {
            return maxRetainedTasks;
        }

        public void setMaxRetainedTasks(int maxRetainedTasks) {
            this.maxRetainedTasks = maxRetainedTasks;
        }

        public long getSweepIntervalMillis() {
            return sweepIntervalMillis;
        }

        public void setSweepIntervalMillis(long sweepIntervalMillis) {
            this.sweepIntervalMillis = sweepIntervalMillis;
        }
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
