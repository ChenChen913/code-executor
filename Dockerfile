# ============================================================
# 运行用户代码的镜像
#
# 设计原则：分层。每一层都能单独 build 出来用。
#   docker build --target runner-base     -t code-executor-runner-jvm:1 .   # 只要 JVM
#   docker build --target runner-polyglot -t code-executor-runner:1    .   # 全量（默认）
#
# 为什么不在一个 RUN 里装完所有语言？
#   镜像每多一层语言工具链，攻击面就多一份（任何一个 CVE 都是入口）。
#   分层之后，只想跑 Java 的部署就不用把 Python / Node 装进镜像。
# ============================================================

# ------------------------------------------------------------
# 第 1 层：只负责"安全默认值"和 JVM，不含额外语言
# ------------------------------------------------------------
FROM eclipse-temurin:17-jdk-alpine AS runner-base

# 建一个非 root 用户。执行器会用 --user 10001:10001 再指定一次，
# 这里是双保险：就算执行器忘了传，镜像本身也不会用 root 跑。
# -H 表示不要创建 home 目录（/workspace 已经存在）
RUN addgroup -g 10001 -S runner \
 && adduser -u 10001 -S -G runner -H -s /sbin/nologin runner \
 && mkdir -p /workspace \
 && chown -R 10001:10001 /workspace

USER 10001:10001
WORKDIR /workspace

# 根文件系统是只读的，所以把需要写的东西都指到 /tmp（由执行器挂 tmpfs）
ENV HOME=/tmp \
    TMPDIR=/tmp \
    LANG=C.UTF-8

# 容器默认活 120 秒。
# 这是"保命"机制：真执行时执行器会按 编译超时+运行超时+余量 覆盖这个值，
# 万一执行器自己崩了没来得及清理，容器也会自己退出，不会永久占着内存。
CMD ["sh", "-c", "sleep 120"]

# ------------------------------------------------------------
# 第 2 层：追加解释型语言（默认构建目标）
# ------------------------------------------------------------
FROM runner-base AS runner-polyglot

USER root
RUN apk add --no-cache python3 nodejs
USER 10001:10001
