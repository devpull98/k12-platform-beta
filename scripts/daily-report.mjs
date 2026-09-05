#!/usr/bin/env node
// Daily Health Report — query Prometheus, sinh Markdown vao reports/
// Chay thu: node scripts/daily-report.mjs
// Schedule: Windows Task Scheduler - Daily 08:00
//   Action: node "E:\Learn-Backend\java\spring-ticket-ddd\scripts\daily-report.mjs"

import { writeFileSync, mkdirSync } from "fs";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

const PROMETHEUS_URL = process.env.PROMETHEUS_URL ?? "http://localhost:9090";
const REPORT_DIR = process.env.REPORT_DIR ?? join(dirname(fileURLToPath(import.meta.url)), "..", "reports");

async function query(promql) {
  try {
    const url = `${PROMETHEUS_URL}/api/v1/query?query=${encodeURIComponent(promql)}`;
    const res = await fetch(url, { signal: AbortSignal.timeout(5000) });
    const json = await res.json();
    if (json.status === "success" && json.data.result.length > 0) {
      return parseFloat(json.data.result[0].value[1]);
    }
  } catch { /* unreachable or timeout */ }
  return null;
}

function status(val, warn, crit, lowerIsBetter = true) {
  if (val === null) return "❓ N/A";
  if (lowerIsBetter) {
    if (val >= crit) return "🔴 CRITICAL";
    if (val >= warn) return "🟡 WARNING";
  } else {
    if (val <= crit) return "🔴 CRITICAL";
    if (val <= warn) return "🟡 WARNING";
  }
  return "🟢 HEALTHY";
}

function upStatus(val) {
  return val === 1 ? "🟢 HEALTHY" : "🔴 CRITICAL";
}

function pct(val) {
  return val !== null ? `${(val * 100).toFixed(1)}%` : "N/A";
}

function ms(val) {
  return val !== null ? `${Math.round(val * 1000)}ms` : "N/A";
}

function rnd(val, d = 2) {
  return val !== null ? val.toFixed(d) : "N/A";
}

const date = new Date().toISOString().slice(0, 10);

const [appUp, errRate, p95, heapPct,
       mysqlUp, connPct, slowQps,
       redisUp, memPct,
       kafkaUp, maxLag] = await Promise.all([
  query('up{job="spring-ticket-ddd"}'),
  query('sum(rate(http_server_requests_seconds_count{application="spring-ticket-ddd",status=~"5.."}[1h])) / sum(rate(http_server_requests_seconds_count{application="spring-ticket-ddd"}[1h]))'),
  query('histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="spring-ticket-ddd"}[1h])) by (le))'),
  query('sum(jvm_memory_used_bytes{application="spring-ticket-ddd",area="heap"}) / sum(jvm_memory_max_bytes{application="spring-ticket-ddd",area="heap"})'),
  query('mysql_up'),
  query('mysql_global_status_threads_connected / mysql_global_variables_max_connections'),
  query('rate(mysql_global_status_slow_queries[1h])'),
  query('redis_up'),
  query('redis_memory_used_bytes / redis_memory_max_bytes'),
  query('up{job="kafka"}'),
  query('max(kafka_consumergroup_lag)'),
]);

const lines = [
  `# Daily Health Report — ${date}`,
  "",
  "## App: spring-ticket-ddd",
  "| Metric | Value | Status |",
  "|--------|-------|--------|",
  `| Liveness        | ${appUp === 1 ? "UP" : "DOWN"} | ${upStatus(appUp)} |`,
  `| HTTP Error Rate | ${pct(errRate)}              | ${status(errRate, 0.01, 0.05)} |`,
  `| HTTP p95        | ${ms(p95)}                   | ${status(p95, 0.5, 1.0)} |`,
  `| JVM Heap        | ${pct(heapPct)}              | ${status(heapPct, 0.85, 0.95)} |`,
  "",
  "## MySQL",
  "| Metric | Value | Status |",
  "|--------|-------|--------|",
  `| Liveness        | ${mysqlUp === 1 ? "UP" : "DOWN"} | ${upStatus(mysqlUp)} |`,
  `| Connections     | ${pct(connPct)}              | ${status(connPct, 0.70, 0.90)} |`,
  `| Slow Queries/s  | ${rnd(slowQps)}              | ${status(slowQps, 0.5, 2.0)} |`,
  "",
  "## Redis",
  "| Metric | Value | Status |",
  "|--------|-------|--------|",
  `| Liveness        | ${redisUp === 1 ? "UP" : "DOWN"} | ${upStatus(redisUp)} |`,
  `| Memory          | ${pct(memPct)}               | ${status(memPct, 0.75, 0.90)} |`,
  "",
  "## Kafka",
  "| Metric | Value | Status |",
  "|--------|-------|--------|",
  `| Liveness        | ${kafkaUp === 1 ? "UP" : "DOWN"} | ${upStatus(kafkaUp)} |`,
  `| Max Consumer Lag| ${maxLag ?? "N/A"}           | ${status(maxLag, 100, 1000)} |`,
  "",
  "---",
  `Generated: ${new Date().toISOString()}`,
].join("\n");

mkdirSync(REPORT_DIR, { recursive: true });
const outPath = join(REPORT_DIR, `report-${date}.md`);
writeFileSync(outPath, lines, "utf8");
console.log(`Report saved: ${outPath}\n`);
console.log(lines);
