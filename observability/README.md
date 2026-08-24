# observability/

Portable monitoring stack: Prometheus · Loki · Tempo · Promtail · Grafana.
Copy this folder into any Spring Boot project and it works out of the box.

## Services & ports (defaults)

| Service    | Port  | Purpose                      |
|------------|-------|------------------------------|
| Prometheus | 9090  | Metrics scrape & storage     |
| Loki       | 3100  | Log storage (7-day retention)|
| Tempo      | 4317  | OTLP gRPC trace ingestion    |
| Tempo      | 4318  | OTLP HTTP trace ingestion    |
| Promtail   | —     | Ships `logs/app.log` → Loki  |
| Grafana    | 3000  | Dashboards (admin/admin)     |

## Start

```bash
cd observability
docker compose up -d
```

Open Grafana at http://localhost:3000 (admin / admin).

## Reuse in another project

1. Copy this folder into the new project root.
2. Edit `.env` — at minimum set `APP_JOB_NAME` to match `spring.application.name`.
3. Make sure the app writes logs to `logs/app.log` (`logging.file.name: logs/app.log`).
4. Make sure the app exports OTLP traces to `http://localhost:4318/v1/traces`.
5. `docker compose up -d`.

## App configuration required

```yaml
# application.yml
management:
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces
  tracing:
    sampling:
      probability: 1.0
  metrics:
    tags:
      application: ${spring.application.name}

logging:
  file:
    name: logs/app.log
```

## Log ↔ Trace correlation

Promtail extracts `traceId` from JSON log lines. Loki datasource in Grafana is
configured with a derived field that turns every `traceId` into a Tempo link,
so you can jump from a log line straight to the corresponding trace.
