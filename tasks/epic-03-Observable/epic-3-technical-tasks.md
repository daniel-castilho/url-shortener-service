# Epic 3 – Technical Tasks

> Real prefix: `service=url-shortener` (Micrometer tag). Current business series (baseline, `MicrometerMetricsAdapter`):
> counters: `urls.shortened.total`, `cache.hits.total`, `cache.misses.total`, `bloomfilter.rejections.total`, `urls.expired.total`, `schema.migrations.applied.total`, `schema.migrations.failed.total`, `security.ssrf.blocked.total` — timers (p50/p95/p99): `id.generation.duration`, `url.retrieval.duration`.
> `dargent_*`/`MetricsConfig.java`/"12 series" **do not exist** — template drift from another project (dargent) was corrected.

## 3.1 Implement correlation-Id across the whole pipeline
- [ ] Create `RequestCorrelationFilter` (`OncePerRequestFilter`, registered in `SecurityConfig`/`WebMvcConfig`):
    - Inbound `X-Request-Id` validated (ASCII ≤ 64 chars, no CR/LF/control chars); malformed → UUID generated.
    - Missing → generate UUID; **echo it in the response header** (`X-Request-Id`).
    - `MDC.put("request_id", ...)` for each request; cleanup in the `finally`.
    - Propagation to the async workpath (analytics/click events) — document the limitation if outbox/consumers do not exist.
- [ ] Verify that every request log has `request_id` in the MDC (appenders do not overwrite it).
- [ ] Create `CorrelationIdIT` (RestAssured end-to-end): asserts echoed header + `request_id` present in captured logs.
- [ ] Paste the test and validation-grep output into the handoff-DoD.

## 3.2 Freeze Prometheus metrics (gate)
- [ ] Create `scripts/check-metrics-frozen.sh` (or `metrics-frozen-check`) that:
    - Brings up the expected list via Micrometer (static: the 10 business series + the JVM/web/Jakarta patterns that exist in the playback).
    - Compares the rendered `/{actuator}/prometheus` against the frozen list → fails if a new series appears without updating the list (a change requires design review + list bump).
- [ ] Wire into `verify` (execution) OR into CI (job `observability`).
- [ ] `./mvnw verify` → `metrics-frozen-check` PASS.
- [ ] Paste the script output and the frozen list into the handoff-DoD.

## 3.3 Tiered health checks — prod lockdown test
- [ ] Baseline: verify `app.security.actuator` + `management.health.show-details`/`spring security` (debt 9) cover public liveness/readiness and detail when authorized.
- [ ] Create `ProductionLockdownIT` (`prod` profile + actuator): asserts
    - `/actuator/health/liveness` → 200
    - `/actuator/health/readiness` → 200 with Mongo/Redis up
    - `/actuator/health` in prod → no sensitive `details` (show-details does not leak)
    - non-public endpoints → 401/403 per profile
- [ ] `./mvnw test -Dtest='ProductionLockdownIT'` → green.
- [ ] Paste output into the handoff-DoD.

## 3.4 Prometheus alert rules — validated in CI
- [ ] `deploy/monitoring/alerts.yml`: ensure the `runbook-§X` annotation (X = `docs/slos.md` section) on every rule (baseline: 3 burn-rate rules).
- [ ] CI: job/step that downloads `promtool` + `amtool` (pinned-version binaries or prom/alertmanager containers) and runs `promtool test rules` + `amtool check-config` (promtool/amtool **not** installed locally/in CI today).
- [ ] Add rule tests (`promtool test rules`), unit/utest3 friendly, under the `deploy/monitoring/test/` tree.
- [ ] Green on `./^promtool test rules` and `amtool check-config` local + CI.
- [ ] Paste outputs into the handoff-DoD.

## 3.5 Implement a quick diagnostic panel
- [ ] Consolidate the symptom → check → action table (≥5 rows) in `docs/observability.md` (new section §Quick Diagnostics).
- [ ] Create `scripts/debug-health.sh`:
    - `curl /actuator/prometheus | grep <series>` for each critical one of the 10 business series.
    - Prints state + recommended action per SLO (`docs/slos.md`).
- [ ] `bash scripts/debug-health.sh` → readable output, no errors.
- [ ] Paste output into the handoff-DoD.

## 3.6 Integrate into CI (GitHub Actions)
- [ ] `observability` job in `.github/workflows/ci.yml`:
    - `scripts/check-metrics-frozen.sh`
    - `promtool test rules` + `amtool check-config` (pinned binaries)
    - (optional) `bash scripts/debug-health.sh` against the application image
- [ ] Failure in any job → PR not mergeable (requires existing branch protection).
- [ ] Paste the workflow snippet into the handoff-DoD.

## 3.7 Observability backward-compatible with EP2
- [ ] `security.ssrf.blocked.total` in the frozen list + verified in `SsrfProtectionIT` (already incremented).
- [ ] No name collisions with the other series (single listing).
- [ ] `./mvnw verify` combined (EP1+EP2+EP3) → green (unit 280 + IT 128 baseline, 2026-09-10).

---

**Epic 3 completion checklist:**

- [ ] `request_id` in 100% of logs (MDC) + `CorrelationIdIT` green
- [ ] 10 business series "frozen"; `metrics-frozen-check` PASS
- [ ] `ProductionLockdownIT` → tiered health in prod green
- [ ] 3+ rules with `runbook-§X`; `promtool test rules` + `amtool check-config` green
- [ ] Quick diagnostic panel (`debug-health.sh`) green
- [ ] CI job `observability` green
- [ ] Backward-compatible integration with EP2 metrics green
- [ ] `./mvnw verify` fully green (unit + IT + gates)

*Once all are checked, Epic 3 is complete and the next epic (EP4 – Testing) can start.*