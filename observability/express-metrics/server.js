const express = require('express');
const client = require('prom-client');
const mysql = require('mysql2/promise');

const app = express();
const register = new client.Registry();
client.collectDefaultMetrics({ register });

const httpRequestDuration = new client.Histogram({
  name: 'express_metrics_demo_http_request_duration_seconds',
  help: 'Demo request duration in seconds',
  labelNames: ['route', 'status'],
  buckets: [0.01, 0.05, 0.1, 0.25, 0.5, 1, 2],
});
const httpRequestsTotal = new client.Counter({
  name: 'express_metrics_demo_http_requests_total',
  help: 'Total demo requests',
  labelNames: ['route', 'status'],
});
register.registerMetric(httpRequestDuration);
register.registerMetric(httpRequestsTotal);

const dbQueryDuration = new client.Histogram({
  name: 'express_metrics_demo_db_query_duration_seconds',
  help: 'Demo DB query duration in seconds',
  labelNames: ['status'],
  buckets: [0.001, 0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1],
});
const dbQueriesTotal = new client.Counter({
  name: 'express_metrics_demo_db_queries_total',
  help: 'Total demo DB queries',
  labelNames: ['status'],
});
register.registerMetric(dbQueryDuration);
register.registerMetric(dbQueriesTotal);

// mysql2's Pool has no public stats API — pool.pool.{_allConnections,_freeConnections}
// are undocumented internals, but this is a demo service so relying on them to get
// pool-size gauges (mirroring HikariCP's panels in the Spring dashboard) is fine here.
const dbPool = mysql.createPool({
  host: process.env.DB_HOST || 'host.docker.internal',
  port: Number(process.env.DB_PORT || 3306),
  user: process.env.DB_USER || 'ticket_user',
  password: process.env.DB_PASSWORD || 'ticket_pass',
  database: process.env.DB_NAME || 'ticket_dd',
  connectionLimit: 5,
});
new client.Gauge({
  name: 'express_metrics_demo_db_pool_connections',
  help: 'DB pool connections by state',
  labelNames: ['state'],
  registers: [register],
  collect() {
    const raw = dbPool.pool;
    this.set({ state: 'total' }, raw._allConnections.length);
    this.set({ state: 'free' }, raw._freeConnections.length);
    this.set({ state: 'queued' }, raw._connectionQueue.length);
  },
});

app.get('/metrics', async (req, res) => {
  res.set('Content-Type', register.contentType);
  res.end(await register.metrics());
});

app.get('/work', (req, res) => {
  const start = process.hrtime.bigint();
  const delayMs = Math.floor(Math.random() * 300) + 10;

  setTimeout(() => {
    const status = Math.random() < 0.1 ? 500 : 200;
    const durationSec = Number(process.hrtime.bigint() - start) / 1e9;

    httpRequestDuration.observe({ route: '/work', status }, durationSec);
    httpRequestsTotal.inc({ route: '/work', status });

    res.status(status).json({ status, durationMs: Math.round(durationSec * 1000) });
  }, delayMs);
});

app.get('/db', async (req, res) => {
  const start = process.hrtime.bigint();
  try {
    const [rows] = await dbPool.query('SELECT 1 + 1 AS result');
    const durationSec = Number(process.hrtime.bigint() - start) / 1e9;

    dbQueryDuration.observe({ status: '200' }, durationSec);
    dbQueriesTotal.inc({ status: '200' });
    httpRequestDuration.observe({ route: '/db', status: 200 }, durationSec);
    httpRequestsTotal.inc({ route: '/db', status: 200 });

    res.json({ status: 200, durationMs: Math.round(durationSec * 1000), result: rows[0].result });
  } catch (err) {
    const durationSec = Number(process.hrtime.bigint() - start) / 1e9;

    dbQueryDuration.observe({ status: '500' }, durationSec);
    dbQueriesTotal.inc({ status: '500' });
    httpRequestDuration.observe({ route: '/db', status: 500 }, durationSec);
    httpRequestsTotal.inc({ route: '/db', status: 500 });

    res.status(500).json({ status: 500, error: err.message });
  }
});

app.get('/', (req, res) => res.json({ service: 'express-metrics', ok: true }));

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`express-metrics listening on ${port}`));
