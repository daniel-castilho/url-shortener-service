# Observability Gaps — Implementation Sequence
## Close Item 12: Timers, OpenTelemetry Tracing, SLOs, k6 Load Tests

**Companions:** `observability-spec.md` · `observability-backlog.md`
**Rule:** complete each step's acceptance and verification before starting the next. Do not invent out-of-scope work.

---

## Global Execution Rules

1. Work in small, reviewable vertical commits.
2. Read the referenced story acceptance before coding.
3. Add tests with the production change, not at the end.
4. The redirect path must never block or throw on analytics/tracing failure.
5. Use `$inc` — never read-modify-write. (The `QuotaService` bug is exactly this class; do not repeat it.)
6. No new Maven coordinate without approval (OTel starter + OTLP exporter approved here).
7. After each step, update task status and docs; do not silently alter the spec.

### Fast Verification (throughout)

```bash
mvn test
```

### Integration Verification

```bash
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

### Full Gate

```bash
mvn verify
```

---

## Step 0 — Baseline, Design Lock, Dependency Gate
### Stories: (context)

### Actions

1. Confirm HEAD (`0579f5d`), fast tests green, and current observability state.
2. Confirm Spring Data Redis / Lettuce, Micrometer, `StringRedisTemplate` on classpath.
3. Record locked decisions in `docs/observability.md`:
   - Timers: `id.generation.duration`, `url.retrieval.duration` with p50/p95/p99
   - Tracing: OpenTelemetry, OTLP/HTTP, BatchSpanProcessor, 10% sampling + always-on errors
   - SLOs: Availability 99.9%, Latency p99 < 200ms, Error Rate < 0.1%
   - Load tests: k6, manual dispatch, thresholds p95 < 200ms / error rate < 0.1%
   - Dashboards: Grafana JSON (overview, tracing, SLO)
4. Confirm OTel deps approved: `opentelemetry-spring-boot-starter`, `opentelemetry-exporter-otlp`.
5. Record locked decisions in `docs/observability.md`.

### Done When

- baseline understood; locked decisions recorded;
- dependency approach approved;
- no unresolved "or/if available" choice remains.

### Verify

```bash
mvn test
```

---

## Step 1 — Timer Metrics: id.generation.duration + url.retrieval.duration
### Stories: T1, T2

### Actions

1. Add `recordIdGeneration(Duration)`, `recordUrlRetrieval(Duration)` to `MetricsPort`.
2. Add Timer metrics in `MetricsService` with p50/p95/p99 publishing.
3. Implement in `MicrometerMetricsAdapter` (record methods).
4. Wrap `Base62CodeGenerator.generate()` and `VanityUrlIdStrategy.generate()` with timer.
5. Wrap `UrlCachePort.get()` and `MongoUrlRepository.findById()` with timer.

### Done When

- `id.generation.duration` and `url.retrieval.duration` metrics recorded with p50/p95/p99.
- Unit tests prove timers record correctly.
- Integration test proves timers increment on shorten/redirect.

### Verify

```bash
mvn test
mvn test -Dtest='MongoUrlRepositoryIT' -DfailIfNoTests=false
```

---

## Step 2 — OpenTelemetry: Dependencies & Exporter
### Stories: O1

### Actions

1. Add `opentelemetry-spring-boot-starter` + `opentelemetry-exporter-otlp` to `pom.xml`.
2. Configure OTLP/HTTP exporter in `application.yaml`:
   - Endpoint: `${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}`
   - `BatchSpanProcessor` (batch size 512, schedule delay 5s)
3. Configure resource attributes:
   - `service.name=url-shortener`
   - `service.version=${project.version}`
   - `deployment.environment=${SPRING_PROFILES_ACTIVE:dev}`
4. Register `OpenTelemetry` bean if needed (starter auto-configures).

### Done When

- OTel starter on classpath; auto-configuration works.
- OTLP/HTTP exporter sends spans to collector (verified in logs).
- `BatchSpanProcessor` used; resource attributes present.

### Verify

```bash
mvn compile
mvn test
```

---

## Step 3 — OpenTelemetry: Sampling & Fail-Open
### Stories: O2

### Actions

1. Configure sampling in `application.yaml`:
   - `management.tracing.sampling.probability=0.1` (10%)
   - Always-on for errors via custom sampler or `AlwaysOnSampler` for errors
2. Implement fail-open: catch `DataAccessException` / `RuntimeException` from OTel exporter; log warning; allow request to proceed.
2. Ensure error spans always recorded (always-on sampler for errors).

### Done When

- Sampling rate ~10% (verify in traces).
- Error spans always recorded (test with thrown exception).
- Collector down → request succeeds, warning logged.

### Verify

```bash
mvn test
```

---

## Step 4 — MDC Correlation & Manual Instrumentation
### Stories: O3, O4

### Actions

1. Add MDC correlation in `logback-spring.xml`:
   - Pattern includes `%X{traceId}` `%X{spanId}`
   - Verify traceId/spanId appear in logs for traced requests
2. Add manual spans:
   - `UrlShortenerService.shorten()` → `shorten` span (attributes: `http.method`, `url.original`, `url.short_code`, `strategy`)
   - `UrlShortenerService.getOriginalUrl()` → `redirect` span (attributes: `url.short_code`, `cache.hit`)
   - `Base62CodeGenerator.generate()` / `VanityUrlIdStrategy.generate()` → `id.generation` span (attributes: `strategy`, `attempt`)
   - `MongoUrlRepository.findById()` → `url.retrieval` span (attributes: `url.short_code`, `cache.hit`)
   - `RedisClickEventQueue.track()` → `analytics.track` span (attributes: `url.short_code`)
3. Add span events: `id.generated`, `cache.hit`, `db.query`, `analytics.queued`
3. Add span attributes: `http.method`, `url.short_code`, `strategy`, `cache.hit`, `attempt`

### Done When

- Spans visible in Jaeger/Tempo with correct names/attributes.
- MDC shows `traceId`/`spanId` in logs for traced requests.
- Span events visible for key operations.

### Verify

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 5 — SLO Definitions & Prometheus Recording Rules
### Stories: S1, S2

### Actions

1. Create `docs/slos.md` with SLI definitions, targets, windows, rationale, review schedule.
2. Add Prometheus recording rules (`recording-rules.yml`):
   - Availability SLI: `sum(rate(http_requests_total{status!~"5.."}[30d])) / sum(rate(http_requests_total[30d]))`
   - Latency p99 SLI: `sum(rate(http_request_duration_seconds_bucket{le="0.2"}[30d])) / sum(rate(http_request_duration_seconds_count[30d]))`
   - Error rate SLI: `sum(rate(http_requests_total{status=~"5.."}[30d])) / sum(rate(http_requests_total[30d]))`
3. Add burn-rate alerting rules (`alerts.yml`):
   - Fast: 2% budget in 1h → critical
   - Slow: 5% budget in 6h → warning
4. Register rules in Prometheus (via `prometheus.yml` or operator).

### Done When

- Recording rules compute SLIs correctly (verified in Prometheus).
- Fast burn alert fires at 2%/1h (test by generating errors).
- Slow burn alert fires at 5%/6h (test by sustained errors).

### Verify

```bash
mvn verify
# Manually verify in Prometheus/Grafana
```

---

## Step 6 — Grafana SLO Dashboard
### Stories: S3

### Actions

1. Create `dashboards/url-shortener-slo.json` with panels:
   - SLO compliance gauge (availability, latency, error rate)
   - Error budget remaining (%)
   - Burn rate (fast/slow) time series
   - Error budget remaining %
3. Verify dashboard imports cleanly; panels show correct data.

### Done When

- Dashboard imports cleanly into Grafana.
- Panels show correct data (verified against Prometheus).
- Thresholds colored correctly (green/yellow/red).

### Verify

```bash
# Import in Grafana UI; verify visually
```

---

## Step 7 — k6 Load Tests
### Stories: L1, L2, L3

### Actions

1. Create `load-tests/shorten.js`:
   - POST /api/v1/urls with random URLs
   - Thresholds: p95 < 200ms, error rate < 0.1%
3. Create `load-tests/redirect.js`:
   - Save URL via repository, then GET /{id}
   - Test both cache hits (repeated GET) and misses (new codes)
   - Thresholds: p95 < 100ms (hit), < 300ms (miss), error rate < 0.1%
3. Create `load-tests/mixed.js`:
   - Mixed shorten + redirect (1:10 ratio)
   - Thresholds as above
4. Add k6 thresholds in scripts (`thresholds` block).

### Done When

- All three scripts run successfully against running app.
- Thresholds enforced (CI fails if breached).

### Verify

```bash
k6 run load-tests/shorten.js
k6 run load-tests/redirect.js
k6 run load-tests/mixed.js
```

---

## Step 8 — k6 CI Workflow (Manual Dispatch)
### Stories: L4

### Actions

1. Create `.github/workflows/load-test.yml`:
   - `on.workflow_dispatch`
   - `k6 run load-tests/mixed.js` with thresholds
   - Fail workflow if thresholds breached
   - Upload `results.json` artifact
3. Add `workflow_dispatch` trigger (no schedule).

### Done When

- Workflow runs on manual dispatch.
- Fails if thresholds breached.
- `results.json` artifact uploaded.

### Verify

```bash
# Manual trigger via GitHub UI; verify workflow runs and passes
```

---

## Step 8 — Baseline Document
### Stories: L5

### Actions

1. Create `docs/load-test-baseline.md` with:
   - p50/p95/p99 for shorten and redirect (hit/miss)
   - Throughput (req/s) at various loads
   - Date, environment, k6 version
2. Update after each load test run.

### Done When

- Document exists and is accurate.
- Updated after each load test run.

---

## Step 9 — Grafana Dashboards
### Stories: D1, D2, D3

### Actions

1. Create `dashboards/url-shortener-overview.json`:
   - Latency p50/p95/p99 (shorten, redirect)
   - Throughput (req/s)
   - Error rate
   - Cache hit rate
   - Queue depth (analytics)
2. Create `dashboards/url-shortener-tracing.json`:
   - Trace duration (p50/p95/p99)
   - Span breakdown by operation
   - Error traces count
   - Service map (if Tempo)
3. Create `dashboards/url-shortener-slo.json`:
   - SLO compliance gauge (availability, latency, error rate)
   - Error budget remaining (%)
   - Burn rate (fast/slow) time series
   - Error budget remaining %

### Done When

- All three dashboards import cleanly into Grafana.
- Panels show correct data (verified against Prometheus).
- Thresholds colored correctly (green/yellow/red).

### Verify

```bash
# Import in Grafana UI; verify visually
```

---

## Step 10 — Integration Tests & Verification
### Stories: V1, V2

### Actions

1. Add tracing IT (verify spans created, attributes correct) in `ClickPipelineIT` or new `TracingIT`.
2. Add SLO IT (verify recording rules, alerts) in `SloIT`.
3. Run `RedirectRateLimitIT`, `ClickPipelineIT`, `SsrfProtectionIT` — all green.
4. Run `mvn verify` — full gate green.

### Done When

- All ITs green (including new ones).
- `mvn verify` green.

### Verify

```bash
mvn verify
```

---

## Step 11 — Documentation Sync + Tag v0.7.0
### Stories: V3

### Actions

1. Update `docs/observability.md` — architecture, SLOs, runbooks, dashboard links.
2. Update `docs/slos.md` — SLO definitions, rationale, review schedule.
3. Update `docs/load-test-baseline.md` — baseline from k6 runs.
4. Update `CHANGELOG.md` — promote `[Unreleased]` to `[0.7.0]`.
4. Update `AGENTS.md` — debt item 12 → `resolved`.
4. Update `README.md` — observability section updated.
4. Update `docs/coding-standards.md` — observability bullet.
4. Update `docs/lessons.md` — lessons learned.
4. Create tag `v0.7.0`, push.

### Done When

- All docs reflect current state.
- AGENTS.md item 12 → `resolved`.
- Tag `v0.7.0` pushed to origin.

### Verify

```bash
mvn verify
git tag -a v0.7.0 -m "v0.7.0 — Observability: timers, tracing, SLOs, k6"
git push origin main v0.7.0
```

---

## Final Smoke / Acceptance Path

1. `mvn test` → 155 unit tests pass.
2. `mvn verify` → 35 IT/E2E pass, coverage ≥ 60%, SpotBugs 0.
3. `bash scripts/check-boundaries.sh --self-test` → PASS.
3. `k6 run load-tests/mixed.js` (manual) → passes thresholds.
4. Import Grafana dashboards → all panels green.
5. `git tag -a v0.7.0 -m "v0.7.0 — Observability: timers, tracing, SLOs, k6"` && `git push origin main v0.7.0`.

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| OTel collector unavailable in CI | Medium | High | Fail-open in adapter; CI uses mock collector or skips tracing tests |
| k6 flakiness in CI | Low | Medium | Run on manual dispatch only; generous thresholds |
| SpotBudgets false positives | Low | Low | Use `@SuppressFBWarnings` only with justification |
| Coverage regression | Low | Medium | Add tests with each feature; monitor CI coverage trend |

---

## As-built record (2026-08-27, delivered)

**Status:** v0.7.0 delivered (tag candidate), full gate green (`mvn verify`).

### Delivered

- **Step 1 (timers):** `MetricsPort.recordIdGeneration/recordUrlRetrieval` + `MicrometerMetricsAdapter`
  timers with p50/p95/p99; wired in `UrlShortenerService` (Base62 + vanity ID generation, redirect
  cache+DB lookup). Unit tests: `MicrometerMetricsAdapterTest` (4) + 3 new assertions in
  `UrlShortenerServiceTest`. 162 unit tests total.
- **Step 2 (deps/exporter):** `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` +
  `opentelemetry-sdk-extension-autoconfigure`; `management.otlp.tracing.endpoint`
  (`${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}`); `management.tracing.sampling.probability=0.1`.
- **Step 3 (sampling/fail-open):** head sampling 10%; ERROR traces always kept via collector
  **tail sampling** (`deploy/otel/otel-collector-config.yml`) — chosen over a Java sampler because a
  Sampler only sees a span at start, before its outcome is known. Fail-open proven by
  `TracingFailOpenIT` (2 tests; endpoint pointed at a dead port).
- **Step 4 (MDC):** `logback-spring.xml` adds `%X{traceId}` `%X{spanId}` by overriding
  `CONSOLE_LOG_PATTERN` (the included `console-appender.xml` already declares `CONSOLE`;
  re-declaring it breaks startup). **Deviation:** manual business spans (`shorten`/`redirect`/
  `id.generation`) were **not** added to `core/` — they violated the architectural boundary
  (the gate fails on `io.micrometer` imports in `core/`); HTTP spans come from Spring Boot
  auto-instrumentation instead. Timers cover the business latency signals.
- **Step 5 (SLOs):** `docs/slos.md`, `deploy/monitoring/recording-rules.yml`,
  `deploy/monitoring/alerts.yml` (fast 14.4x/1h critical, slow 6x/6h warning, budget exhausted).
- **Steps 6+9 (dashboards):** `dashboards/url-shortener-{overview,tracing,slo}.json` (validated).
- **Steps 7+8 (load tests):** `load-tests/{shorten,redirect,mixed}.js`,
  `.github/workflows/load-test.yml` (workflow_dispatch), `docs/load-test-baseline.md` (template).
- **Step 10:** `mvn verify` green — 162 unit + 37 IT/E2E, JaCoCo gate passed, SpotBugs 0,
  boundary check (incl. self-test) PASS.
- **Step 11 (docs):** CHANGELOG v0.7.0, AGENTS.md item 12 → `resolved` + new item 17 (open),
  README observability section, coding-standards/l lessons, `docs/observability.md`.

### Remaining per plan (tracked as AGENTS.md item 17)

- Expose `analytics.queue.depth` gauge (Grafana overview panel present but empty until then).
- Publish the first concrete k6 baseline (`docs/load-test-baseline.md`).

### Notes

- Root cause of the compile blocker: `UrlShortenerService.shorten` was missing a `}` to close the
  `if (customAlias != null …)` block (introduced by the previous manual-span refactor), which made
  every subsequent rewrite of `withSpan(...)` parse as "illegal start of type/expression" at
  lines ~111–114. Fixed by restoring the balanced structure; span code dropped (see deviation above).