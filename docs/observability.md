# Observability — URL Shortener Service

How the service reports metrics, traces, and SLOs. Companion: `docs/slos.md`.

## Metrics

Spring Boot Actuator exposes Micrometer metrics at `/actuator/prometheus`
(your Prometheus scrapes `deploy/monitoring/prometheus.yml`).

Business metrics recorded behind `MetricsPort` (`core/` stays framework-free):

| Metric | Source | Percentiles |
|--------|--------|-------------|
| `urls.shortened.total` | shorten path | — |
| `cache.hits.total` / `cache.misses.total` | redirect path | — |
| `bloomfilter.rejections.total` | bloom filter | — |
| `id.generation.duration` | `MetricsPort.recordIdGeneration` (Base62 + vanity generation) | p50/p95/p99 |
| `url.retrieval.duration` | `MetricsPort.recordUrlRetrieval` (cache + DB lookup, single hot-path hit) | p50/p95/p99 |
| `http.server.requests` | Spring Boot HTTP layer (auto) | histogram |

The redirect hot path (Rule 5) still does a **single** DB hit; the retrieval
timer wraps that lookup without adding any blocking I/O.

## Tracing

- **Instrumentation:** Spring Boot auto-instrumentation via
  `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` +
  `opentelemetry-sdk-extension-autoconfigure`. HTTP spans are created by the
  framework (`http.server.requests` observation); **no tracing code lives in
  `core/`** (architectural boundary, AGENTS Rule 1).
- **Exporter:** OTLP/HTTP at `management.otlp.tracing.endpoint`
  (`${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}`).
- **Sampling:** head sampling `management.tracing.sampling.probability=0.1`
  (10%); the OpenTelemetry Collector
  (`deploy/otel/otel-collector-config.yml`) applies **tail sampling** that
  keeps every ERROR trace regardless of head rate.
- **Correlation:** `logback-spring.xml` logs `%X{traceId}` `%X{spanId}`;
  Spring populates these MDC keys from the active trace.
- **Fail-open:** span export is asynchronous — an unreachable collector never
  blocks or fails requests (proven by `TracingFailOpenIT`).

## SLOs

Defined in `docs/slos.md`; recording rules and burn-rate alerts in
`deploy/monitoring/recording-rules.yml` and `deploy/monitoring/alerts.yml`.
Grafana SLO dashboard: `dashboards/url-shortener-slo.json`.

## Load tests

k6 scripts in `load-tests/` (`shorten.js`, `redirect.js`, `mixed.js`), manual
dispatch via `.github/workflows/load-test.yml`. Baselines tracked in
`docs/load-test-baseline.md`.

## Run scripts

```bash
# Run k6 load tests against a locally started app (relaxed per-IP budgets):
RATE_LIMITER_LIMIT=1000000 RATE_LIMITER_REDIRECT_LIMIT=1000000 mvn spring-boot:run &
k6 run load-tests/mixed.js
```

## Roadmap

- Expose the analytics Redis-Stream depth as a `analytics.queue.depth` gauge
  (the Grafana overview panel is present but empty until then).
- Publish the first real k6 baseline in `docs/load-test-baseline.md` and the
  initial p50/p95/p99 numbers from a production-like run.