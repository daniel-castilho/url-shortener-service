# SLOs — URL Shortener Service

Service-level objectives for the URL shortener, with SLI definitions, targets,
windows, rationale and response runbooks. These back `deploy/monitoring/recording-rules.yml`
and `deploy/monitoring/alerts.yml` and the SLO Grafana dashboard.

## Targets

| SLI | SLO target | Window | Error budget | Burn-rate alerts |
|-----|-----------|--------|--------------|------------------|
| Availability (non-5xx requests) | ≥ 99.9% | 30d rolling | 0.1% of requests | Fast 14.4x (critical) / Slow 6x (warning) |
| Latency (p99) | < 200ms | 30d rolling | 0.1% of requests over budget? — see below | Threshold alerts from p99 panel |
| Error rate (5xx) | < 0.1% | 30d rolling | equivalent to availability | covered by availability burn alerts |

> **Latency note:** the p99 < 200 ms target is enforced by the k6 load harness
> (thresholds `p95 < 200ms`, error rate `< 0.1%`) and tracked on the latency
> dashboard. The availability burn-rate alerts are the primary 24/7 guard.

## Frozen metrics contract

The application's business meters are a **frozen contract** (Epic 3 story 3.2).
`scripts/check-metrics-frozen.sh` (bound at `verify` + CI, with `--self-test`) fails if a
new `Counter`/`Timer`/`Gauge` is registered in `src/main/java` outside this table, or a
frozen series disappears. Series live behind `MetricsPort` →
`infra/observability/MicrometerMetricsAdapter`. Prometheus rendering appends `_total`
(counters) and `_seconds` (timers).

| Meter (base name) | Type | Tags | Meaning |
|---|---|---|---|
| `urls.shortened.total` | Counter | service=url-shortener | `POST /api/v1/urls` invocations |
| `redirects.total` | Counter | service=url-shortener | `GET /{id}` redirects performed |
| `cache.hits.total` | Counter | cache=redis | cache-aside hits on the redirect path |
| `cache.misses.total` | Counter | cache=redis | cache-aside misses on the redirect path |
| `bloomfilter.rejections.total` | Counter | protection=cache-penetration | requests rejected by the bloom filter |
| `urls.expired.total` | Counter | service=url-shortener | expired short URLs hit |
| `schema.migrations.applied.total` | Counter | service=url-shortener | `MongoSchemaMigrator` V-versions applied |
| `schema.migrations.failed.total` | Counter | service=url-shortener | failed migrations (fail-fast) |
| `security.ssrf.blocked.total` | Counter | service=url-shortener | destination URLs blocked by SSRF protection |
| `analytics.events.enqueued.total` | Counter | — | click events pushed to the Redis Stream |
| `analytics.events.persisted.total` | Counter | — | click events bulk-inserted to `click_events` |
| `analytics.events.dropped.total` | Counter | — | events dropped (fail-open path) |
| `analytics.events.failed.total` | Counter | — | persistence failures (at-least-once retried) |
| `analytics.queue.depth` | Gauge | — | Redis-Stream pending length |
| `analytics.rollup.days.total` | Counter | — | rollup job runs |
| `analytics.rollup.errors.total` | Counter | — | rollup job failures |
| `analytics.rollup.groups.upserted.total` | Counter | — | rollup groups upserted |
| `analytics.retention.runs.total` | Counter | — | retention purge runs |
| `analytics.retention.purged.total` | Counter | — | expired documents purged |
| `analytics.retention.errors.total` | Counter | — | retention purge failures |
| `id.generation.duration` | Timer | service=url-shortener | Base62 + vanity code generation (p50/p95/p99) |
| `url.retrieval.duration` | Timer | service=url-shortener | single hot-path cache + DB lookup (p50/p95/p99) |
| `shorten.latency` | Timer | operation=shorten | end-to-end shorten request (p50/p95/p99) |
| `redirect.latency` | Timer | operation=redirect | end-to-end redirect request (p50/p95/p99) |

> **Consolidation (story 3.2):** the second metrics bean `infra/observability/MetricsService`
> (wired directly into `UrlController`) was folded into `MicrometerMetricsAdapter`; names,
> tags and descriptions are byte-for-byte preserved, so exposed series and dashboards are
> unchanged. Adding or removing a meter is a data-model change for Prometheus: update this
> table, `docs/observability.md`, the gate's `FROZEN_METERS` and any dashboards together.

## SLI definitions

All SLIs are computed from the Micrometer `http.server.requests` histogram
(`/actuator/prometheus`), tagged `service="url-shortener"`:

- **Availability SLI** — ratio of requests without a 5xx status:
  `sum(rate(http_server_requests_seconds_count{status!~"5.."}[W])) / sum(rate(http_server_requests_seconds_count[W]))`
- **Latency OK SLI** — ratio of requests served within the 200 ms target:
  `sum(rate(http_server_requests_seconds_bucket{le="0.2"}[W])) / sum(rate(http_server_requests_seconds_count[W]))`
- **Error rate SLI** — ratio of 5xx responses:
  `sum(rate(http_server_requests_seconds_count{status=~"5.."}[W])) / sum(rate(http_server_requests_seconds_count[W]))`

with `W = 30d`. Recording rules are in `deploy/monitoring/recording-rules.yml`.

## Burn-rate alerting

Availability error budget = `1 − 0.999 = 0.001`. Burn rate = error ratio ÷ error budget.

- **Fast burn — critical:** error ratio over 1h **and** 5m both > `0.001 × 14.4`
  (≈ 2% of budget consumed in 1h).
- **Slow burn — warning:** error ratio over 6h **and** 30m both > `0.001 × 6`
  (≈ 5% of budget consumed in 6h).
- **Budget exhausted:** `slo:url_shortener:availability_budget_remaining_30d == 0`.

Multi-window conditions prevent false positives from short traffic spikes with
no sustained burn. See `deploy/monitoring/alerts.yml`.

## Response runbook

| Condition | Alert | First action |
|-----------|-------|--------------|
| Fast burn | `SLOAvailabilityFastBurn` (critical) | Check p50/p95/p99 + error rate panels; inspect `logs/application.log` for errors; health-check MongoDB/Redis; rollback recent deploy if latency introduced it. |
| Slow burn | `SLOAvailabilitySlowBurn` (warning) | Scheduled investigation; correlate with deploy timeline (CHANGELOG) and k6 baseline (`docs/load-test-baseline.md`). |
| Budget exhausted | `SLOErrorBudgetExhausted` | Emergency — treat as incident; freeze deploys; add capacity or fix defect. |

## Review schedule

SLO targets and burn thresholds are reviewed every release cycle (`Releases` /
CHANGELOG milestone) against the latest k6 baseline. Changes require updating
this file, `deploy/monitoring/*.yml` and the SLO dashboard together.