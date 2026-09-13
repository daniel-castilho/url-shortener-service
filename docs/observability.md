# Observability — URL Shortener Service

How the service reports metrics, traces, and SLOs. Companion: `docs/slos.md`.

## Metrics

Spring Boot Actuator exposes Micrometer metrics at `/actuator/prometheus`
(your Prometheus scrapes `deploy/monitoring/prometheus.yml`).

Business metrics are recorded behind `MetricsPort` (`core/` stays framework-free) and
wired to Micrometer in `infra/observability/MicrometerMetricsAdapter`. The set of
registered meters is **frozen** (Epic 3 story 3.2): `scripts/check-metrics-frozen.sh`
(bound at `verify` + CI) fails if a new `Counter/Timer/Gauge` is registered outside the
reviewed contract below, or if a frozen series disappears. Prometheus exposes the
matching `*_total` / `*_seconds` series.

| Metric | Source | Percentiles |
|--------|--------|-------------|
| `urls.shortened.total` | `POST /api/v1/urls` (shorten path) | — |
| `shorten.latency` | shorten path, end-to-end | p50/p95/p99 |
| `redirects.total` | `GET /{id}` (redirect path) | — |
| `redirect.latency` | redirect path, end-to-end | p50/p95/p99 |
| `url.retrieval.duration` | `MetricsPort.recordUrlRetrieval` (cache + DB lookup, single hot-path hit) | p50/p95/p99 |
| `id.generation.duration` | `MetricsPort.recordIdGeneration` (Base62 + vanity generation) | p50/p95/p99 |
| `cache.hits.total` / `cache.misses.total` | redirect path cache-aside | — |
| `bloomfilter.rejections.total` | bloom filter | — |
| `urls.expired.total` | expired short URL resolution | — |
| `security.ssrf.blocked.total` | SSRF protection (`DefaultUrlValidator.validate`) | — |
| `schema.migrations.applied.total` / `schema.migrations.failed.total` | `MongoSchemaMigrator` | — |
| `analytics.events.enqueued.total` / `.persisted.total` / `.dropped.total` / `.failed.total` | analytics click-event pipeline | — |
| `analytics.queue.depth` | Redis-Stream pending/length gauge (`RedisClickEventQueue`) | — |
| `analytics.rollup.days.total` / `.errors.total` / `.groups.upserted.total` | analytics rollup job | — |
| `analytics.retention.runs.total` / `.purged.total` / `.errors.total` | `ClickEventsRetentionPurge` | — |
| `http.server.requests` | Spring Boot HTTP layer (auto) | histogram |

The redirect hot path (Rule 5) still does a **single** DB hit; the retrieval
timer wraps that lookup without adding any blocking I/O.

> **Consolidation note (Epic 3, story 3.2):** the legacy
> `infra/observability/MetricsService` (a second metrics bean wired directly into
> `UrlController`, registering `urls.shortened.total`, `redirects.total`,
> `cache.hits.total`, `cache.misses.total`, `bloomfilter.rejections.total`,
> `shorten.latency`, `redirect.latency`) was folded into `MicrometerMetricsAdapter`
> behind `MetricsPort`. Meter names, tags and descriptions are preserved **byte-for-byte**
> (same series in Prometheus), so dashboards and alerts are unaffected.

## Logging & request correlation

Only the standard SLF4J/Logback stack is used (`%X{MDC}`), no tracing code in `core/`.

- Every request is tagged with a `request_id` MDC key (plain console/file patterns and
  the JSON encoder include it).
- `infra/security/RequestCorrelationFilter` (highest precedence, runs before Spring
  Security): accepts the inbound `X-Request-Id` only when it is a safe ASCII token
  (`^[A-Za-z0-9._-]{1,64}$`), otherwise replaces it with a generated UUID. The resolved
  id is echoed on the response header and present on every log line of the request.
- OpenTelemetry: `%X{traceId}` / `%X{spanId}` populate from the active trace when tracing
  is on (see below).

Example (plain console): `2026-09-11 04:00:00 ... [a1b2c3d4-e5f6-...] WARN ... - URL not found`.

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

## Diagnosis (quick triage)

`scripts/debug-health.sh [base_url]` probes `/actuator/health/liveness`+
`/actuator/health/readiness` and, when the scrape is reachable,
`/actuator/prometheus` (error ratio, cache hits/misses, analytics queue depth), then
prints the recommended action. It is a triage aid, not a gate.

| Symptom | Check | Action |
|---|---|---|
| Readiness DOWN / Mongo or Redis unreachable | `/actuator/health/readiness` body; `docker compose ps`; service logs | `docker-compose up -d`, verify network/replicaset, re-check readiness (UP requires both Mongo **and** Redis) |
| Liveness DOWN / app unresponsive | `systemctl status url-shortener`; tail `logging/application.log` | restart the unit; on recurrence run `scripts/verify-graceful-shutdown.sh`, inspect shutdown logs (crash/OOM/blocked migration) |
| Sustained 5xx / fast-burn alert | `error5xx` line + `SLOAvailabilityFastBurn` status in Alertmanager | `docs/slos.md` §Response runbook — **Fast burn** (critical) row |
| `analytics.queue.depth` growing (>100s of events) | queue gauge + `click_events` insert stats | consumer (`ClickBatchWorker`) backed up/failing: check its logs; queue is bounded (`XADD MAXLEN`) and fail-open — watch for drops |
| Cache hit ratio collapsing | `hits/(hits+misses)` from the cache counters | verify Redis reachable + L1/bloom reset; a single DB hit per redirect is by design (Rule 5), ratios must stay high |
| Redirect latency p99 > 200ms | `url_retrieval_duration_seconds` + `redirect_latency_seconds` p99 panels | MongoDB/Redis latency + network; correlate with the k6 baseline (`docs/load-test-baseline.md`) |

## Run scripts

```bash
# Run k6 load tests against a locally started app (relaxed per-IP budgets):
RATE_LIMITER_LIMIT=1000000 RATE_LIMITER_REDIRECT_LIMIT=1000000 ./mvnw spring-boot:run &
k6 run load-tests/mixed.js

# Quick health triage (Epic 3 story 3.5):
bash scripts/debug-health.sh http://localhost:8080

# Validate alert rules and configs (matches the CI observability job):
promtool check rules deploy/monitoring/alerts.yml deploy/monitoring/recording-rules.yml
promtool test rules deploy/monitoring/rules_tests.yml
promtool check config deploy/monitoring/prometheus.yml
amtool check-config deploy/monitoring/alertmanager.yml
```

## Roadmap (Epic 3, follow-ups)

- Operator identity for the actuator tier (AGENTS.md debt 26): decide a scoped operator
  identity (BasicAuth user, JWT role, or IP allowlist) so `/actuator/health` (components),
  `/actuator/metrics` and `/actuator/prometheus` become operator-readable over HTTP; wire or
  drop `security.actuator.health-detail-enabled`.