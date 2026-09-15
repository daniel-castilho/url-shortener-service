# Observability Gaps — Backlog
## Close Item 12: Timers, OpenTelemetry Tracing, SLOs, k6 Load Tests

**Priority:** P1 — product value. Fifth epic.
**All stories:** Must.
**Companions:** `observability-spec.md` · `observability-implementation-sequence.md`

**Execution status:** ready from `main` (`0579f5d`, `v0.6.0`).

---

## Epic Outcome

Observability is real: all four pillars (metrics, traces, logs, SLOs) are implemented, automated, and documented. No "observability gaps" remain in the debt matrix.

---

## Story Map

```text
FOUNDATION
T1  id.generation.duration timer + p50/p95/p99
T2  url.retrieval.duration timer + p50/p95/p99

TRACING
O1  OpenTelemetry deps + OTLP/HTTP exporter + BatchSpanProcessor
O2  Sampling: 10% probabilistic + always-on errors; fail-open policy
O3  MDC correlation (traceId/spanId) in logs
O4  Manual instrumentation spans (shorten, redirect, id.generation)

SLOs
S1  SLO definitions in docs/slos.md (availability, latency, error rate)
S2  Prometheus recording rules + burn-rate alerts (fast/slow)
S3  Grafana SLO dashboard (JSON)

LOAD TESTS
L1  k6 shorten script (POST /api/v1/urls)
L2  k6 redirect script (GET /{id}) with cache hit/miss
L3  k6 mixed workload script
L4  CI workflow (manual dispatch) + thresholds
L5  Baseline document

DASHBOARDS
D1  Overview dashboard (latency, throughput, errors, cache, queue)
D2  Tracing dashboard (span breakdown, trace duration, errors)
D3  SLO dashboard (compliance, budget, burn rate)

VERIFICATION & DELIVERY
V1  Integration tests for timers + tracing + SLOs
V2  k6 manual dispatch CI workflow
V3  Documentation sync (AGENTS.md, README, CHANGELOG, coding-standards, lessons)
V3  Tag v0.7.0
```

---

## T1 — id.generation.duration Timer

**Goal:** Record time to generate a short code with p50/p95/p99 percentiles.

### Work

- Add `recordIdGeneration(Duration)` to `MetricsPort`
- Add Timer metric in `MetricsService` with p50/p95/p99
- Implement in `MicrometerMetricsAdapter`
- Wrap `Base62CodeGenerator.generate()` and `VanityUrlIdStrategy.generate()` calls

### Acceptance

- [ ] `id.generation.duration` metric visible in `/actuator/prometheus` with p50/p95/p99
- [ ] Unit test verifies timer records correctly
- [ ] Integration test shows timer increments on shorten

---

## T2 — url.retrieval.duration Timer

**Goal:** Record time to resolve a short code (cache hit vs miss) with p50/p95/p99.

### Work

- Add `recordUrlRetrieval(Duration)` to `MetricsPort`
- Add Timer metric in `MetricsService` with p50/p95/p99
- Implement in `MicrometerMetricsAdapter`
- Wrap `UrlCachePort.get()` and `MongoUrlRepository.findById()` calls

### Acceptance

- [ ] `url.retrieval.duration` metric visible with p50/p95/p99
- [ ] Unit test verifies timer records correctly
- [ ] Integration test shows timer increments on redirect

---

## O1 — OpenTelemetry Dependencies & Exporter

**Goal:** Add OTel dependencies and configure OTLP/HTTP exporter.

### Work

- Add `opentelemetry-spring-boot-starter` + OTLP exporter deps
- Configure OTLP/HTTP exporter to local collector (dev)
- Use `BatchSpanProcessor` (not `SimpleSpanProcessor`)
- Configure resource attributes (service.name, version, env)

### Acceptance

- [ ] OTel starter on classpath; auto-configuration works
- [ ] OTLP/HTTP exporter sends spans to collector (verified in logs)
- [ ] `BatchSpanProcessor` used (not `SimpleSpanProcessor`)
- [ ] Resource attributes: `service.name`, `service.version`, `deployment.environment`

---

## O2 — Sampling & Fail-Open Policy

**Goal:** Configure sampling and fail-open behavior.

### Work

- Configure sampling: 10% probabilistic (`traceIdRatioBased(0.1)`) + always-on for errors
- Fail-open: if OTel collector unavailable, never block request; log warning
- Always-on for errors (HTTP 5xx, exceptions)

### Acceptance

- [ ] Sampling rate ~10% (verify in traces)
- [ ] Error spans always recorded (test with thrown exception)
- [ ] Collector down → request succeeds, warning logged

---

## O3 — MDC Correlation

**Goal:** Correlate logs with traces via MDC.

### Work

- Add `%X{traceId}` `%X{spanId}` to Logback pattern
- Verify traceId/spanId appear in logs for traced requests

### Acceptance

- [ ] Logs contain `traceId` and `spanId` for traced requests
- [ ] Non-traced requests show `-` for traceId/spanId

---

## O4 — Manual Instrumentation Spans

**Goal:** Add semantic spans for key operations.

### Work

- Add `@WithSpan` / manual span creation for:
  - `UrlShortenerService.shorten()` → `shorten`
  - `UrlShortenerService.getOriginalUrl()` → `redirect`
  - `Base62CodeGenerator.generate()` / `VanityUrlIdStrategy.generate()` → `id.generation`
  - `MongoUrlRepository.findById()` → `url.retrieval`
  - `RedisClickEventQueue.track()` → `analytics.track`

### Acceptance

- [ ] Spans visible in Jaeger/Tempo with correct names/attributes
- [ ] Span attributes include `http.method`, `url.short_code`, `strategy`, `cache.hit`
- [ ] Span events for `id.generated`, `cache.hit`, `db.query`, `analytics.queued`

---

## S1 — SLO Definitions

**Goal:** Document SLOs with rationale.

### Work

- Create `docs/slos.md` with:
  - SLI definitions, targets, windows
  - Error budget calculations
  - Burn-rate alert definitions
  - Review schedule (quarterly)

### Acceptance

- [ ] `docs/slos.md` exists and complete
- [ ] SLOs match spec: Availability 99.9%, Latency p99 < 200ms, Error Rate < 0.1%

---

## S2 — Prometheus Recording Rules & Burn-Rate Alerts

**Goal:** Automate SLO computation and burn-rate alerting.

### Work

- Add Prometheus recording rules for SLO SLIs (availability, latency, error rate)
- Add burn-rate alerting rules:
  - Fast: 2% budget in 1h → critical
  - Slow: 5% budget in 6h → warning
- Alerts fire on `Alertmanager` (or log if no Alertmanager)

### Acceptance

- [ ] Recording rules compute SLIs correctly (verified in Prometheus)
- [ ] Fast burn alert fires at 2%/1h (test by generating errors)
- [ ] Slow burn alert fires at 5%/6h (test by sustained errors)

---

## S3 — Grafana SLO Dashboard

**Goal:** Visualize SLO compliance and error budgets.

### Work

- Create `dashboards/url-shortener-slo.json` with:
  - SLO compliance gauge (availability, latency, error rate)
  - Error budget remaining (percentage)
  - Burn rate (fast/slow) time series
  - Error budget remaining percentage

### Acceptance

- [ ] Dashboard imports cleanly into Grafana
- [ ] Panels show correct data (verified against Prometheus)
- [ ] Thresholds colored correctly (green/yellow/red)

---

## L1 — k6 Shorten Script

**Goal:** Load test shorten endpoint.

### Work

- Create `load-tests/shorten.js`
- Simulate POST /api/v1/urls with random URLs
- Thresholds: p95 < 200ms, error rate < 0.1%

### Acceptance

- [ ] Script runs successfully against running app
- [ ] Thresholds enforced (CI fails if breached)

---

## L2 — k6 Redirect Script

**Goal:** Load test redirect endpoint with cache hit/miss.

### Work

- Create `load-tests/redirect.js`
- Test both cache hits (via repeated GET) and misses (new codes)
- Thresholds: p95 < 100ms (hit), < 300ms (miss), error rate < 0.1%

### Acceptance

- [ ] Script runs successfully; hits and misses both tested
- [ ] Thresholds enforced

---

## L3 — k6 Mixed Workload Script

**Goal:** Realistic mixed workload.

### Work

- Create `load-tests/mixed.js` combining shorten + redirect
- Simulate realistic traffic mix (e.g., 1:10 shorten:redirect)
- Thresholds match redirect script

### Acceptance

- [ ] Script runs; thresholds enforced

---

## L4 — k6 CI Workflow (Manual Dispatch)

**Goal:** Run load tests on demand via GitHub Actions.

### Work

- Create `.github/workflows/load-test.yml` with `workflow_dispatch`
- Run `k6 run load-tests/mixed.js` with thresholds
- Fail workflow if thresholds breached
- Artifact upload for `results.json`

### Acceptance

- [ ] Workflow runs successfully on manual dispatch
- [ ] Fails if thresholds breached
- [ ] Results artifact uploaded

---

## L5 — Baseline Document

**Goal:** Record performance baseline.

### Work

- Create `docs/load-test-baseline.md` with:
  - p50/p95/p99 for shorten and redirect (hit/miss)
  - Throughput (req/s) at various loads
  - Date, environment, k6 version

### Acceptance

- [ ] Document exists and is accurate
- [ ] Updated after each load test run

---

## D1 — Overview Dashboard

**Goal:** `dashboards/url-shortener-overview.json`

### Panels

- Latency p50/p95/p99 (shorten, redirect)
- Throughput (req/s)
- Error rate
- Cache hit rate
- Queue depth (analytics)

---

## D2 — Tracing Dashboard

**Goal:** `dashboards/url-shortener-tracing.json`

### Panels

- Trace duration (p50/p95/p99)
- Span breakdown by operation
- Error traces count
- Service map (if Tempo)

---

## D3 — SLO Dashboard

**Goal:** `dashboards/url-shortener-slo.json`

### Panels

- SLO compliance gauge (availability, latency, error rate)
- Error budget remaining (%)
- Burn rate (fast/slow) time series
- Error budget remaining %

---

## V1 — Integration Tests

**Goal:** Verify all observability components in integration.

### Work

- Add tests for timer metrics in `MongoUrlRepositoryIT` / `ClickPipelineIT`
- Add tracing IT (verify spans created, attributes correct)
- Add SLO IT (verify recording rules, alerts)

### Acceptance

- [ ] `mvn verify` passes with new ITs
- [ ] Coverage maintained ≥ 60%

---

## V2 — k6 CI Workflow (Manual Dispatch)

**Goal:** Run load tests on demand.

### Work

- Create `.github/workflows/load-test.yml` with `workflow_dispatch`
- Run `k6 run load-tests/mixed.js` with thresholds
- Fail workflow if thresholds breached
- Upload `results.json` artifact

### Acceptance

- [ ] Workflow runs on manual dispatch
- [ ] Fails if thresholds breached
- [ ] Artifacts uploaded

---

## V3 — Documentation Sync + Tag v0.7.0

**Goal:** All docs reflect applied observability.

### Work

- Update `docs/observability.md`, `docs/slos.md`, `docs/load-test-baseline.md`
- Update `CHANGELOG.md` (promote to `[0.7.0]`)
- Update `AGENTS.md` debt item 12 → `resolved`
- Update `README.md`, `docs/coding-standards.md`, `docs/lessons.md`
- Update `CHANGELOG.md` (promote `[Unreleased]` to `[0.7.0]`)
- Create tag `v0.7.0`, push

### Acceptance

- [ ] All docs reflect current state
- [ ] AGENTS.md item 12 → `resolved`
- [ ] Tag `v0.7.0` pushed

---

## Epic Definition of Done

- [ ] Timer metrics `id.generation.duration` / `url.retrieval.duration` recorded with p50/p95/p99
- [ ] OpenTelemetry tracing working (local dev + OTel collector); traces visible in Jaeger/Tempo
- [ ] SLOs defined, recorded, alerted (burn-rate alerts fire correctly)
- [ ] k6 load tests run on manual dispatch; thresholds enforced; baseline published
- [ ] Grafana dashboards import cleanly; panels show correct data
- [ ] Docs updated; AGENTS.md item 12 → `resolved`
- [ ] `mvn verify` green (unit + IT + JaCoCo + SpotBugs + jar)
- [ ] Tag `v0.7.0` created and pushed