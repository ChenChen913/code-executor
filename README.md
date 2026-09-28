# code-executor

把一个「跑一段代码」的需求，变成一个**安全、可复用**的 HTTP 接口。

这是跟着《在线代码执行器实现教程（1 - 需求分析和设计）》做出来的实现，但**不是照抄**：
原设计里缺失或不够严谨的地方（尤其是权限、网络、清理这三块）都做了补强，
每一处改动和理由都写在 [docs/01-需求分析和设计.md](docs/01-需求分析和设计.md) 里。

**状态：M0 + API Key 鉴权已完成并通过真实容器验证**——54 个单元/集成测试 + 32 项端到端断言全部通过，
不是"能编译"而已。实测数据见[测试报告](docs/05-测试报告.md)。

> 不熟悉容器的话，先看[需求分析与设计文档的第 0 章](docs/01-需求分析和设计.md#第-0-章--技术背景容器隔离靠什么)：
> 讲清了 namespace / cgroup / capability / seccomp 各管什么、`--read-only` 的能力边界在哪，
> 以及 `docker exec` 与 `docker cp` 为什么是两条不同的路径。后面每一条加固参数都能对到机制上。

---

## 目录

- [它解决什么问题](#它解决什么问题)
- [安全默认值](#安全默认值这个项目的重点)
- [快速开始](#快速开始)
- [没装 Docker？用演练模式](#没装-docker用演练模式)
- [接口](#接口)
- [加一门语言](#加一门语言)
- [配置](#配置)
- [项目结构](#项目结构)
- [测试](#测试)
- [⚠️ 部署前必读](#️-部署前必读)
- [已知限制](#已知限制)
- [文档](#文档)

---

## 它解决什么问题

想验证一小段代码，却要装 IDE、装编译器、配环境——为了跑 3 行代码花 20 分钟。
这个服务让你直接 POST 一段代码，它在加固过的容器里跑完，把结果还给你。

它不是玩具：**默认就是关起来的**。断网、只读文件系统、非 root、丢弃全部特权、
内存/CPU/进程数/超时全都有硬上限。

---

## 安全默认值（这个项目的重点）

每个容器在创建时都会带上这些限制，**默认开启，没有开关可以关掉**。
每一条都有真实容器的实测证据（见[安全设计](docs/04-安全设计.md)）：

| 限制 | 作用 | 实测结果 |
|---|---|---|
| **API Key 鉴权** | 拦住"谁能调这个接口"。**默认开启，没配 key 就启动失败** | 不带 key → 401；带错 key → 401 |
| `--network none` | 断网。挡住 SSRF 探测内网、外传数据、挖矿、当代理 | 连不上，DNS 也解析不出来 |
| `--dns 127.0.0.1` | 不把宿主机 DNS 地址泄露给用户代码 | `/etc/resolv.conf` 里没有内网 IP |
| `--read-only` | 根文件系统只读，改不了 `/etc`，装不了后门 | 写 `/etc` → `Read-only file system` |
| `--tmpfs /workspace` | 唯一可写的地方是内存盘，容器一停就消失 | 可写，且不留痕 |
| `--user 10001:10001` | 非 root 运行 | `getuid()` = 10001 |
| `--cap-drop ALL` | 丢弃全部 Linux capability | `mount()` 被拒 |
| `no-new-privileges` | 有 setuid 二进制也提不了权 | —— |
| `--memory` + `--memory-swap` | 内存上限，且不给 swap | 超限 → `OUT_OF_MEMORY`，退出码 137 |
| `--cpus 1` | 只给一个核 | —— |
| `--pids-limit` | 进程数上限，挡 fork 炸弹 | 第 30 个进程被拒 |
| `--ulimit` | 句柄数 / 进程数 / 单文件大小再各加一道 | —— |
| 输出截断 | `while(true) print()` 打不爆执行器自己的内存 | 写 5MB → 只回传 64 KB |
| 保命时长 | 容器最多活 `编译超时 + 运行超时 + 30s`，执行器崩了也不会永久泄漏 | —— |
| 定时回收 | 扫 `code-executor.managed=true` 标签的孤儿容器 | 回收了旧的，**没有误杀**新的 |
| 不用 `--rm` | 保留容器才能 inspect，判断"是超时还是 OOM" | 退出码 137 的两种情况被正确区分 |

---

## 快速开始

### 0. 前置条件

- JDK **17+**
- Maven 3.9+
- Docker（守护进程要真的在跑：`docker ps` 能出结果）

> **国内网络注意**：`docker build` 会去 `auth.docker.io` 拉基础镜像，这个域名在国内通常连不上。
> 先配镜像加速，或者先把基础镜像拉下来改个名：
> ```bash
> docker pull docker.m.daocloud.io/library/eclipse-temurin:17-jdk-alpine
> docker tag  docker.m.daocloud.io/library/eclipse-temurin:17-jdk-alpine eclipse-temurin:17-jdk-alpine
> ```

### 1. 构建运行镜像

```bash
docker build -t code-executor-runner:1 .
```

### 2. 启动服务

先准备一个 API Key（**鉴权默认开启，没有 key 会启动失败**）：

```bash
export EXECUTOR_AUTH_KEYS_0_NAME=my-app
export EXECUTOR_AUTH_KEYS_0_VALUE=$(openssl rand -base64 32)
```

```powershell
# Windows PowerShell
$env:EXECUTOR_AUTH_KEYS_0_NAME = 'my-app'
$env:EXECUTOR_AUTH_KEYS_0_VALUE = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))
```

然后启动：

```bash
mvn spring-boot:run
```

> 只想本地跑着玩、不想配 key：
> `mvn spring-boot:run -Dspring-boot.run.arguments=--executor.auth.enabled=false`
> （这个开关是刻意做得这么显眼的。）

### 3. 跑一段代码

```bash
curl -X POST http://localhost:8080/api/v1/executions \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: $EXECUTOR_AUTH_KEYS_0_VALUE" \
  -d '{"language":"python","code":"print(1+1)"}'
```

```json
{
  "jobId": "0f2c...",
  "language": "python",
  "status": "SUCCESS",
  "success": true,
  "exitCode": 0,
  "stdout": "2\n",
  "stderr": "",
  "compileOutput": null,
  "truncated": false,
  "timedOut": false,
  "durationMs": 442,
  "message": "运行成功"
}
```

---

## 没装 Docker？用演练模式

不想装 Docker，或者只是想先看懂流程，加上这个参数：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--executor.dry-run=true
```

这时**不会创建任何容器**，接口会把「将会下发给 Docker 的命令」原样渲染出来：

```
docker run -d \
  --name code-executor-... \
  --user 10001:10001 \
  --network none \
  --read-only \
  --tmpfs /workspace:rw,exec,nosuid,nodev,size=64m,mode=1777 \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --memory 128m --memory-swap 128m \
  --cpus 1 \
  --pids-limit 32 \
  code-executor-runner:1 \
  sh -c "sleep 45"
```

---

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/executions` | 执行代码 |
| `GET` | `/api/v1/languages` | 支持的语言与各自配额 |
| `GET` | `/actuator/health` | 健康检查 |

**状态码约定**：只要执行本身完成了就返回 `200`——哪怕用户代码编译失败、超时、被 OOM 杀掉。
这些都是"正常结果"，写在响应体的 `status` 字段里。只有"请求没能被执行"才用 4xx/5xx。

| status | 含义 |
|---|---|
| `SUCCESS` | 跑完了，退出码 0 |
| `COMPILE_ERROR` | 编译没过（`compileOutput` 里有原因） |
| `RUNTIME_ERROR` | 编译过了，但进程非 0 退出（看 `stderr`） |
| `TIMEOUT` | 超时，容器已被强制终止 |
| `OUT_OF_MEMORY` | 内存超限，容器被内核杀掉 |
| `DRY_RUN` | 演练模式，没有真正执行 |

| HTTP | 场景 |
|---|---|
| `400` | 语言不支持 / 代码为空 / 代码超长 / JSON 格式错 |
| `401` | 缺少或错误的 `X-API-Key` |
| `429` | 并发已满（不排队，直接拒绝） |
| `503` | Docker 连不上，或运行镜像没构建 |

完整字段说明见[接口文档](docs/03-接口文档.md)。

---

## 加一门语言

**改配置就行，不用改 Java 代码。** 编辑 [`src/main/resources/languages.yml`](src/main/resources/languages.yml)：

```yaml
executor:
  languages:
    go:
      display-name: Go 1.22
      enabled: true
      source-file-name: main.go
      compile-command: [ "go", "build", "-o", "main", "main.go" ]
      run-command: [ "./main" ]
      compile-timeout-ms: 30000
      run-timeout-ms: 5000
      memory-mb: 256
      pids-limit: 64
```

再去 `Dockerfile` 里加一层装上 `go` 就行了。

> 命令一律写成**数组**，不要写成一整条字符串。数组形式下 Docker 会直接 `execve`，
> 中间没有 shell，用户就不可能靠拼接参数注入额外命令。

---

## 配置

全部参数在 [`application.yml`](src/main/resources/application.yml) 的 `executor.*` 下。最常改的几个：

| 配置 | 默认 | 说明 |
|---|---|---|
| `executor.auth.enabled` | `true` | API Key 鉴权。**没有配置 key 时启动失败** |
| `executor.auth.header-name` | `X-API-Key` | 从哪个请求头取 key |
| `executor.auth.keys[n].name` / `.value` | 空 | 调用方凭据。支持多个，用于 key 轮换 |
| `executor.auth.public-paths` | `[/actuator/health]` | 免鉴权路径 |
| `executor.image` | `code-executor-runner:1` | 运行用户代码的镜像 |
| `executor.max-concurrent-executions` | `4` | 并发上限，超出返回 429 |
| `executor.max-code-bytes` | `65536` | 单次提交代码上限，⚠️ **不要超过 90 KB** |
| `executor.max-output-bytes` | `65536` | stdout/stderr 各自的上限 |
| `executor.dry-run` | `false` | 演练模式 |
| `executor.reaper.grace-seconds` | `120` | 孤儿容器保护期，**必须大于最长的一次执行** |

> `max-code-bytes` 为什么有隐藏上限？源码是以 base64 形式作为命令行参数传进容器的，
> 而 Linux 单个参数上限是 128 KiB，base64 会膨胀 1/3。

---

## 项目结构

```
src/main/java/com/chenchen/codeexecutor/
├── core/
│   ├── SandboxPolicy.java      ← 所有容器加固规则集中在这里，一眼看全
│   ├── DockerCodeRunner.java   ← 真跑：创建容器 → 写代码 → 编译 → 运行 → 清理
│   ├── DryRunCodeRunner.java   ← 演练：只渲染命令，不碰 Docker
│   ├── ExecutionService.java   ← 校验 + 并发限制
│   ├── ContainerReaper.java    ← 孤儿容器定时回收
│   └── BoundedOutputStream.java← 输出截断，防 while(true) print
├── language/                   ← 语言抽象与注册表
├── model/                      ← 请求/响应/状态
├── security/                   ← API Key 鉴权（过滤器 + 纯逻辑校验器）
└── web/                        ← REST 接口与统一错误处理

scripts/e2e-test.ps1            ← 32 项真实容器端到端测试（含鉴权与安全回归）
docs/                           ← 需求分析 / 部署 / 接口 / 安全 / 测试报告
```

---

## 测试

```bash
mvn test                                        # 54 个单元/集成测试，不需要 Docker

mvn spring-boot:run                             # 终端 1（记得配 API Key）
powershell -File scripts/e2e-test.ps1           # 终端 2：32 项真实容器断言
```

端到端脚本覆盖四块：**鉴权**、功能（三种语言）、错误分类（编译错/运行错/超时/OOM/截断）、
**安全回归（断网/只读/非 root/进程数限制/清理无残留）**。

> 鉴权和安全回归那两节的断言都是**回归保护**：
> 以后有人为了图方便把鉴权关掉、或者删掉 `--network none`，脚本会直接红掉。

---

## ⚠️ 部署前必读

### 1. 鉴权已默认开启，但它是静态共享密钥

`executor.auth.enabled` 默认 `true`，没配 key 就起不来。但它有明确的边界：

- **是共享密钥，不是身份体系。** 所有拿到同一个 key 的调用方在服务端看起来是同一个人，
  日志里只有你给这个 key 起的 `name`
- **没有过期、没有自动轮换。** 轮换靠配置多个 key 手工做：
  加新 key → 部署 → 切换调用方 → 删旧 key
- **没有按 key 的配额。** 一个 key 被打满，其他 key 一起受影响（M2）
- **不做 TLS。** 这个服务只跑 HTTP，**必须由反向代理终止 HTTPS**，
  否则 key 在网络上是明文

### 2. 不要把执行器容器化后挂 `docker.sock`

```yaml
# ❌ 绝对不要这样做
volumes:
  - /var/run/docker.sock:/var/run/docker.sock
```

执行器持有 Docker socket，而 **Docker socket 等价于宿主机 root**——
能访问它的人可以让 Docker 起一个挂载宿主机根目录的容器。
鉴权能挡住"随便什么人都能调接口"，但**挡不住拿到 key 之后的横向移动**。

正确做法：执行器作为普通进程直接跑在宿主机上（systemd 示例见[部署文档](docs/02-部署文档.md)），
用防火墙限制来源。

### 3. 生产环境还缺什么

鉴权只是让"能部署"这件事成立。离生产还有（详见[安全设计](docs/04-安全设计.md)第 4 节）：

- 按调用方的配额与限流（M2）
- 可观测：执行成功率、耗时分布、被杀原因统计（M2）
- seccomp 自定义 profile、最小化镜像（M2）
- 独立节点部署，与主业务、数据库物理隔离（M2）

---

## 已知限制

- **鉴权是静态共享密钥**，没有过期、没有自动轮换、没有按 key 的配额
- **不做排队**，并发满了直接 429
- **同步接口**，编译型语言慢的时候调用方要自己等
- **每请求一个容器**，冷启动开销实在（容器池在路线图上）
- **seccomp 用的是 Docker 默认 profile**，没做更严的自定义策略
- **只读根文件系统仍然可读系统文件**（数据出不去，但信息会被看到）
- 不支持 stdin、多文件、自定义命令行参数
- **没有做过压测**，并发上限 4 是拍脑袋定的

完整的残余风险清单在[安全设计第 4 节](docs/04-安全设计.md#4-残余风险诚实清单)，
路线图在[需求分析第 9 章](docs/01-需求分析和设计.md#第-9-章--实施路线图)。

---

## Windows 用户注意

如果控制台里中文日志显示成乱码（`�Ѽ���`），那是控制台代码页的问题，
不是服务的问题（接口返回的 JSON 是标准 UTF-8）。执行一次即可：

```powershell
chcp 65001
```

---

## 文档

| 文档 | 内容 |
|---|---|
| [01 需求分析与设计](docs/01-需求分析和设计.md) | 原文整理 + 逐条评审 + 改进设计 + **第 0 章：容器隔离的底层机制** |
| [02 部署文档](docs/02-部署文档.md) | 环境准备、配置全表、生产部署、故障排查 |
| [03 接口文档](docs/03-接口文档.md) | 完整字段说明、状态机、多语言调用示例 |
| [04 安全设计](docs/04-安全设计.md) | 威胁模型、加固清单与实测证据、**残余风险** |
| [05 测试报告](docs/05-测试报告.md) | 测试分层、真实数据、**测试中发现的 4 个缺陷**、没测到的部分 |

## License

MIT
