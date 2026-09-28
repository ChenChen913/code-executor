<#
.SYNOPSIS
    code-executor 演示脚本 —— 六分钟看懂这个项目在做什么。

.DESCRIPTION
    依次跑 7 个例子，每个都说明"它展示了什么"：
      1~2  基本用法（解释型 / 编译型）
      3    ★ 关键：菜鸟教程能做到、这里做不到的事
      4~7  四种失败场景，以及它们为什么必须区分开

.EXAMPLE
    powershell -File scripts/demo.ps1
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ApiKey = $(if ($env:EXECUTOR_AUTH_KEYS_0_VALUE) { $env:EXECUTOR_AUTH_KEYS_0_VALUE } else { 'dev-local-key-0123456789abcdef' })
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Send-Code {
    param([string]$Language, [string]$Code, [int]$TimeoutSec = 90)
    $payload = @{ language = $Language; code = $Code } | ConvertTo-Json -Compress
    $inFile = [System.IO.Path]::GetTempFileName()
    $outFile = "$inFile.out"
    try {
        [System.IO.File]::WriteAllText($inFile, $payload, [System.Text.UTF8Encoding]::new($false))
        & curl.exe -s --max-time $TimeoutSec -X POST "$BaseUrl/api/v1/executions" `
            -H 'Content-Type: application/json' -H "X-API-Key: $ApiKey" `
            --data-binary "@$inFile" -o $outFile | Out-Null
        return [System.IO.File]::ReadAllText($outFile, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    } finally {
        Remove-Item $inFile, $outFile -Force -ErrorAction SilentlyContinue
    }
}

function Show-Case {
    param([string]$Title, [string]$Why, [string]$Language, [string]$Code, [string]$Expect)
    Write-Host ''
    Write-Host ('━' * 76) -ForegroundColor DarkGray
    Write-Host "  $Title" -ForegroundColor Cyan
    Write-Host "  $Why" -ForegroundColor DarkGray
    Write-Host ('━' * 76) -ForegroundColor DarkGray

    $r = Send-Code -Language $Language -Code $Code

    $color = switch ($r.status) {
        'SUCCESS' { 'Green' }
        'COMPILE_ERROR' { 'Yellow' }
        'RUNTIME_ERROR' { 'Yellow' }
        'TIMEOUT' { 'Magenta' }
        'OUT_OF_MEMORY' { 'Magenta' }
        default { 'Red' }
    }
    Write-Host ("  status   : {0}   ({1} ms)" -f $r.status, $r.durationMs) -ForegroundColor $color
    if ($null -ne $r.exitCode) { Write-Host "  exitCode : $($r.exitCode)" -ForegroundColor DarkGray }
    if ($r.truncated) { Write-Host "  truncated: true  ← 输出被截断了，你看到的不是全部" -ForegroundColor Yellow }
    Write-Host "  message  : $($r.message)" -ForegroundColor Gray

    if ($r.compileOutput) {
        Write-Host '  ── 编译输出 ──' -ForegroundColor DarkGray
        ($r.compileOutput.Trim() -split "`n" | Select-Object -First 4) | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkYellow }
    }
    if ($r.stdout) {
        Write-Host '  ── stdout ──' -ForegroundColor DarkGray
        ($r.stdout.TrimEnd() -split "`n" | Select-Object -First 24) | ForEach-Object { Write-Host "    $_" -ForegroundColor White }
    }
    if ($r.stderr) {
        Write-Host '  ── stderr ──' -ForegroundColor DarkGray
        ($r.stderr.TrimEnd() -split "`n" | Select-Object -First 4) | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkRed }
    }
    if ($Expect) { Write-Host "  ▸ $Expect" -ForegroundColor DarkCyan }
}

Write-Host ''
Write-Host '  code-executor · 演示' -ForegroundColor White
Write-Host "  目标: $BaseUrl" -ForegroundColor DarkGray

try { $null = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 5 }
catch { Write-Host "`n  执行器没在运行，先执行: mvn spring-boot:run`n" -ForegroundColor Red; exit 1 }

Show-Case -Title '① 基本用法：跑一段 Python' `
    -Why '解释型语言，没有编译步骤' `
    -Language 'python' -Code @'
nums = [1, 2, 3, 4, 5]
print("求和 =", sum(nums))
print("平方 =", [n * n for n in nums])
'@ -Expect '一次 POST 就拿到结果，不用装 Python 环境'

Show-Case -Title '② 编译型语言：Java 的两阶段执行' `
    -Why '先 javac 编译，成功后才 java 运行；编译失败就不会往下跑' `
    -Language 'java' -Code @'
public class Main {
    public static void main(String[] args) {
        int sum = 0;
        for (int i = 1; i <= 100; i++) sum += i;
        System.out.println("1 到 100 的和 = " + sum);
    }
}
'@ -Expect '注意类名必须是 Main，和容器里的文件名一致'

Show-Case -Title '③ ★ 关键差异：菜鸟教程能做到的事，这里做不到' `
    -Why '原文实测菜鸟教程可以把容器里的文件列出来、读出来 —— 这里会怎样？' `
    -Language 'python' -Code @'
import os, socket

print("--- 尝试读取目录 ---")
print(os.popen("ls -a /usr/local 2>&1").read()[:120] or "(空)")

print("--- 尝试写系统文件 ---")
try:
    open("/etc/pwned", "w").write("x")
    print("!!! 写入成功，沙箱失效 !!!")
except Exception as e:
    print("写入被拒:", e)

print("--- 尝试连外网 ---")
try:
    socket.create_connection(("1.1.1.1", 53), timeout=3)
    print("!!! 网络是通的，沙箱失效 !!!")
except Exception as e:
    print("连不出去:", type(e).__name__)

print("--- 当前身份 ---")
print("uid =", os.getuid())
'@ -Expect '文件仍可读（只读根文件系统挡不住读），但：写不了系统文件、连不上网、不是 root'

Show-Case -Title '④ 编译错误：和运行错误分开报' `
    -Why '用户最想知道的是"没编译过"还是"跑起来挂了"，这两种该看的地方完全不同' `
    -Language 'java' -Code @'
public class Main {
    public static void main(String[] args) {
        int x = "这不是数字";
    }
}
'@ -Expect 'status=COMPILE_ERROR，原因在 compileOutput 里，stdout/stderr 是空的'

Show-Case -Title '⑤ 运行出错：stdout 和 stderr 分开' `
    -Why '崩溃前的输出也要拿得到，否则没法调试' `
    -Language 'python' -Code @'
print("第一步：开始")
print("第二步：准备")
raise ValueError("第三步炸了")
'@ -Expect 'stdout 有前两步，stderr 有异常栈'

Show-Case -Title '⑥ 死循环：掐秒表' `
    -Why '原文安全需求第二条：不能让人写个"睡眠一万年"把线程占死' `
    -Language 'python' -Code @'
print("开始死循环……")
while True:
    pass
'@ -Expect '5 秒后被强制终止，status=TIMEOUT，容器被 KILL'

Show-Case -Title '⑦ 内存炸弹：容器被内核杀掉' `
    -Why '原文安全需求第一条：不能让人用死循环创建对象把内存吃光' `
    -Language 'python' -Code @'
blocks = []
while True:
    blocks.append(bytearray(50 * 1024 * 1024))
'@ -Expect '超过 128MB 上限后被 OOM Killer 干掉，exitCode=137，status=OUT_OF_MEMORY'

Write-Host ''
Write-Host ('━' * 76) -ForegroundColor DarkGray
Write-Host '  演示结束' -ForegroundColor Green
Write-Host ''
Write-Host '  想接着玩：' -ForegroundColor White
Write-Host '    · 看支持哪些语言     curl http://localhost:8080/api/v1/languages' -ForegroundColor Gray
Write-Host '    · 跑完整测试         powershell -File scripts/e2e-test.ps1' -ForegroundColor Gray
Write-Host '    · 不装 Docker 看流程 mvn spring-boot:run -Dspring-boot.run.arguments=--executor.dry-run=true' -ForegroundColor Gray
Write-Host '    · 加一门语言         改 src/main/resources/languages.yml + Dockerfile' -ForegroundColor Gray
Write-Host ''
