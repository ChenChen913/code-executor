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
# 第 2 层：追加解释型语言
# ------------------------------------------------------------
FROM runner-base AS runner-polyglot

USER root
RUN apk add --no-cache python3 nodejs
USER 10001:10001

# ------------------------------------------------------------
# 第 3 层：最小化（推荐用于生产）
#
# 为什么值得单独做一层：容器是只读根文件系统，但"只读"挡不住【读】。
# 用户代码可以 cat /etc/passwd、可以遍历 /usr/lib/python3.*/——
# 镜像里有什么，他就能看到什么。所以缩小镜像 = 缩小可读面 = 减少信息泄露。
#
# 删掉的都是"运行时用不到"的东西：
#   jmods / src.zip / include  —— JDK 的模块定义、源码、JNI 头文件，运行 java 不需要
#   man / doc / locale 数据    —— 文档
#   python 的 test/idlelib/tkinter —— 测试套件和用不到的 GUI 库
#
# build:
#   docker build --target runner-minimal -t code-executor-runner-min:1 .
# ------------------------------------------------------------
FROM runner-polyglot AS runner-minimal

USER root
RUN set -eux; \
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"; \
    rm -rf "$JAVA_HOME/jmods" \
           "$JAVA_HOME/lib/src.zip" \
           "$JAVA_HOME/include" \
           "$JAVA_HOME/man"; \
    # python 的版本目录名带版本号，用通配符匹配；不存在也不报错
    rm -rf /usr/lib/python3*/test \
           /usr/lib/python3*/idlelib \
           /usr/lib/python3*/tkinter \
           /usr/lib/python3*/ensurepip \
           /usr/lib/python3*/lib2to3 \
           /usr/lib/python3*/__pycache__; \
    rm -rf /usr/share/man /usr/share/doc /usr/share/locale /usr/share/i18n; \
    rm -rf /var/cache/apk/*; \
    # 收尾自检：删完之后 java / python3 / node 必须还能跑，
    # 否则就是删过头了 —— 让构建直接失败，而不是产出一个坏镜像
    java -version; \
    python3 -c "print('python ok')"; \
    node -e "console.log('node ok')"

USER 10001:10001

# ------------------------------------------------------------
# 第 4 层：拍平（最终产物）
#
# ⚠️ 这一层是必需的，不是画蛇添足。Docker 的镜像是分层叠加的：
#    在第 3 层 rm 掉的文件，仍然完整地留在下面那几层里 ——
#    所以只看 `docker images` 的话，第 3 层一个字节都没省下来。
#
# 把整个文件系统 COPY 到一个干净的 alpine 上，所有层被压成一层，
# 被删掉的东西才真正消失。这样镜像体积和【容器内可读的文件】同时变小。
#
# 副作用要注意：COPY --from 会覆盖基础镜像自己的 /etc 等目录，
# 所以这里用 scratch —— 内容【全部】来自上一层，一个字节的旧层都不带。
# 用 scratch 而不是 alpine 还有一个现实原因：国内拉不到 Docker Hub 的 alpine，
# 而 scratch 是内置的、不需要拉取。
# ------------------------------------------------------------
FROM scratch AS runner-squashed

COPY --from=runner-minimal / /

USER 10001:10001
WORKDIR /workspace
# ⚠️ scratch 不带任何 ENV，基础镜像原本设的 JAVA_HOME / PATH 全丢了。
# 不显式补回来的话，java 就"消失"了（实测踩到：python/node 正常，java: not found）。
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    HOME=/tmp \
    TMPDIR=/tmp \
    LANG=C.UTF-8
CMD ["sh", "-c", "sleep 120"]
