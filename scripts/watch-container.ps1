<#
.SYNOPSIS
    现场演示：看着一个加固过的容器被创建、运行、销毁。

.DESCRIPTION
    提交一段要跑几秒的代码，同时在后台轮询 docker ps，
    抓住那个容器，把它真实的 HostConfig 打出来——
    证明那些加固不是写在配置文件里，而是真的落到了容器上。

.EXAMPLE
    powershell -File scripts/watch-container.ps1 -ApiKey 'your-key'
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ApiKey  = $(if ($env:EXECUTOR_AUTH_KEYS_0_VALUE) { $env:EXECUTOR_AUTH_KEYS_0_VALUE } else { 'demo-key-0123456789abcdef' })
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Line($c = '─') { Write-Host ($c * 76) -ForegroundColor DarkGray }

Write-Host ''
Write-Host '  现场演示：一个容器的完整生命周期' -ForegroundColor White
Line '═'

# ── 提交任务（后台，不阻塞）────────────────────────────────
$code = @'
public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println("   [容器内] 我起来了，准备跑 6 秒...");
        for (int i = 1; i <= 3; i++) {
            Thread.sleep(2000);
            System.out.println("   [容器内] 已经跑了 " + (i * 2) + " 秒");
        }
        System.out.println("   [容器内] 跑完了");
    }
}
'@
$payload = @{ language = 'java'; code = $code } | ConvertTo-Json -Compress
$inFile  = [System.IO.Path]::GetTempFileName()
$outFile = "$inFile.out"
[System.IO.File]::WriteAllText($inFile, $payload, [System.Text.UTF8Encoding]::new($false))

Write-Host ''
Write-Host '  ▸ 提交代码...' -ForegroundColor Cyan
$job = Start-Job -ScriptBlock {
    param($url, $key, $file, $out)
    & curl.exe -s --max-time 90 -X POST "$url/api/v1/executions" `
        -H 'Content-Type: application/json' -H "X-API-Key: $key" `
        --data-binary "@$file" -o $out | Out-Null
} -ArgumentList $BaseUrl, $ApiKey, $inFile, $outFile

# ── 盯着 docker ps ─────────────────────────────────────────
Write-Host '  ▸ 盯着 Docker，等容器出现...' -ForegroundColor Cyan
Write-Host ''
$seen = $null
$captured = $false
for ($i = 0; $i -lt 120; $i++) {
    $ids = & docker ps --filter 'label=code-executor.managed=true' --format '{{.ID}}' 2>$null
    if ($ids) {
        $seen = @($ids)[0]
        if (-not $captured) {
            $captured = $true
            Write-Host "  ✅ 容器出现了！ ID = $($seen.Substring(0,12))" -ForegroundColor Green
            Write-Host ''
            Write-Host '  ── 它真实的 HostConfig（docker inspect 读出来的，不是我说的）──' -ForegroundColor Yellow
            $hc = (& docker inspect $seen --format '{{json .HostConfig}}' 2>$null) | ConvertFrom-Json
            $containerUser = (& docker inspect $seen --format '{{.Config.User}}' 2>$null)
            $name = (& docker inspect $seen --format '{{.Name}}' 2>$null)
            $rows = [ordered]@{
                'User（非 root）'      = $containerUser
                'NetworkMode（断网）'  = $hc.NetworkMode
                'ReadonlyRootfs'       = $hc.ReadonlyRootfs
                'CapDrop（丢特权）'    = ($hc.CapDrop -join ',')
                'SecurityOpt'          = ($hc.SecurityOpt -join ',')
                'Memory（字节）'       = $hc.Memory
                'MemorySwap'           = $hc.MemorySwap
                'NanoCpus'             = $hc.NanoCpus
                'PidsLimit'            = $hc.PidsLimit
                'Privileged'           = $hc.Privileged
                'AutoRemove'           = $hc.AutoRemove
                'Dns'                  = ($hc.Dns -join ',')
            }
            foreach ($k in $rows.Keys) {
                Write-Host ("    {0,-22} {1}" -f $k, $rows[$k]) -ForegroundColor Gray
            }
            if ($hc.Tmpfs) {
                Write-Host '    Tmpfs                  ' -NoNewline -ForegroundColor Gray
                Write-Host (($hc.Tmpfs.PSObject.Properties | ForEach-Object { "$($_.Name) [$($_.Value)]" }) -join '  ') -ForegroundColor Gray
            }
            Write-Host ''
            Write-Host "  ▸ 继续观察 $name ..." -ForegroundColor Cyan
        } else {
            Write-Host "    ...仍在运行（$([math]::Round($i*0.5,1)) 秒）" -ForegroundColor DarkGray
        }
    } elseif ($seen) {
        Write-Host ''
        Write-Host '  ✅ 容器已消失 —— 被清理掉了' -ForegroundColor Green
        break
    }
    Start-Sleep -Milliseconds 500
}

# ── 取结果 ────────────────────────────────────────────────
Wait-Job $job -Timeout 60 | Out-Null
Receive-Job $job | Out-Null
Remove-Job $job -Force
Line
Write-Host '  执行结果' -ForegroundColor White
Line
if (Test-Path $outFile) {
    $r = [System.IO.File]::ReadAllText($outFile, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    Write-Host ("  status   : {0}   ({1} ms)" -f $r.status, $r.durationMs) -ForegroundColor Green
    Write-Host ("  exitCode : {0}" -f $r.exitCode) -ForegroundColor Gray
    Write-Host '  ── stdout（容器内打印的，带时间顺序）──' -ForegroundColor DarkGray
    ($r.stdout.TrimEnd() -split "`n") | ForEach-Object { Write-Host "  $_" -ForegroundColor White }
}
Remove-Item $inFile, $outFile -Force -ErrorAction SilentlyContinue

Line
$left = & docker ps -a --filter 'label=code-executor.managed=true' --format '{{.ID}}' 2>$null
$n = if ($left) { @($left).Count } else { 0 }
Write-Host "  残留容器: $n 个" -ForegroundColor $(if ($n -eq 0) { 'Green' } else { 'Red' })
Write-Host ''
