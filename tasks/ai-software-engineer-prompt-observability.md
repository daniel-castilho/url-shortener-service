# AI Software Engineer Prompt: Observability Gaps (Item 12)
## Close Item 12: Timers, OpenTelemetry Tracing, SLOs, k6 Load Tests

**Context:** You are an AI software engineer working on the URL Shortener Service (Java 21, Spring Boot 3.5.7, MongoDB, Redis, hexagonal architecture). The codebase is at `v0.6.0` (commit `0579f5d`). Debt item 12 (Observability Gaps) is the next epic.

**Repository:** `/home/castilho/projects/url-shortener/url-shortener-service`
**Package:** `ca.tyny.urlshortener`
**Architecture:** Hexagonal — `core/` (domain, ports, services) must never import `infra/` or framework classes.

---

## Your Mission

Implement the complete observability stack to close debt item 12:

1. **Timer Metrics** — `id.generation.duration`, `url.retrieval.duration` with p50/p95/p99
2. **OpenTelemetry Tracing** — OTLP/HTTP, 10% sampling + always-on errors, fail-open, MDC correlation, manual spans
3. **SLOs** — Availability 99.9%, Latency p99 < 200ms, Error Rate < 0.1%; burn-rate alerts
4. **k6 Load Tests** — shorten, redirect, mixed; manual dispatch CI; thresholds
5. **Grafana Dashboards** — JSON models (overview, tracing, SLO)
4. **Documentation** — sync all docs, tag v0.7.0

---

## Locked Decisions (Do Not Deviate)

| Decision | Value |
|----------|-------|
| OTel Collector | Local dev default (localhost:4318) |
| SLO Targets | Availability 99.9%, Latency p99 < 200ms, Error Rate < 0.1% |
| Sampling | 10% probabilistic + always-on errors |
| k6 CI | Manual dispatch only |
| Dashboards | Grafana JSON models |
| OTel Exporter | OTLP/HTTP (firewall-friendly) |
| Sampling | 10% probabilistic + always-on errors |
| Fail-Open | Never block request on OTel failure |

---

## Reference Documents

- **Spec:** `tasks/observability-spec.md`
- **Backlog:** `tasks/observability-backlog.md`
- **Sequence:** `tasks/observability-implementation-sequence.md`

---

## Your Instructions

### 1. Follow the Implementation Sequence

Execute steps in order: `observability-implementation-sequence.md` Steps 0-11.
**Rule:** Complete each step's acceptance and verification before starting the next.

### 2. Quality Gates (Non-Negotiable)

```bash
# Fast loop
mvn test

# Integration
mvn test -Dtest='*IT' -DfailIfNoTests=false

# Full gate (run before declaring step done)
mvn verify
```

**Must pass before declaring any step done:**
- 155+ unit tests, 35+ IT/E2E tests
- JaCoCo LINE ≥ 60%, BRANCH ≥ 60%
- SpotBugs 0 findings
- Boundary check PASS + self-test PASS

### 3. Architecture Boundaries (Never Violate)

- `core/` (domain, ports, services) — **zero** Spring/Lombok/framework imports, zero `infra.*` imports
- `infra/` — Spring, Lombok, Micrometer, OTel, Redis, MongoDB allowed
- `MetricsPort` in `core/ports/outgoing` — no Micrometer imports
- `MicrometerMetricsAdapter` in `infra/observability` — implements `MetricsPort`

### 4. Testing Standards

- Unit tests: JUnit 5 + Mockito, no Spring context, no I/O
- Integration tests: `@SpringBootTest(webEnvironment=RANDOM_PORT)` + Testcontainers (Mongo + Redis)
- Test naming: `*Test` = unit, `*IT` = integration (failsafe)
- Mock ports, not implementations

### 5. Code Style

- Java 21: `record` for value objects/DTOs, explicit constructors in `core/`, Lombok allowed in `infra/`
- No wildcard imports
- No `@Component`/`@Service` in `core/` — beans via `@Configuration` in `infra/config`
- Structured logging with MDC (`traceId`, `spanId`)

### 5. Documentation = Part of "Done"

Every step updates:
- `docs/observability.md`, `docs/slos.md`, `docs/load-test-baseline.md`
- `AGENTS.md` (debt matrix)
- `README.md` (Current State / Roadmap)
- `CHANGELOG.md` (Unreleased → version)
- `docs/coding-standards.md`, `docs/lessons.md`

---

## Step-by-Step Execution

### Step 0 — Baseline & Design Lock
- Confirm HEAD, fast tests green
- Record decisions in `docs/observability.md`
- Verify OTel deps approved

### Step 1 — Timer Metrics (T1, T2)
- Add `recordIdGeneration`, `recordUrlRetrieval` to `MetricsPort`
- Timers in `MetricsService` with p50/p95/p99
- Implement in `MicrometerMetricsAdapter`
- Wrap `Base62CodeGenerator.generate()`, `VanityUrlIdStrategy.generate()`
- Wrap `UrlCachePort.get()`, `MongoUrlRepository.findById()`
- Verify: unit + IT tests green

### Step 2 — OTel Deps & Exporter (O1)
- Add `opentelemetry-spring-boot-starter`, `opentelemetry-exporter-otlp`
- OTLP/HTTP exporter, `BatchSpanProcessor` (batch 512, delay 5s)
- Resource attributes: `service.name`, `version`, `environment`

### Step 3 — Sampling & Fail-Open (O2)
- 10% probabilistic (`traceIdRatioBased(0.1)`) + always-on errors
- Fail-open: collector down → log warning, allow request
- Error spans always recorded

### Step 4 — MDC Correlation & Manual Spans (O3, O4)
- Logback pattern: `%X{traceId}` `%X{spanId}`
- Manual spans: `shorten`, `redirect`, `id.generation`, `url.retrieval`, `analytics.track`
- Span attributes: `http.method`, `url.short_code`, `strategy`, `cache.hit`, `attempt`
- Span events: `id.generated`, `cache.hit`, `db.query`, `analytics.queued`

### Step 5 — SLO Definitions & Recording Rules (S1, S2)
- `docs/slos.md` with SLI definitions, targets, windows
- Prometheus recording rules for SLIs
- Burn-rate alerts: fast (2%/1h), slow (5%/6h)

### Step 6 — SLO Dashboard (S3)
- Grafana JSON: compliance gauge, error budget remaining, burn rate

### Step 7-8 — k6 Load Tests (L1-L4)
- `load-tests/shorten.js`, `redirect.js`, `mixed.js`
- Thresholds: p95 < 200ms (shorten), p95 < 100ms/300ms (redirect hit/miss), error rate < 0.1%
- CI workflow: manual dispatch, thresholds enforced, artifact upload

### Step 9 — Grafana Dashboards (D1, D2, D3)
- `url-shortener-overview.json`, `url-shortener-tracing.json`, `url-shortener-slo.json`
- Import cleanly, panels correct

### Step 10 — Integration Tests & Full Gate
- Tracing IT, SLO IT, all existing ITs green
- `mvn verify` green

### Step 11 — Doc Sync + Tag v0.7.0
- Update all docs, AGENTS.md item 12 → resolved
- Tag `v0.7.0`, push

---

## Verification Checklist (Per Step)

- [ ] `mvn test` — 155+ unit tests pass
- [ ] `mvn test -Dtest='*IT' -DfailIfNoTests=false` — 35+ ITs pass
- [ ] `mvn verify` — full gate green (coverage ≥ 60%, SpotBugs 0)
- [ ] `bash scripts/check-boundaries.sh --self-test` — PASS

---

## Non-Negotiable Rules

1. **Never** add Spring/Lombok/framework imports to `core/`
2. **Never** add `infra.*` imports to `core/`
3. **Never** add `@Component`/`@Service` in `core/`
4. **Never** use `@Autowired` in `core/` (constructor injection only)
5. **Never** commit without running full gate
6. **Never** add Maven deps without approval (OTel starter + OTLP exporter approved)
7. **Never** weaken SLO targets or thresholds without approval

---

## Current State Reference

- **HEAD:** `0579f5d` (`v0.6.0` tagged)
- **Gate:** 155 unit + 35 IT/E2E, coverage ≥ 60%, SpotBugs 0, boundary PASS
- **Key Classes to Touch:**
  - `MetricsPort.java`, `MetricsService.java`, `MicrometerMetricsAdapter.java`
  - `Base62CodeGenerator.java`, `VanityUrlIdStrategy.java`
  - `MongoUrlRepository.java`, `RedisUrlCache.java`
  - `UrlShortenerService.java`
  - `OpenApiConfig.java` (new), `SecurityConfig.java` (existing)

---

## Questions? Ask Before Proceeding

If any locked decision needs clarification, or if a story's acceptance criteria are ambiguous, **ask before implementing**. Do not assume.

---

*This prompt is your contract. Follow it exactly. Quality gates are your definition of done.*