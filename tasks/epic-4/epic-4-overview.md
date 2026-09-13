# Epic 4: Testing – Quality as a Foundation

**Project:** url-shortener-service
**Context:** Java 25, Spring Boot 4.1.1, Hexagonal Architecture, MongoDB and Redis (Testcontainers 2.0.5)
**Goal:** Consolidate the testing strategy as the pillar that ensures every change (feature,
refactoring, upgrade) is proven before reaching production, with controlled coverage, deterministic
tests and fast feedback.

---

## Why is this epic fourth?

- **EP1 (Maintainable)** provides the base of packages, names and conventions that tests depend on
  to be located and executed predictably (Spotless + ArchUnit + `check-boundaries.sh`).
- **EP2 (Secure)** introduced the secure login status, SSRF protection, ConfigValidator and HTTP
  headers; EP4 validates that these features are covered by integration and unit tests
  (`SsrfProtectionIT`, `ProdConfigValidatorIT`, `SecurityHeadersIT`).
- **EP3 (Observable)** provides the metrics (24 frozen series), logs (correlation-id/MDC) and health
  checks that the performance and load tests use as *ground truth*
  (`check-metrics-frozen.sh`, `ProductionLockdownIT`, promtool/amtool).
- **EP4 → EP5/Performance:** Load tests and SLO validation are only reliable if the unit and
  integration test base is solid (k6 baseline already published in `docs/load-test-baseline.md`).
- **EP4 → EP6/Scalable:** The confidence to scale comes from concurrency, race and boundary gate
  tests that already live in this epic.
- **EP4 → EP7/Reliable:** Fault tolerance is validated by chaos tests, circuit‑breaker states and
  reconciler scenarios.
- **EP4 → EP8/Deployable:** The deploy pipeline is only considered safe when the `./mvnw verify`
  gate (unit + IT + E2E) is consistently green.

**Elevated Acceptance Criteria:**

1. `./mvnw test` → green for all `core` and `infra` modules (270 unit tests, no Docker).
2. `./mvnw verify` (includes `*IT`) → JaCoCo coverage respects the floors configured in the pom:
   BUNDLE LINE/BRANCH ≥ 60% and `core.*` LINE/BRANCH ≥ 70% (measured 2026-09-10: core LINE 90.6% /
   BRANCH 81.0%).
3. Zero *flaky* tests in the main suite: tests that fail once and pass on rerun are investigated and
   fixed.
4. All *stories* of the epic have at least one associated automated test cited in the `AGENTS.md`.
5. **Rule zero — zero‑from‑memory:** every number, sha or count in the evidence is pasted from real
   command output.

**Relationship with other epics:**

| Epic           | Direct dependency                                                                 |
|----------------|------------------------------------------------------------------------------------|
| EP1 – Maintainable | Packages, names, code conventions that tests validate (ArchUnit, boundary gate) |
| EP2 – Secure      | SSRF, ConfigValidator, SecurityHeaders tests (`SsrfProtectionIT`, `ProdConfigValidatorIT`, `SecurityHeadersIT`) |
| EP3 – Observable  | Metrics (24 frozen series) and logs that the performance/SLO tests consume        |
| EP5 – Performance | Load tests (k6) and benchmarks that validate latency and throughput                |
| EP7 – Reliable    | Circuit‑breaker, bulkhead, reconciler, outbox exhaustion tests                |
| EP8 – Deployable  | `./mvnw verify` pipeline as the final gate before any deploy                |

---

*Next step: execute the stories (4.1‑4.5) as landed in `tasks/epic-4/epic-4-stories.md`.*