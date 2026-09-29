<h1 align="center">Online Code Executor</h1>

<p align="center">
  <a href="./README.md">简体中文</a> | <b>English</b>
  <br><br>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT"></a>
</p>

Online code executor: a multi-language code sandbox built on Spring Boot + Docker. One container per request, never reused, with 15 hardening measures on by default (no network, read-only root filesystem, non-root, all capabilities dropped, pids/memory/CPU limits).

There are no screenshots or GIFs in this repository, so here is real end-to-end test output instead.

```text
──────────────────────────────────────────────────────────────
  1. 功能 — 三种语言各跑一次
──────────────────────────────────────────────────────────────
  [PASS] Python 执行成功
         status=SUCCESS exitCode=0 duration=188ms
  [PASS] Java 编译+运行成功
         status=SUCCESS duration=289ms
  [PASS] JavaScript 执行成功
         status=SUCCESS duration=238ms
  [PASS] UTF-8 中文与 emoji 正确回传
         stdout=中文输出测试：你好，世界 👋

──────────────────────────────────────────────────────────────
  3. 安全回归
──────────────────────────────────────────────────────────────
  [PASS] 断网：容器内连不上外网
         stdout=NETWORK_BLOCKED:OSError
  [PASS] 只读根文件系统：写不了 /etc
         stdout=ROOTFS_READONLY:OSError
  [PASS] 非 root 运行：uid 不是 0
         stdout=UID=10001
  [PASS] 进程数限制：挡住了 fork
         stdout=FORK_BLOCKED_AT=22 [Errno 11] Resource temporarily unavailable
  [PASS] 特权已丢弃：mount 不成功
         stdout=MOUNT_DENIED

  通过 53 / 53
```

> The above is a verbatim excerpt from `scripts/e2e-test.ps1`. The test runner prints in Chinese.

## Table of Contents

- [Why this project](#why-this-project)
- [Quick Start](#quick-start)
- [Usage](#usage)
- [Configuration](#configuration)
- [Project Structure](#project-structure)
- [Development](#development)
- [FAQ](#faq)
- [Known Limitations](#known-limitations)
- [Contributing](#contributing)
- [License](#license)

## Why this project

It started as a walkthrough of the tutorial 《在线代码执行器实现教程》, but the goal was not to stop at "it runs". The tutorial's implementation makes a few security compromises — for example, it writes source code into the container with `docker cp`. That approach fails outright against a read-only root filesystem with `container rootfs is marked read-only`, because `docker cp` goes through the daemon-side rootfs view and cannot see tmpfs. This project turns it into a version that holds up on security.

Intended audience: backend developers learning Docker container isolation, and teams that need a sandbox for a judge system but do not want to adopt a full judging platform.

How it differs from [Judge0](https://github.com/judge0/judge0): Judge0 is a complete judging platform (database, queue, multi-language packages, web UI) with a heavier setup. This project implements only the sandbox layer, and lays out all 15 hardening measures, the reasoning behind each trade-off, and the measured numbers, so it can be read as a reference implementation.

### Execution flow

```mermaid
sequenceDiagram
    participant C as Caller
    participant A as Executor (Spring Boot)
    participant P as Warm pool
    participant D as Docker daemon
    participant S as Sandbox container

    C->>A: POST /api/v1/executions (X-API-Key)
    A->>A: Authenticate → quota → validate
    A->>A: Acquire a concurrency slot (429 if unavailable)
    A->>P: Lease a pre-warmed container
    alt Pool hit
        P-->>A: Created and started, never used before
    else Pool miss
        A->>D: Create and start a container
    end
    A->>S: exec: write all source files and stdin in one call (base64 via argv)
    A->>S: exec: compile (compiled languages only; may hit the compile cache)
    A->>S: exec: run, watching the timeout
    S-->>A: stdout / stderr / exit code
    A->>D: Destroy the container (on success, error, or timeout alike)
    A-->>C: Structured result (SUCCESS / COMPILE_ERROR / RUNTIME_ERROR / TIMEOUT / OUT_OF_MEMORY)
```

## Quick Start

Prerequisites:

| Dependency | Version | Notes |
|---|---|---|
| JDK | **17** | The project compiles for 17; JDK 11 fails with `class file version 61.0` |
| Maven | 3.9+ | Or the bundled one in your IDE |
| Docker Engine | **20.10+** | Needs `--pids-limit`, `--memory-swap`, and tmpfs support; on Windows use Docker Desktop (WSL2 backend) |

```sh
git clone https://github.com/ChenChen913/code-executor.git
cd code-executor

# 1. Build the sandbox image. Takes about 2 minutes the first time.
docker build --target runner-squashed -t code-executor-runner-min:1 .

# 2. Start the executor. The script detects JDK 17, checks Docker,
#    builds the image if missing, and generates an API key.
powershell -ExecutionPolicy Bypass -File scripts/start.ps1
```

The script prints the generated API key at the end. Startup is complete once you see `Started CodeExecutorApplication`.

Verify:

```sh
curl -s http://localhost:8080/actuator/health
# {"status":"UP"}
```

`/actuator/health` is the only endpoint that skips authentication, so it can be wired straight into Kubernetes or a monitoring system.

Without PowerShell (Linux / macOS), starting the app manually is equivalent to:

```sh
JAVA_HOME=/path/to/jdk17 \
EXECUTOR_AUTH_KEYS_0_NAME=local \
EXECUTOR_AUTH_KEYS_0_VALUE=local-key-0123456789abcdef \
mvn spring-boot:run
```

## Usage

Submit a piece of Java code and get a structured result back:

```sh
curl -s -X POST http://localhost:8080/api/v1/executions \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: <your key>' \
  -d '{
        "language": "java",
        "code": "public class Main { public static void main(String[] a){ System.out.println(\"hi\"); } }"
      }'
```

```json
{
  "jobId": "…",
  "language": "java",
  "status": "SUCCESS",
  "success": true,
  "exitCode": 0,
  "stdout": "hi\n",
  "stderr": "",
  "compileOutput": "",
  "truncated": false,
  "timedOut": false,
  "durationMs": 289,
  "compileCached": false
}
```

Multiple files and standard input:

```sh
curl -s -X POST http://localhost:8080/api/v1/executions \
  -H 'Content-Type: application/json' -H 'X-API-Key: <your key>' \
  -d '{
        "language": "python",
        "code": "import helper\nprint(helper.add(*map(int, input().split())))",
        "files": { "helper.py": "def add(a, b):\n    return a + b\n" },
        "stdin": "3 4\n"
      }'
```

Async submission, so long jobs do not hold a connection:

```sh
# Submit and get a taskId immediately
curl -s -X POST http://localhost:8080/api/v1/tasks \
  -H 'Content-Type: application/json' -H 'X-API-Key: <your key>' \
  -d '{"language":"java","code":"…"}'
# {"taskId":"…","language":"java","status":"PENDING"}

# Poll
curl -s http://localhost:8080/api/v1/tasks/<taskId> -H 'X-API-Key: <your key>'
```

Task status (`PENDING` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED`) and code execution status (`SUCCESS` / `COMPILE_ERROR` / …) are two separate layers: the former says whether the task has been processed, the latter says how the code actually ran.

Full API reference: [docs/03-接口文档.md](docs/03-接口文档.md) (Chinese). Security design: [docs/04-安全设计.md](docs/04-安全设计.md) (Chinese).

## Configuration

Configuration lives in `src/main/resources/application.yml`. The environment variable form uses Spring Boot relaxed binding.

<details>
<summary>Full configuration reference (click to expand)</summary>

| Key | Environment variable | Default | Description |
|---|---|---|---|
| `executor.image` | `EXECUTOR_IMAGE` | `code-executor-runner:1` | Sandbox image name |
| `executor.auth.enabled` | `EXECUTOR_AUTH_ENABLED` | `true` | Disabling drops all authentication; local debugging only |
| `executor.auth.keys[n].name` | `EXECUTOR_AUTH_KEYS_0_NAME` | none | Caller identity, used for logs and quotas |
| `executor.auth.keys[n].value` | `EXECUTOR_AUTH_KEYS_0_VALUE` | none | The key itself; **startup fails if shorter than 16 characters** |
| `executor.auth.keys[n].requests-per-minute` | `EXECUTOR_AUTH_KEYS_0_REQUESTS_PER_MINUTE` | `0` (global default) | Per-key override; `-1` means unlimited |
| `executor.dry-run` | `EXECUTOR_DRYRUN` | `false` | Dry-run mode; the whole flow works without Docker |
| `executor.max-concurrent-executions` | `EXECUTOR_MAX_CONCURRENT_EXECUTIONS` | `4` | Global concurrency cap; excess requests get 429 immediately |
| `executor.max-code-bytes` | `EXECUTOR_MAX_CODE_BYTES` | `65536` | Max code size per request |
| `executor.max-files` | `EXECUTOR_MAX_FILES` | `16` | Max number of extra files |
| `executor.max-output-bytes` | `EXECUTOR_MAX_OUTPUT_BYTES` | `65536` | Per-stream cap for stdout / stderr; overflow is truncated and flagged |
| `executor.workspace-size-mb` | `EXECUTOR_WORKSPACE_SIZE_MB` | `64` | Size of the `/workspace` tmpfs |
| `executor.pool.enabled` | `EXECUTOR_POOL_ENABLED` | `true` | Container warm pool |
| `executor.pool.warm-per-language` | `EXECUTOR_POOL_WARM_PER_LANGUAGE` | `1` | Containers pre-warmed per language |
| `executor.async.enabled` | `EXECUTOR_ASYNC_ENABLED` | `true` | When off, `/api/v1/tasks` returns 404 |
| `executor.async.worker-threads` | `EXECUTOR_ASYNC_WORKER_THREADS` | `4` | Should not be lower than `max-concurrent-executions` |
| `executor.compile-cache.enabled` | `EXECUTOR_COMPILE_CACHE_ENABLED` | `true` | Compile artifact cache |
| `executor.quota.default-requests-per-minute` | `EXECUTOR_QUOTA_DEFAULT_REQUESTS_PER_MINUTE` | `60` | Executions per minute, per caller |
| `executor.quota.default-daily-quota` | `EXECUTOR_QUOTA_DEFAULT_DAILY_QUOTA` | `5000` | Executions per day, per caller |
| `executor.seccomp-profile` | `EXECUTOR_SECCOMP_PROFILE` | empty | Path to a custom seccomp profile, see below |
| `server.port` | `SERVER_PORT` | `8080` | Listening port |

</details>

> `EXECUTOR_AUTH_KEYS_0_NAME`, `EXECUTOR_AUTH_KEYS_0_VALUE`, and `EXECUTOR_IMAGE` have been verified in practice; the rest are the relaxed-binding equivalents.

### seccomp profile

`docker/seccomp-profile.json` denies a set of syscalls that a sandboxed program never needs, on top of Docker's default profile: `ptrace`, `bpf`, `mount`, `kexec_load`, the kernel keyring, `userfaultfd`, and others.

```sh
# On Linux: place the file where the daemon can read it, then point at it
sudo cp docker/seccomp-profile.json /etc/code-executor/seccomp-profile.json
# Start with: --executor.seccomp-profile=/etc/code-executor/seccomp-profile.json
```

> ⚠️ This path is resolved on the **machine running the Docker daemon**, not the machine running the executor. With Docker Desktop for Windows the daemon runs inside a WSL VM and cannot read files on the Windows side, so this setting has no effect on Windows. Actual enforcement needs to be verified on Linux.

## Project Structure

```
code-executor/
├── Dockerfile                    4 stages: runner-base → polyglot → minimal → squashed (recommended)
├── docker/seccomp-profile.json   Custom seccomp policy
├── pom.xml                       Spring Boot 3.5.16 / Java 17
├── scripts/
│   ├── start.ps1                 One-command launcher (detects JDK, checks Docker, builds image, generates key)
│   ├── e2e-test.ps1              53 end-to-end assertions against real containers
│   ├── benchmark.ps1             Latency distribution measurement
│   ├── demo.ps1                  Demo script
│   └── watch-container.ps1       Watch the container lifecycle live
├── docs/                         Design, deployment, API, security, test report, handover notes (Chinese)
└── src/main/java/com/chenchen/codeexecutor/
    ├── core/                     Sandbox core
    │   ├── SandboxPolicy.java        Single source of truth for all 15 hardening measures
    │   ├── ContainerFactory.java     Single entry point for creating/destroying containers
    │   ├── ContainerPool.java        Warm pool (pre-warmed, never reused)
    │   ├── DockerCodeRunner.java     The full flow of one execution
    │   ├── CompileCache.java         Compile artifact cache
    │   └── ContainerReaper.java      Orphan container reaping and reconciliation
    ├── security/                 API key auth and per-caller quotas
    ├── language/                 Language recipes (timeouts/memory/pids/commands)
    ├── model/                    Requests, responses, status enums
    └── web/                      HTTP endpoints and unified error handling
```

Before changing anything, read the "关键设计决定" (key design decisions) section at the end of [docs/06-进度与交接.md](docs/06-进度与交接.md). It documents four deliberate trade-offs, including why containers are never reused and why there is no webhook support. (Chinese.)

## Development

```sh
mvn -B test                                          # 115 unit/integration tests, no Docker needed
mvn -B clean package                                 # Package
powershell -File scripts/e2e-test.ps1 -ApiKey <key>  # 53 real-container assertions, needs Docker
powershell -File scripts/benchmark.ps1 -ApiKey <key> # Measure execution latency distribution
```

Two things worth knowing before you change code:

1. **Passing unit tests does not mean real containers still work.** Several issues in this project only surfaced under real-container tests. When the reaper killed in-flight requests, for instance, it looked like "an infinite loop program exits abnormally after 2 seconds, but passes when run alone." Run both suites after any change.
2. **Every `.ps1` under `scripts/` must be saved as UTF-8 with BOM.** PowerShell 5.1 reads `.ps1` files as ANSI, so Chinese comments break parsing with confusing errors such as `意外的标记 "[PASS]"`.

## FAQ

**Q: `mvn` fails with `class file version 61.0`?**
A: `JAVA_HOME` points at JDK 11. This project needs JDK 17: `set JAVA_HOME=D:\java\jdk17` (Windows) or `export JAVA_HOME=/path/to/jdk17`.

**Q: Startup reports the image is missing?**
A: Build it first with `docker build --target runner-squashed -t code-executor-runner-min:1 .`, then start with `--executor.image=code-executor-runner-min:1`.

**Q: It keeps returning `503` saying it cannot reach the Docker daemon?**
A: Docker is not running, or `executor.docker.host` is wrong. The executor separates "environment not ready" (503) from "the executor itself failed" (500), so a 503 means check Docker first.

**Q: `docker build` hangs pulling the base image?**
A: Direct access to Docker Hub is often unavailable from mainland China. Configure a registry mirror in Docker Desktop (for example `docker.m.daocloud.io`). The `FROM scratch` stage needs no pull.

**Q: Getting `429`?**
A: Two possibilities: the concurrency slots are full (the synchronous endpoint fails fast rather than queueing), or you hit the per-caller quota. The `message` field states which one.

**Q: Polling a task returns `404`?**
A: Task results have a TTL (600 seconds by default) and are swept after that. Task state also lives in memory, so restarting the executor clears it.

**Q: Why does Python use `-E -s` instead of `-I`?**
A: `-I` implies `-P`, which strips the script's directory from `sys.path` and breaks same-directory imports like `import helper`. `-E -s` only isolates environment variables and user site-packages while keeping the script directory.

## Known Limitations

The full list is in [docs/06-进度与交接.md](docs/06-进度与交接.md) (Chinese). The parts that affect usage:

- **Only three languages**: Java, Python 3, JavaScript (Node). Adding one means editing `src/main/resources/languages.yml` and the runtime image.
- **No persistence.** Results, async tasks, the compile cache, and caller quotas all live in process memory: a restart clears them, and **multi-node deployment is impossible**.
- **Async tasks cannot cancel a running execution.** `DELETE` only works on queued tasks; a running one returns `409`. Killing containers is a separate mechanism.
- **No webhook callbacks.** The executor runs on an internal network and holds `docker.sock`, so making it POST to a caller-supplied URL is a ready-made SSRF channel. Async results must be polled.
- **The custom seccomp profile has no effect on Windows + Docker Desktop** (the daemon runs in WSL and cannot read Windows-side files). Actual enforcement needs verification on Linux.
- **gVisor / Kata Containers are not implemented.** The development machine is Windows + Docker Desktop and cannot run `runsc`. Current isolation = namespaces + cgroups + capabilities + seccomp + read-only root filesystem, and it **shares the host kernel**.
- **Single node.** Horizontal scaling first requires moving task state, the compile cache, and quotas into Redis.
- **The warm pool's behaviour under concurrency is unverified.** The end-to-end suite runs sequentially.
- **The minimal image only shrinks the readable surface.** `--readonly` does not prevent reading, so whatever is in the image is visible to user code. `runner-squashed` takes the image from 653MB to 484MB and the file count from 2837 to 2540, but that is not the same as security isolation.

## Contributing

Issues and pull requests are welcome: [Issues](https://github.com/ChenChen913/code-executor/issues).

Commit messages follow Conventional Commits: `type(scope): summary`, with a Chinese imperative summary and no trailing period. Types are `feat` / `fix` / `docs` / `refactor` / `perf` / `test` / `build` / `ci` / `chore`.

Verification requirement for any change: both `mvn -B test` and `scripts/e2e-test.ps1` must pass. For new security-relevant behaviour, add an assertion to `scripts/e2e-test.ps1`.

## License

[MIT](LICENSE)
