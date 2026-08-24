# Daily Health Report — chay luc 08:00 AM
# Setup Windows Task Scheduler:
#   Action: powershell.exe -File "E:\Learn-Backend\java\spring-ticket-ddd\scripts\daily-report.ps1"
#   Trigger: Daily 08:00

param(
    [string]$PrometheusUrl = "http://localhost:9090",
    [string]$ReportDir     = "$PSScriptRoot\..\reports"
)

$date   = Get-Date -Format "yyyy-MM-dd"
$report = [System.Text.StringBuilder]::new()

function Query($promql) {
    try {
        $enc = [Uri]::EscapeDataString($promql)
        $r   = Invoke-RestMethod "$PrometheusUrl/api/v1/query?query=$enc" -TimeoutSec 5
        if ($r.status -eq "success" -and $r.data.result.Count -gt 0) {
            return [double]$r.data.result[0].value[1]
        }
    } catch {}
    return $null
}

function Status($val, $warn, $crit, [switch]$LowerIsBetter) {
    if ($null -eq $val) { return "❓ N/A" }
    if ($LowerIsBetter) {
        if ($val -ge $crit) { return "🔴 CRITICAL" }
        if ($val -ge $warn) { return "🟡 WARNING" }
        return "🟢 HEALTHY"
    } else {
        if ($val -le $crit) { return "🔴 CRITICAL" }
        if ($val -le $warn) { return "🟡 WARNING" }
        return "🟢 HEALTHY"
    }
}

$null = $report.AppendLine("# Daily Health Report — $date")
$null = $report.AppendLine("")

# ── App ──────────────────────────────────────────────────────
$appUp      = Query('up{job="spring-ticket-ddd"}')
$errRate    = Query('sum(rate(http_server_requests_seconds_count{application="spring-ticket-ddd",status=~"5.."}[1h])) / sum(rate(http_server_requests_seconds_count{application="spring-ticket-ddd"}[1h]))')
$p95        = Query('histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="spring-ticket-ddd"}[1h])) by (le))')
$heapPct    = Query('jvm_memory_used_bytes{application="spring-ticket-ddd",area="heap"} / jvm_memory_max_bytes{application="spring-ticket-ddd",area="heap"}')

$null = $report.AppendLine("## App: spring-ticket-ddd")
$null = $report.AppendLine("| Metric | Value | Status |")
$null = $report.AppendLine("|--------|-------|--------|")
$null = $report.AppendLine("| Liveness | $(if($appUp -eq 1){'UP'}else{'DOWN'}) | $(if($appUp -eq 1){'🟢 HEALTHY'}else{'🔴 CRITICAL'}) |")
$null = $report.AppendLine("| HTTP Error Rate | $([math]::Round($errRate*100,2))% | $(Status $errRate 0.01 0.05 -LowerIsBetter) |")
$null = $report.AppendLine("| HTTP p95 Latency | $([math]::Round($p95*1000,0))ms | $(Status $p95 0.5 1.0 -LowerIsBetter) |")
$null = $report.AppendLine("| JVM Heap | $([math]::Round($heapPct*100,1))% | $(Status $heapPct 0.85 0.95 -LowerIsBetter) |")
$null = $report.AppendLine("")

# ── MySQL ─────────────────────────────────────────────────────
$mysqlUp   = Query('mysql_up')
$connPct   = Query('mysql_global_status_threads_connected / mysql_global_variables_max_connections')
$slowQps   = Query('rate(mysql_global_status_slow_queries[1h])')

$null = $report.AppendLine("## MySQL")
$null = $report.AppendLine("| Metric | Value | Status |")
$null = $report.AppendLine("|--------|-------|--------|")
$null = $report.AppendLine("| Liveness | $(if($mysqlUp -eq 1){'UP'}else{'DOWN'}) | $(if($mysqlUp -eq 1){'🟢 HEALTHY'}else{'🔴 CRITICAL'}) |")
$null = $report.AppendLine("| Connections | $([math]::Round($connPct*100,1))% | $(Status $connPct 0.7 0.9 -LowerIsBetter) |")
$null = $report.AppendLine("| Slow Queries/s | $([math]::Round($slowQps,2)) | $(Status $slowQps 0.5 2.0 -LowerIsBetter) |")
$null = $report.AppendLine("")

# ── Redis ─────────────────────────────────────────────────────
$redisUp   = Query('redis_up')
$memPct    = Query('redis_memory_used_bytes / redis_memory_max_bytes')

$null = $report.AppendLine("## Redis")
$null = $report.AppendLine("| Metric | Value | Status |")
$null = $report.AppendLine("|--------|-------|--------|")
$null = $report.AppendLine("| Liveness | $(if($redisUp -eq 1){'UP'}else{'DOWN'}) | $(if($redisUp -eq 1){'🟢 HEALTHY'}else{'🔴 CRITICAL'}) |")
$null = $report.AppendLine("| Memory | $([math]::Round($memPct*100,1))% | $(Status $memPct 0.75 0.90 -LowerIsBetter) |")
$null = $report.AppendLine("")

# ── Kafka ─────────────────────────────────────────────────────
$kafkaUp   = Query('up{job="kafka"}')
$maxLag    = Query('max(kafka_consumergroup_lag)')

$null = $report.AppendLine("## Kafka")
$null = $report.AppendLine("| Metric | Value | Status |")
$null = $report.AppendLine("|--------|-------|--------|")
$null = $report.AppendLine("| Liveness | $(if($kafkaUp -eq 1){'UP'}else{'DOWN'}) | $(if($kafkaUp -eq 1){'🟢 HEALTHY'}else{'🔴 CRITICAL'}) |")
$null = $report.AppendLine("| Max Consumer Lag | $maxLag | $(Status $maxLag 100 1000 -LowerIsBetter) |")
$null = $report.AppendLine("")

$null = $report.AppendLine("---")
$null = $report.AppendLine("Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")

# Luu file
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
$path = "$ReportDir\report-$date.md"
$report.ToString() | Out-File -FilePath $path -Encoding utf8
Write-Host "Report saved: $path"
Write-Host ""
Write-Host $report.ToString()
