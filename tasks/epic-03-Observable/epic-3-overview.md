# Epic 3: Observable – Total Visibility

**Project:** url-shortener-service
**Context (real, 2026-09):** Java 25, Spring Boot 4.1.1, Tomcat 11 (virtual threads), Hexagonal Architecture, MongoDB + Redis (Testcontainers), observability baseline already present (`docs/observability.md`, `docs/slos.md`, `deploy/monitoring/{prometheus,recording-rules,alerts}.yml`, `deploy/otel/`).
**Objective:** close the real observability gaps of this repository: correlation-id in 100% of logs, a "frozen" metrics gate, actuator lockdown tests in prod, alert rules validated by `promtool`/`amtool` in CI, and an operational quick-diagnosis panel.

---

## Why this epic now?

- **EP1 (Maintainable)** consolidated names/packages and the `check-doc-sync`; EP3 depends on that foundation for consistent log correlation.
- **EP2 (Secure)** delivered `logSafe`, headers and `security.ssrf.blocked.total`; EP3 gives operational visibility to this evidence.
- Building blocks **already exist** (baseline): tiered health checks + tiered actuator (debt 9), `MetricsPort` with 10 series, SLOs + burn-rate in `docs/slos.md`, 3 alert rules in `deploy/monitoring/alerts.yml`, OTel tracing (debt 12).
- The **real gaps** to close are listed in `epic-3-technical-tasks.md` §3.1–3.7 (new) vs. baseline (already exists).

**Elevated Acceptance Criteria:**

1. `request_id` in the MDC in **100%** of request logs (verified by `CorrelationIdIT` + validation script).
2. **Frozen metrics**: every series registered in `MicrometerMetricsAdapter` has a match in the `/actuator/prometheus` playback; a new counter/timer requires design review (gate in CI).
3. `ProductionLockdownIT` validates actuator in prod (`health/liveness`, `health/readiness`, `show-details` non-leak) — lockdown already implemented (debt 9); the end-to-end test is missing.
4. Alert rules in `deploy/monitoring/alerts.yml` with the `runbook-§X` annotation pointing to `docs/slos.md`; `promtool test rules` + `amtool check-config` green on every push.
5. Quick diagnostic panel: symptom → check → action table in `docs/observability.md` + working `scripts/debug-health.sh`.
6. CI: `observability` job running the gates above; failure = PR blocked.
7. **Zero "claims from memory"**: every number, sha or count in the evidence is pasted from a real command output.

**Relationship with other epics:**

| Epic | Dependency |
|-------|-------------|
| EP1 – Maintainable | Logging conventions, packages, `logSafe` |
| EP2 – Secure | `security.ssrf.blocked.total`, headers, tiered actuator |
| EP4 – Testing | Test stories derived from the stories below |
| EP5 – Performance | p95 latency SLOs measured via metrics/tracing |
| EP6 – Scalable | Pool/rate-limit tuning based on metrics |
| EP7 – Reliable | Circuit-breaker/bulkhead states exposed (if added) |
| EP8 – Deployable | Stable health checks/metrics for blue-green/canary |

---

*Next step: detailed stories (3.1–3.7) — already filled in `epic-3-stories.md` with baseline vs. gap.*