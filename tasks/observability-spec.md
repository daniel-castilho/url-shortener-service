# Observability Gaps — Technical Specification
## Close Item 12: Timers, OpenTelemetry Tracing, SLOs, k6 Load Tests

**Status:** ready for implementation from current `main` (`0579f5d`, `v0.6.0`).
**Priority:** P1 — product value. Fifth epic.
**Companions:** `observability-backlog.md` · `observability-implementation-sequence.md`

---

## 1. Purpose

The service has basic metrics (`shorten.latency`, `redirect.latency`) but lacks:

1. **Key timers** — `id.generation.duration` and `url.retrieval.duration` are documented but never recorded
2. **Distributed tracing** — no OpenTelemetry tracing; no correlation between logs/metrics/traces
3. **SLOs** — no formal Service Level Objectives, error budgets, or burn-rate alerting
4. **Load testing** — no k6 scenarios; no performance baseline or regression detection
5. **Dashboards** — no Grafana dashboards; observability is ad-hoc

This epic makes observability real: all four pillars (metrics, traces, logs, SLOs) are implemented, automated, and documented.

---

## 2. Scope

### In scope

- **Timer metrics** — `id.generation.duration`, `url.retrieval.duration` recorded via Micrometer with p50/p95/p99
- **OpenTelemetry tracing** — OTLP/HTTP to local collector (dev); Java Agent OR starter (not both); BatchSpanProcessor; 10% sampling + always-on errors; MDC correlation
- **SLOs** — Availability (99.9%), Latency p99 (200ms), Error Rate (<0.1%); 30-day rolling window; burn-rate alerts (fast/slow)
- **k6 load tests** — shorten, redirect, mixed; manual dispatch CI; thresholds (p95 < 200ms, error rate < 0.1%)
- **Grafana dashboards** — JSON models for overview, tracing, SLOs
- **Documentation** — `docs/observability.md`, `docs/slos.md`, `docs/load-test-baseline.md`, updated `CHANGELOG.md`, `AGENTS.md` item 12 → `resolved`

### Out of scope

- Log aggregation / Loki integration
- Alertmanager / PagerDuty integration (alerting rules only)
- Synthetic monitoring / uptime checks
- Chaos engineering / fault injection
- Custom OpenTelemetry Java Agent build (use standard agent or starter)

---

## 3. Architectural Constraints

### 3.1 Dependency Direction (Hexagonal)

- `core/` stays framework-free. Tracing/Micrometer only in `infra/`.
- `MetricsPort` stays in `core/ports/outgoing`. `MicrometerMetricsAdapter` in `infra/observability`.
- OpenTelemetry config lives in `infra/config` and `application.yaml`.

### 3.2 Cross-System Consistency

- Tracing and metrics are separate concerns. Never put a tracing call in the metrics port.
- Metrics are recorded synchronously; tracing is asynchronous where possible.
- No `@Transactional` across Mongo + Redis; tracing spans are independent.

### 3.3 Sampling & Fail-Open

- **Tracing:** 10% probabilistic (`traceIdRatioBased(0.1)`) + always-on for errors. Fail-open: if OTel collector unavailable, never block the request.
- **Metrics:** Never fail-open. Metrics must always succeed; if MeterRegistry fails, log and continue.

---

## 4. Data Model

### 4.1 Metrics (Micrometer)

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `id.generation.duration` | Timer | `strategy` (random/vanity) | Time to generate a short code |
| `url.retrieval.duration` | Timer | `source` (cache/db) | Time to resolve a short code |
| `shorten.latency` | Timer | — | End-to-end shorten latency |
| `redirect.latency` | Timer | — | End-to-end redirect latency |
| `urls.shortened.total` | Counter | — | Total URLs shortened |
| `redirects.total` | Counter | — | Total redirects |
| `cache.hits.total` | Counter | `cache` (redis/l1) | Cache hits |
| `cache.misses.total` | Counter | `cache` | Cache misses |
| `bloomfilter.rejections.total` | Counter | `protection` | Bloom filter rejections |

### 4.2 Tracing (OpenTelemetry)

| Span | Attributes | Events |
|------|------------|--------|
| `shorten` | `http.method`, `http.target`, `url.original`, `url.short_code`, `strategy` | `id.generated`, `cache.miss`, `db.insert` |
| `redirect` | `http.method`, `http.target`, `url.short_code`, `cache.hit` | `cache.hit`, `db.query`, `analytics.queued` |
| `id.generation` | `strategy`, `attempt` | `collision`, `retry` |

**Sampling:** 10% probabilistic (`traceIdRatioBased(0.1)`) + always-on for errors (HTTP 5xx, exceptions).

---

## 5. SLO Definition

| SLO | SLI | Target | Window | Error Budget |
|-----|-----|--------|--------|--------------|
| **Availability** | `http_requests_total{status!~"5.."}` / `http_requests_total` | ≥ 99.9% | 30d rolling | 43.2 min/month |
| **Latency (p99)** | `http_request_duration_seconds_bucket{le="0.2"}` / `count` | ≥ 99% < 200ms | 30d rolling | 1% of requests |
| **Error Rate** | `http_requests_total{status=~"5.."}` / `http_requests_total` | < 0.1% | 30d rolling | 0.1% of requests |

**Burn-rate alerts:**
- **Fast:** 2% budget in 1h (14.4× normal) → critical
- **Slow:** 5% budget in 6h (2× normal) → warning

---

## 6. API Contract

No outward contract change. Observability is internal.

---

## 7. Observability

### 7.1 Metrics (already implemented + new)

- New timers: `id.generation.duration`, `url.retrieval.duration`
- All timers publish p50/p95/p99 percentiles
- All counters have low-cardinality tags only (no user ID, IP, raw code)

### 7.2 Tracing (new)

- OTLP/HTTP exporter to local collector (dev) / Grafana Cloud (prod)
- `BatchSpanProcessor` with `BatchSpanProcessorOptions` (batch size 512, schedule delay 5s)
- Sampling: `traceIdRatioBased(0.1)` + always-on for errors
- MDC correlation: `%X{traceId}` `%X{spanId}` in log pattern

### 7.3 SLO Dashboards (Grafana JSON)

| Dashboard | Panels |
|-----------|--------|
| `url-shortener-overview` | Latency p50/p95/p99, throughput, error rate, cache hit rate, queue depth |
| `url-shortener-tracing` | Trace duration, span breakdown, error traces, service map |
| `url-shortener-slo` | SLO compliance, error budget remaining, burn rate (fast/slow) |

---

## 8. Verification Commands

```bash
# Unit tests (fast, no Docker)
mvn test

# Integration tests (Docker + Testcontainers)
mvn verify

# Full gate (unit + IT + JaCoCo + SpotBugs + jar)
mvn verify

# k6 load test (manual dispatch)
k6 run load-tests/mixed.js

# Verify dashboards import
grafana dashboard import --file dashboards/url-shortener-overview.json
```

---

## 9. Documentation Deliverables

Update in same epic:

- `docs/observability.md` — architecture, SLOs, runbooks, dashboard links
- `docs/slos.md` — SLO definitions, rationale, review schedule
- `docs/load-test-baseline.md` — p50/p95/p99 baseline from k6 runs
- `CHANGELOG.md` — `[Unreleased]` → `[0.7.0]` entry
- `AGENTS.md` — debt item 12 → `resolved`
- `README.md` — observability section updated
- `CHANGELOG.md` — promote `[Unreleased]` to `[0.7.0]`

---

## 10. Definition of Done

- [ ] Timer metrics `id.generation.duration` / `url.retrieval.duration` recorded with p50/p95/p99
- [ ] OpenTelemetry tracing working (local dev + OTel collector); traces visible in Jaeger/Tempo
- [ ] SLOs defined, recorded, alerted (burn-rate alerts fire correctly)
- [ ] k6 load tests run on manual dispatch; thresholds enforced; baseline published
- [ ] Grafana dashboards import cleanly; panels show correct data
- [ ] All docs updated; AGENTS.md item 12 → `resolved`
- [ ] `mvn verify` green (unit + IT + JaCoCo + SpotBugs + jar)
- [ ] Tag `v0.7.0` created and pushed

---

*Pre-implementation spec. Preserve deviations and final evidence as as-built record after delivery.*