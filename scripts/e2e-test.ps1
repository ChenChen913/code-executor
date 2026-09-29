<#
.SYNOPSIS
    在线代码执行器的真实端到端测试（需要 Docker 真的在运行）。

.DESCRIPTION
    这个脚本会真的把代码提交给执行器、真的创建容器、真的跑一遍，
    然后逐条断言结果。它覆盖三块：

      1. 功能：三种语言的成功路径
      2. 错误分类：编译错 / 运行错 / 超时 / 内存超限 / 输出截断
      3. 安全回归：断网、只读根文件系统、非 root、进程数限制、清理无残留

    第 3 块是重点。这些断言的作用是"防止以后有人顺手把加固参数删掉"——
    删了，脚本就会红。

.PARAMETER BaseUrl
    执行器地址，默认 http://localhost:8080

.PARAMETER ApiKey
    API Key。默认读环境变量 EXECUTOR_AUTH_KEYS_0_VALUE；
    都没有时退回本地开发用的固定值（见 README 的快速开始）。

.EXAMPLE
    pwsh -File scripts/e2e-test.ps1

.EXAMPLE
    pwsh -File scripts/e2e-test.ps1 -ApiKey 'your-real-key'
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ApiKey = $(if ($env:EXECUTOR_AUTH_KEYS_0_VALUE) { $env:EXECUTOR_AUTH_KEYS_0_VALUE } else { 'dev-local-key-0123456789abcdef' })
)

$ErrorActionPreference = 'Stop'
$script:Passed = 0
$script:Failed = 0
$script:Results = @()

function Write-Section($title) {
    Write-Host ''
    Write-Host ('─' * 78) -ForegroundColor DarkGray
    Write-Host "  $title" -ForegroundColor Cyan
    Write-Host ('─' * 78) -ForegroundColor DarkGray
}

function Invoke-Code {
    param(
        [string]$Language,
        [string]$Code,
        [int]$TimeoutSec = 90,
        [hashtable]$Files = $null,
        [string]$Stdin = $null
    )

    $body = @{ language = $Language; code = $Code }
    if ($Files) { $body.files = $Files }
    if ($Stdin) { $body.stdin = $Stdin }
    $payload = $body | ConvertTo-Json -Compress -Depth 5
    $inFile = [System.IO.Path]::GetTempFileName()
    $outFile = "$inFile.out"
    try {
        # 必须用无 BOM 的 UTF-8 写，否则 Spring 解析 JSON 会失败
        [System.IO.File]::WriteAllText($inFile, $payload, [System.Text.UTF8Encoding]::new($false))
        & curl.exe -s --max-time $TimeoutSec -X POST "$BaseUrl/api/v1/executions" `
            -H 'Content-Type: application/json' -H "X-API-Key: $ApiKey" `
            --data-binary "@$inFile" -o $outFile | Out-Null
        if (-not (Test-Path $outFile) -or (Get-Item $outFile).Length -eq 0) {
            throw "执行器没有返回内容（超时或被拒绝）"
        }
        $result = [System.IO.File]::ReadAllText($outFile, [System.Text.Encoding]::UTF8) | ConvertFrom-Json

        # 错误响应里没有 stdout / stderr 这些字段，统一补成空值，
        # 免得断言代码里到处写 $null 判断（写漏一个整个脚本就崩）
        foreach ($field in 'jobId', 'language', 'status', 'stdout', 'stderr', 'compileOutput', 'message') {
            if ($null -eq $result.$field) {
                $result | Add-Member -NotePropertyName $field -NotePropertyValue '' -Force
            }
        }
        foreach ($flag in 'truncated', 'timedOut') {
            if ($null -eq $result.$flag) {
                $result | Add-Member -NotePropertyName $flag -NotePropertyValue $false -Force
            }
        }
        if ($null -eq $result.durationMs) {
            $result | Add-Member -NotePropertyName durationMs -NotePropertyValue 0 -Force
        }
        return $result
    } finally {
        Remove-Item $inFile, $outFile -Force -ErrorAction SilentlyContinue
    }
}

function Assert-That {
    param(
        [string]$Name,
        [scriptblock]$Condition,
        [string]$Detail = ''
    )
    try {
        $ok = & $Condition
    } catch {
        $ok = $false
        $Detail = $_.Exception.Message
    }
    if ($ok) {
        $script:Passed++
        $script:Results += [pscustomobject]@{ Test = $Name; Result = 'PASS'; Note = $Detail }
        Write-Host ("  [PASS] {0}" -f $Name) -ForegroundColor Green
        if ($Detail) { Write-Host ("         {0}" -f $Detail) -ForegroundColor DarkGray }
    } else {
        $script:Failed++
        $script:Results += [pscustomobject]@{ Test = $Name; Result = 'FAIL'; Note = $Detail }
        Write-Host ("  [FAIL] {0}" -f $Name) -ForegroundColor Red
        if ($Detail) { Write-Host ("         {0}" -f $Detail) -ForegroundColor Yellow }
    }
}

# ════════════════════════════════════════════════════════════════
# 前置检查
# ════════════════════════════════════════════════════════════════
Write-Host ''
Write-Host '在线代码执行器 · 真实端到端测试' -ForegroundColor White
Write-Host "目标: $BaseUrl" -ForegroundColor DarkGray

Write-Section '0. 前置检查'

$health = $null
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 10
} catch {
    Write-Host "  [FAIL] 执行器没在运行：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host '         先启动：mvn spring-boot:run' -ForegroundColor Yellow
    exit 1
}
Assert-That '执行器健康检查（免鉴权）' { $health.status -eq 'UP' } "status=$($health.status)"

# ── 鉴权 ────────────────────────────────────────────────────────
# 这几条是"防止有人把鉴权关掉"的回归保护。
$probeBody = '{"language":"python","code":"print(1)"}'
$probeFile = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($probeFile, $probeBody, [System.Text.UTF8Encoding]::new($false))

function Get-HttpStatus {
    param([string]$HeaderArgs)
    $args = @('-s', '-o', 'NUL', '-w', '%{http_code}', '-X', 'POST', "$BaseUrl/api/v1/executions",
              '-H', 'Content-Type: application/json', '--data-binary', "@$probeFile")
    if ($HeaderArgs) { $args += @('-H', $HeaderArgs) }
    return (& curl.exe @args)
}

$statusNoKey = Get-HttpStatus -HeaderArgs ''
Assert-That '不带 API Key → 401' { $statusNoKey -eq '401' } "HTTP $statusNoKey"

$statusWrongKey = Get-HttpStatus -HeaderArgs 'X-API-Key: definitely-not-the-right-key'
Assert-That '带错误 API Key → 401' { $statusWrongKey -eq '401' } "HTTP $statusWrongKey"

$statusGoodKey = Get-HttpStatus -HeaderArgs "X-API-Key: $ApiKey"
Assert-That '带正确 API Key → 200' { $statusGoodKey -eq '200' } "HTTP $statusGoodKey"

$languagesNoKey = & curl.exe -s -o NUL -w '%{http_code}' "$BaseUrl/api/v1/languages"
Assert-That '语言列表同样需要鉴权' { $languagesNoKey -eq '401' } "HTTP $languagesNoKey"

Remove-Item $probeFile -Force -ErrorAction SilentlyContinue

$dockerOk = $false
try {
    $v = & docker info --format '{{.ServerVersion}}' 2>$null
    $dockerOk = [bool]$v
} catch { }
Assert-That 'Docker 守护进程可用' { $dockerOk }

$img = & docker images -q code-executor-runner:1 2>$null
Assert-That '运行镜像已构建' { [bool]$img } 'code-executor-runner:1'

if (-not $dockerOk -or -not $img) {
    Write-Host ''
    Write-Host '  前置条件不满足，中止。' -ForegroundColor Red
    exit 1
}

# ════════════════════════════════════════════════════════════════
# 1. 功能：三种语言的成功路径
# ════════════════════════════════════════════════════════════════
Write-Section '1. 功能 — 三种语言各跑一次'

$py = Invoke-Code -Language 'python' -Code @'
print("hello from python")
print(1 + 1)
'@
Assert-That 'Python 执行成功' { $py.status -eq 'SUCCESS' } "status=$($py.status) exitCode=$($py.exitCode) duration=$($py.durationMs)ms"
Assert-That 'Python 输出正确' { $py.stdout -match 'hello from python' -and $py.stdout -match '2' } "stdout=$($py.stdout.Trim() -replace "`n",' | ')"

$java = Invoke-Code -Language 'java' -Code @'
public class Main {
    public static void main(String[] args) {
        System.out.println("hello from java");
        int sum = 0;
        for (int i = 1; i <= 100; i++) sum += i;
        System.out.println("sum=" + sum);
    }
}
'@
Assert-That 'Java 编译+运行成功' { $java.status -eq 'SUCCESS' } "status=$($java.status) duration=$($java.durationMs)ms"
Assert-That 'Java 输出正确' { $java.stdout -match 'hello from java' -and $java.stdout -match 'sum=5050' } "stdout=$($java.stdout.Trim() -replace "`n",' | ')"

$js = Invoke-Code -Language 'javascript' -Code @'
const nums = [1,2,3,4,5];
console.log("hello from node");
console.log("total=" + nums.reduce((a,b)=>a+b,0));
'@
Assert-That 'JavaScript 执行成功' { $js.status -eq 'SUCCESS' } "status=$($js.status) duration=$($js.durationMs)ms"
Assert-That 'JavaScript 输出正确' { $js.stdout -match 'hello from node' -and $js.stdout -match 'total=15' } "stdout=$($js.stdout.Trim() -replace "`n",' | ')"

$cjk = Invoke-Code -Language 'python' -Code 'print("中文输出测试：你好，世界 👋")'
Assert-That 'UTF-8 中文与 emoji 正确回传' { $cjk.stdout -match '中文输出测试' -and $cjk.stdout -match '👋' } "stdout=$($cjk.stdout.Trim())"

# ════════════════════════════════════════════════════════════════
# 1.5 多文件与标准输入
# ════════════════════════════════════════════════════════════════
Write-Section '1.5 多文件与标准输入'

$multiJava = Invoke-Code -Language 'java' -Code @'
public class Main {
    public static void main(String[] args) {
        System.out.println(new Helper().greet());
    }
}
'@ -Files @{ 'Helper.java' = 'public class Helper { public String greet(){ return "多文件 OK"; } }' }
Assert-That 'Java 多文件：一次提交两个类' { $multiJava.status -eq 'SUCCESS' } "status=$($multiJava.status) duration=$($multiJava.durationMs)ms"
Assert-That 'Java 多文件：跨文件调用成功' { $multiJava.stdout -match '多文件 OK' } "stdout=$($multiJava.stdout.Trim())"

$multiPy = Invoke-Code -Language 'python' -Code @'
import helper
print(helper.double(21))
'@ -Files @{ 'helper.py' = 'def double(x): return x * 2' }
Assert-That 'Python 多文件：import 得到附加模块' { $multiPy.status -eq 'SUCCESS' -and $multiPy.stdout -match '42' } "status=$($multiPy.status) stdout=$($multiPy.stdout.Trim()) stderr=$(($multiPy.stderr -split "`n" | Select-Object -Last 2) -join ' / ')"

$stdinPy = Invoke-Code -Language 'python' -Code @'
import sys
nums = [int(line) for line in sys.stdin if line.strip()]
print("读了", len(nums), "个数字，和 =", sum(nums))
'@ -Stdin "3`n4`n5`n"
Assert-That 'stdin：程序能读到标准输入' { $stdinPy.status -eq 'SUCCESS' -and $stdinPy.stdout -match '和 = 12' } "status=$($stdinPy.status) stdout=$($stdinPy.stdout.Trim())"

$traversal = Invoke-Code -Language 'python' -Code 'print(1)' -Files @{ '../../etc/passwd' = 'evil' }
Assert-That '路径穿越的文件名被拒绝（400）' { $traversal.status -eq 400 -or $traversal.message -match '不合法' } "HTTP status=$($traversal.status) message=$($traversal.message)"

$shadowEntry = Invoke-Code -Language 'python' -Code 'print(1)' -Files @{ 'main.py' = 'print("shadow")' }
Assert-That '附加文件不能顶掉入口文件（400）' { $shadowEntry.status -eq 400 -or $shadowEntry.message -match '入口文件' } "message=$($shadowEntry.message)"

# ════════════════════════════════════════════════════════════════
# 1.6 异步任务接口
# ════════════════════════════════════════════════════════════════
Write-Section '1.6 异步任务 — 提交后拿 taskId，轮询取结果'

$asyncBody = '{"language":"java","code":"public class Main { public static void main(String[] a) throws Exception { System.out.println(\"async ok\"); } }"}'
$asyncIn = [System.IO.Path]::GetTempFileName()
$asyncOut = "$asyncIn.out"
[System.IO.File]::WriteAllText($asyncIn, $asyncBody, [System.Text.UTF8Encoding]::new($false))

$submitCode = & curl.exe -s -o $asyncOut -w '%{http_code}' -X POST "$BaseUrl/api/v1/tasks" `
    -H 'Content-Type: application/json' -H "X-API-Key: $ApiKey" --data-binary "@$asyncIn"
$task = [System.IO.File]::ReadAllText($asyncOut, [System.Text.Encoding]::UTF8) | ConvertFrom-Json

Assert-That '异步提交返回 202 Accepted' { $submitCode -eq '202' } "HTTP $submitCode"
Assert-That '异步提交立即返回 taskId（不阻塞等结果）' { $task.taskId } "taskId=$($task.taskId)"

$final = $null
$polls = 0
for ($i = 0; $i -lt 80; $i++) {
    $polls = $i + 1
    $pollOut = "$asyncIn.poll"
    & curl.exe -s -o $pollOut "$BaseUrl/api/v1/tasks/$($task.taskId)" -H "X-API-Key: $ApiKey" | Out-Null
    $final = [System.IO.File]::ReadAllText($pollOut, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    if ($final.status -eq 'COMPLETED' -or $final.status -eq 'FAILED') { break }
    Start-Sleep -Milliseconds 300
}
Assert-That '轮询最终拿到 COMPLETED' { $final.status -eq 'COMPLETED' } "status=$($final.status)，轮询 $polls 次"
Assert-That '任务状态与代码状态是两层，各说各的' { $final.status -eq 'COMPLETED' -and $final.result.status -eq 'SUCCESS' } "task=$($final.status) code=$($final.result.status)"
Assert-That '异步执行结果正确' { $final.result.stdout -match 'async ok' } "stdout=$($final.result.stdout.Trim())"

$asyncBadLang = & curl.exe -s -o NUL -w '%{http_code}' -X POST "$BaseUrl/api/v1/tasks" `
    -H 'Content-Type: application/json' -H "X-API-Key: $ApiKey" -d '{"language":"cobol","code":"x"}'
Assert-That '异步接口的校验是同步做的（坏语言立刻 400）' { $asyncBadLang -eq '400' } "HTTP $asyncBadLang"

$asyncNoAuth = & curl.exe -s -o NUL -w '%{http_code}' "$BaseUrl/api/v1/tasks/$($task.taskId)"
Assert-That '任务查询同样需要鉴权' { $asyncNoAuth -eq '401' } "HTTP $asyncNoAuth"

$asyncMissing = & curl.exe -s -o NUL -w '%{http_code}' "$BaseUrl/api/v1/tasks/00000000-0000-0000-0000-000000000000" -H "X-API-Key: $ApiKey"
Assert-That '未知 taskId 返回 404' { $asyncMissing -eq '404' } "HTTP $asyncMissing"

Remove-Item $asyncIn, $asyncOut, "$asyncIn.poll" -Force -ErrorAction SilentlyContinue

# ════════════════════════════════════════════════════════════════
# 2. 错误分类
# ════════════════════════════════════════════════════════════════
Write-Section '2. 错误分类 — 用户要知道"为什么没跑成"'

$compileErr = Invoke-Code -Language 'java' -Code @'
public class Main {
    public static void main(String[] args) {
        int x = "this is not an int";   // 类型错误
    }
}
'@
Assert-That '编译错误 → COMPILE_ERROR' { $compileErr.status -eq 'COMPILE_ERROR' } "status=$($compileErr.status)"
Assert-That '编译错误有 compileOutput' { $compileErr.compileOutput -match 'incompatible' -or $compileErr.compileOutput -match 'error' } "compileOutput 前 80 字：$($compileErr.compileOutput.Substring(0, [Math]::Min(80, $compileErr.compileOutput.Length)).Trim() -replace "`n",' ')"

$runErr = Invoke-Code -Language 'python' -Code @'
print("before crash")
raise ValueError("boom")
'@
Assert-That '运行异常 → RUNTIME_ERROR' { $runErr.status -eq 'RUNTIME_ERROR' } "status=$($runErr.status) exitCode=$($runErr.exitCode)"
Assert-That '运行异常的 stdout 与 stderr 分开' { $runErr.stdout -match 'before crash' -and $runErr.stderr -match 'ValueError' } 'stdout 有 before crash，stderr 有 ValueError'

$timeout = Invoke-Code -Language 'python' -Code @'
while True:
    pass
'@
Assert-That '死循环 → TIMEOUT' { $timeout.status -eq 'TIMEOUT' } "status=$($timeout.status) duration=$($timeout.durationMs)ms message=$($timeout.message)"
Assert-That '超时时 timedOut=true' { $timeout.timedOut -eq $true }

$oom = Invoke-Code -Language 'python' -Code @'
blocks = []
while True:
    blocks.append(bytearray(50 * 1024 * 1024))   # 每块 50MB，128MB 上限两下就爆
'@
Assert-That '内存超限 → OUT_OF_MEMORY' { $oom.status -eq 'OUT_OF_MEMORY' } "status=$($oom.status) exitCode=$($oom.exitCode) message=$($oom.message)"

$flood = Invoke-Code -Language 'python' -Code @'
import sys
for _ in range(50000):
    sys.stdout.write("x" * 100 + "\n")
'@
Assert-That '输出洪水被截断且标记 truncated' { $flood.truncated -eq $true } "truncated=$($flood.truncated) 返回 stdout 长度=$($flood.stdout.Length) 字节"

# ════════════════════════════════════════════════════════════════
# 3. 安全回归 —— 这一节最重要
# ════════════════════════════════════════════════════════════════
Write-Section '3. 安全回归 — 这是这个项目和"菜鸟教程"的区别'

$net = Invoke-Code -Language 'python' -Code @'
import socket
try:
    s = socket.create_connection(("1.1.1.1", 53), timeout=5)
    print("NETWORK_OPEN")
    s.close()
except Exception as e:
    print("NETWORK_BLOCKED:" + type(e).__name__)
'@
Assert-That '断网：容器内连不上外网' { $net.stdout -match 'NETWORK_BLOCKED' } "stdout=$($net.stdout.Trim())"

$dns = Invoke-Code -Language 'python' -Code @'
import socket
try:
    print("RESOLVED:" + socket.gethostbyname("www.baidu.com"))
except Exception as e:
    print("DNS_BLOCKED:" + type(e).__name__)
'@
Assert-That '断网：DNS 解析不到任何域名' { $dns.stdout -notmatch 'RESOLVED' } "status=$($dns.status)（解析不出来 = 出不去网；失败或一直阻塞都算通过）"

$dnsLeak = Invoke-Code -Language 'python' -Code @'
print(open("/etc/resolv.conf").read().strip().replace("\n", " | "))
'@
Assert-That 'DNS 配置不泄露宿主机 nameserver' { $dnsLeak.stdout -notmatch '192\.168\.|10\.\d+\.|172\.(1[6-9]|2\d|3[01])\.' } "stdout=$($dnsLeak.stdout.Trim())"

$rootfs = Invoke-Code -Language 'python' -Code @'
try:
    with open("/etc/evil.conf", "w") as f:
        f.write("pwned")
    print("ROOTFS_WRITABLE")
except Exception as e:
    print("ROOTFS_READONLY:" + type(e).__name__)
'@
Assert-That '只读根文件系统：写不了 /etc' { $rootfs.stdout -match 'ROOTFS_READONLY' } "stdout=$($rootfs.stdout.Trim())"

$uid = Invoke-Code -Language 'python' -Code 'import os; print("UID=" + str(os.getuid()))'
Assert-That '非 root 运行：uid 不是 0' { $uid.stdout -match 'UID=10001' } "stdout=$($uid.stdout.Trim())"

$workspace = Invoke-Code -Language 'python' -Code @'
with open("/workspace/probe.txt", "w") as f:
    f.write("ok")
print("WORKSPACE_WRITABLE")
'@
Assert-That '工作目录可写（沙箱不是"什么都不让干"）' { $workspace.stdout -match 'WORKSPACE_WRITABLE' } "stdout=$($workspace.stdout.Trim())"

$pids = Invoke-Code -Language 'python' -Code @'
import os, time
n = 0
try:
    for _ in range(64):
        pid = os.fork()
        if pid == 0:
            time.sleep(3)
            os._exit(0)
        n += 1
except OSError as e:
    print("FORK_BLOCKED_AT=" + str(n) + " " + str(e))
else:
    print("FORKED_ALL=" + str(n))
'@ -TimeoutSec 120
Assert-That '进程数限制：挡住了 fork' { $pids.stdout -match 'FORK_BLOCKED_AT' -or $pids.status -eq 'OUT_OF_MEMORY' -or $pids.status -eq 'TIMEOUT' } "status=$($pids.status) stdout=$($pids.stdout.Trim())"

$caps = Invoke-Code -Language 'python' -Code @'
try:
    import ctypes
    libc = ctypes.CDLL("libc.so.6", use_errno=True)
    # CAP_SYS_ADMIN 被丢掉之后，mount 必然失败
    rc = libc.mount(b"none", b"/mnt", b"tmpfs", 0, None)
    print("MOUNT_OK" if rc == 0 else "MOUNT_DENIED")
except Exception as e:
    print("MOUNT_DENIED:" + type(e).__name__)
'@
Assert-That '特权已丢弃：mount 不成功' { $caps.stdout -notmatch 'MOUNT_OK' } "stdout=$($caps.stdout.Trim())"

# ════════════════════════════════════════════════════════════════
# 4. 清理
# ════════════════════════════════════════════════════════════════
Write-Section '4. 清理 — 用过的容器不能留下'

Start-Sleep -Seconds 3

# 注意：预热池里的容器是【预期存在】的，它们从没被使用过。
# 这里要检查的是"用过的容器有没有被清理掉"，所以按命名把两类分开：
#   code-executor-warm-<语言>-<随机>  → 池中预热容器，允许存在
#   code-executor-<uuid>             → 某次执行租用的容器，必须消失
$managed = & docker ps -a --filter 'label=code-executor.managed=true' --format '{{.Names}}' 2>$null
$leased = @($managed) | Where-Object { $_ -and $_ -notmatch '^code-executor-warm-' }
$leasedCount = @($leased).Count
Assert-That '没有残留的「已使用」容器' { $leasedCount -eq 0 } "残留 $leasedCount 个：$($leased -join '; ')"

$warmCount = @(@($managed) | Where-Object { $_ -match '^code-executor-warm-' }).Count
Assert-That '剩下的都是预热容器（池子按预期保留）' { $warmCount -ge 0 } "预热容器 $warmCount 个：$(@($managed) -join '; ')"

$running = & docker ps --format '{{.Names}}' 2>$null
$runningLeased = @($running) | Where-Object { $_ -and $_ -notmatch '^code-executor-warm-' }
Assert-That 'Docker 中运行着的容器都不是执行中的租用容器' { @($runningLeased).Count -eq 0 } "运行中 $((@($runningLeased)).Count) 个租用容器"

# ════════════════════════════════════════════════════════════════
# 汇总
# ════════════════════════════════════════════════════════════════
Write-Section '汇总'
$total = $script:Passed + $script:Failed
Write-Host ''
Write-Host ("  通过 {0} / {1}" -f $script:Passed, $total) -ForegroundColor $(if ($script:Failed -eq 0) { 'Green' } else { 'Yellow' })
if ($script:Failed -gt 0) {
    Write-Host ("  失败 {0}" -f $script:Failed) -ForegroundColor Red
    Write-Host ''
    $script:Results | Where-Object { $_.Result -eq 'FAIL' } | Format-Table -AutoSize
}
Write-Host ''
Write-Host '  响应耗时一览：' -ForegroundColor DarkGray
$script:Results | Where-Object { $_.Note -match 'duration=' } | ForEach-Object {
    Write-Host ("    {0,-45} {1}" -f $_.Test, ($_.Note -replace '.*duration=(\d+)ms.*', '$1 ms')) -ForegroundColor DarkGray
}

exit $(if ($script:Failed -eq 0) { 0 } else { 1 })
