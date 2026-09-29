<#
.SYNOPSIS
    测量执行延迟分布。用来验证容器预热池到底有没有用。

.DESCRIPTION
    连续提交 N 次执行，统计 durationMs 的最小值/中位数/平均值/最大值。
    注意用的是服务端返回的 durationMs（从创建容器到清理完成的真实耗时），
    不是客户端观测到的网络往返时间。

.EXAMPLE
    powershell -File scripts/benchmark.ps1 -ApiKey 'xxx' -Count 12
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ApiKey = $(if ($env:EXECUTOR_AUTH_KEYS_0_VALUE) { $env:EXECUTOR_AUTH_KEYS_0_VALUE } else { 'demo-key-0123456789abcdef' }),
    [string]$Language = 'python',
    [string]$Code = 'print(1)',
    [int]$Count = 12
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$payload = @{ language = $Language; code = $Code } | ConvertTo-Json -Compress
$inFile = [System.IO.Path]::GetTempFileName()
$outFile = "$inFile.out"
[System.IO.File]::WriteAllText($inFile, $payload, [System.Text.UTF8Encoding]::new($false))

$durations = @()
try {
    for ($i = 1; $i -le $Count; $i++) {
        & curl.exe -s --max-time 120 -X POST "$BaseUrl/api/v1/executions" `
            -H 'Content-Type: application/json' -H "X-API-Key: $ApiKey" `
            --data-binary "@$inFile" -o $outFile | Out-Null
        $r = [System.IO.File]::ReadAllText($outFile, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
        if ($r.durationMs) { $durations += [int]$r.durationMs }
    }
} finally {
    Remove-Item $inFile, $outFile -Force -ErrorAction SilentlyContinue
}

if ($durations.Count -eq 0) { Write-Host '  没有拿到任何结果'; exit 1 }

$sorted = $durations | Sort-Object
$median = if ($sorted.Count % 2 -eq 1) {
    $sorted[[int]($sorted.Count / 2)]
} else {
    [int](($sorted[$sorted.Count / 2 - 1] + $sorted[$sorted.Count / 2]) / 2)
}
$avg = [int](($durations | Measure-Object -Average).Average)

"  {0,-8} n={1,-3} min={2,-6} 中位数={3,-6} 平均={4,-6} max={5}" -f `
    $Language, $durations.Count, $sorted[0], $median, $avg, $sorted[-1]

# 把原始数据也打出来，便于核对
"           原始: $($durations -join ', ')"
