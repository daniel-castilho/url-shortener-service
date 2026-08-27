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