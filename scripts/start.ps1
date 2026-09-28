<#
.SYNOPSIS
    一条命令把 code-executor 跑起来。

.DESCRIPTION
    帮你做完所有前置：找 JDK 17、检查 Docker、缺镜像就构建、
    没有 API Key 就生成一个并打印出来，最后启动服务。

    每个步骤都会说明它在干什么，出问题会告诉你该改哪里。

.PARAMETER ApiKey
    指定 API Key。不指定时：先用环境变量 EXECUTOR_AUTH_KEYS_0_VALUE，
    再没有就自动生成一个并打印（下次要用同一个就把它记下来）。

.PARAMETER Rebuild
    强制重新构建运行镜像（改了 Dockerfile 之后用）。

.PARAMETER DryRun
    演练模式：不创建容器，只把将要下发的 docker 命令渲染出来。

.PARAMETER NoAuth
    关闭鉴权。**只在你本机、且不介意任何人调这个接口时用。**

.EXAMPLE
    powershell -File scripts/start.ps1

.EXAMPLE
    powershell -File scripts/start.ps1 -ApiKey 'my-key-0123456789abcdef'

.EXAMPLE
    powershell -File scripts/start.ps1 -DryRun -NoAuth
#>
[CmdletBinding()]
param(
    [string]$ApiKey,
    [switch]$Rebuild,
    [switch]$DryRun,
    [switch]$NoAuth
)

# 这里刻意用 Continue 而不是 Stop。
# 原因：这个脚本主要是包装原生命令（java / docker / mvn），
# 而它们很爱往 stderr 写「正常」输出——`java -version` 整个都写 stderr，
# `docker build` 的进度也写 stderr。在 Stop 模式下，这些会被当成致命错误直接抛出来。
# 所以改为 Continue，并在每个真正需要判断成败的地方显式检查 $LASTEXITCODE。
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

function Step($n, $text) { Write-Host ''; Write-Host "  [$n] $text" -ForegroundColor Cyan }
function Ok($text)   { Write-Host "      ✅ $text" -ForegroundColor Green }
function Warn($text) { Write-Host "      ⚠️  $text" -ForegroundColor Yellow }
function Die($text)  { Write-Host "      ❌ $text" -ForegroundColor Red; Write-Host ''; exit 1 }

Write-Host ''
Write-Host '  code-executor · 启动' -ForegroundColor White
Write-Host ('  ' + '─' * 60) -ForegroundColor DarkGray

# ── 1. JDK 17 ──────────────────────────────────────────────
Step 1 '找 Java 17（这个项目需要 17 或更高）'
$candidates = @($env:JAVA_HOME, 'D:\java\jdk17', 'D:\java\jdk21') |
    Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) }
$candidates += (Get-ChildItem 'C:\Program Files\Java','C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match 'jdk-?(17|21|22|23)' } | ForEach-Object { $_.FullName })

$javaHome = $null
foreach ($c in $candidates) {
    # 注意：java -version 是往 stderr 输出的。
    # 在 $ErrorActionPreference='Stop' 下，原生命令写 stderr 会被当成致命错误抛出来，
    # 所以这里必须临时放宽。这是个很常见的坑。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $ver = (& (Join-Path $c 'bin\java.exe') -version 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $prevEap
    }
    if ($ver -match 'version "(\d+)') {
        $major = [int]$Matches[1]
        if ($major -ge 17) {
            $javaHome = $c
            Ok "用 $c（Java $major）"
            break
        } else {
            Write-Host "      · 跳过 $c（Java $major，版本太低）" -ForegroundColor DarkGray
        }
    }
}
if (-not $javaHome) {
    Die "没找到 JDK 17+。装一个，或者设置 `$env:JAVA_HOME 指向 JDK 17 目录。`n         当前 JAVA_HOME = '$env:JAVA_HOME'"
}
$env:JAVA_HOME = $javaHome

# ── 2. Docker ──────────────────────────────────────────────
Step 2 '检查 Docker'
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Die 'PATH 里没有 docker 命令。' }
$serverVersion = & docker version --format '{{.Server.Version}}' 2>$null
if (-not $serverVersion) {
    Die "Docker 守护进程没在运行。先把 Docker Desktop 启动起来，再跑这个脚本。`n         验证方式：docker ps 能输出表头。"
}
Ok "守护进程正常（Engine $serverVersion）"

# ── 3. 运行镜像 ────────────────────────────────────────────
Step 3 '检查运行镜像 code-executor-runner:1'
$hasImage = & docker images -q code-executor-runner:1 2>$null
if ($hasImage -and -not $Rebuild) {
    Ok '镜像已存在（要强制重建加 -Rebuild）'
} else {
    Write-Host '      正在构建（第一次要几分钟，会拉基础镜像）...' -ForegroundColor DarkGray
    & docker build -t code-executor-runner:1 . 2>&1 | Select-String -Pattern 'DONE|ERROR|naming to' | Select-Object -Last 4 |
        ForEach-Object { Write-Host "      $($_.Line.Trim())" -ForegroundColor DarkGray }
    if ($LASTEXITCODE -ne 0) {
        Die "构建失败。国内网络常见原因：拉不到基础镜像。`n         解决：给 Docker 配镜像加速，或先手动执行：`n           docker pull docker.m.daocloud.io/library/eclipse-temurin:17-jdk-alpine`n           docker tag  docker.m.daocloud.io/library/eclipse-temurin:17-jdk-alpine eclipse-temurin:17-jdk-alpine"
    }
    Ok '镜像构建完成'
}

# ── 4. API Key ─────────────────────────────────────────────
Step 4 '准备 API Key'
$authArgs = @()
if ($NoAuth) {
    $authArgs = @('--executor.auth.enabled=false')
    Warn '已按 -NoAuth 关闭鉴权 —— 任何能访问 8080 的人都能执行代码，别这么放到公网'
} else {
    if (-not $ApiKey) { $ApiKey = $env:EXECUTOR_AUTH_KEYS_0_VALUE }
    $generated = $false
    if (-not $ApiKey) {
        $bytes = New-Object byte[] 24
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $ApiKey = [Convert]::ToBase64String($bytes)
        $generated = $true
    }
    $env:EXECUTOR_AUTH_KEYS_0_NAME  = 'local'
    $env:EXECUTOR_AUTH_KEYS_0_VALUE = $ApiKey
    $authArgs = @()
    if ($generated) {
        Ok '已生成新的 API Key（记下来，调用时要带）：'
        Write-Host ''
        Write-Host "         $ApiKey" -ForegroundColor Yellow
        Write-Host ''
    } else {
        Ok '使用传入/环境变量里的 API Key'
    }
    Write-Host "      调用时要带请求头： X-API-Key: $ApiKey" -ForegroundColor DarkGray
}

# ── 5. 启动 ────────────────────────────────────────────────
Step 5 '启动服务'
Write-Host ''
Write-Host ('  ' + '─' * 60) -ForegroundColor DarkGray
Write-Host '  就绪后可以用另一个窗口执行：' -ForegroundColor DarkGray
Write-Host '    powershell -File scripts\demo.ps1            # 7 个例子的演示' -ForegroundColor DarkGray
Write-Host '    powershell -File scripts\e2e-test.ps1        # 32 项完整测试' -ForegroundColor DarkGray
Write-Host '    powershell -File scripts\watch-container.ps1 # 看容器的一生' -ForegroundColor DarkGray
Write-Host '  停止：Ctrl+C' -ForegroundColor DarkGray
Write-Host ('  ' + '─' * 60) -ForegroundColor DarkGray
Write-Host ''

$mvnArgs = @()
if ($DryRun) { $mvnArgs += '--executor.dry-run=true' }
$mvnArgs += $authArgs

if ($mvnArgs.Count -gt 0) {
    & mvn spring-boot:run "-Dspring-boot.run.arguments=$($mvnArgs -join ' ')"
} else {
    & mvn spring-boot:run
}
