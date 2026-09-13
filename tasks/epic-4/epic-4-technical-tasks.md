# Epic 4 – Technical Tasks

**Landing:** real commands (`./mvnw`, not `mvn`); real test/script names; no references to
`MetricsConfig.java`, 12 `dargent_*` series or a separate `test.yml` workflow (the CI AC is fulfilled
by the existing `ci.yml`).

## 4.1 Consolidate the test stratification (pyramid) — `docs/testing-playbook.md` §1–2
- [x] Review the `src/test/java` structure (mirrors the production packages; no artificial
      `core/`/`slice/`/`it/` folder — stratification is by suffix convention; moving tests would be
      churn that would break ArchUnit/JaCoCo PACKAGE `core.*`).
- [x] Pure `*Test` unit tests without Spring/I/O (`core/model`, `core/service`, `core/idgeneration`,
      `core/validation`).
- [x] `@WebMvcTest` slices (`UrlControllerTest`, `AuthControllerTest`,
      `UrlControllerRateLimitingTest`, `GlobalExceptionHandlerTest`).
- [x] `*IT` integration tests with Testcontainers (MongoDB + Redis), singleton via
      `BaseIntegrationTest`, without `@DirtiesContext`.
- [x] Run `./mvnw test` → green (271 unit, no Docker, 26.734 s), timing pasted in the DoD.
- [x] Run `./mvnw test -Dtest='*IT'` → green (139 before the gap → 140 final, 1 container pair), timing pasted in the DoD.
- [x] Paste the per-layer JaCoCo matrix (core 90.6%/81.0%, infra 80.3%/72.0%) in the DoD.

## 4.2 Boundary gates
- [x] Run `bash scripts/check-boundaries.sh` and record the output → PASS (0 violations).
- [x] Run `bash scripts/check-boundaries.sh --self-test` (plants a temporary violation and
      asserts the gate detects it).
- [x] `ArchUnit` `BoundaryRulesTest` + `BoundaryRulesSelfTestTest` green (Unit Tests job of the CI).
- [x] `mvnw spotless:check` (bound at `validate`) green — no pending formatting.

## 4.3 SSRF, ConfigValidator and Security Headers (Story 4.3)
- [x] `SsrfProtectionIT` covers literal IPv4 IPs, `127.*` loopback, link‑local, ULA/fd00, literal
      IPv6 `[::1]` (8 tests) — landed (the template said "SSRFIT (13)"; real = 8).
- [x] **Gap of this story (closed):** regression test for IPv4‑mapped IPv6
      (`[::ffff:169.254.169.254]` in the parametrized `SsrfProtectionIT`) and a unit case
      `rejectsIpv4MappedMetadataLiteral` in `DefaultUrlValidatorTest` (the CIDR existed, 0 tests).
- [x] `ProdConfigValidatorIT` (5/5): missing / short / default secret → fail‑fast; strong secret +
      full config → passes; non‑prod ignores.
- [x] `SecurityHeadersIT` (3/3): `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
      `Referrer-Policy: strict-origin` on representative routes.
- [x] Run `./mvnw test -Dtest='SsrfProtectionIT,ProdConfigValidatorIT,SecurityHeadersIT,DefaultUrlValidatorTest'`
      → 35 green (14+5+3+13).
- [x] Paste the output in the DoD.

## 4.4 Validate "frozen" metrics and health checks (Story 4.4)
- [x] `scripts/check-metrics-frozen.sh` freezes the **24 business series** (docs/slos.md §2) —
      `MetricsConfig`/`dargent_*` does not exist in this repo (real code: `MicrometerMetricsAdapter`).
- [x] Run `check-metrics-frozen.sh` + `--self-test` → PASS.
- [x] Run `promtool check rules`, `promtool test rules`, `promtool check config` → green.
- [x] Run `amtool check-config` → green.
- [x] Run `ProductionLockdownIT` → green (7/7; tiered liveness/readiness, `show-details:
      when-authorized`).
- [x] Run `bash scripts/debug-health.sh` → readable output with a recommended action (exit 0).
- [x] Paste the outputs in the DoD.

## 4.5 Traceability to `AGENTS.md` and `handoff-dod.md` (Story 4.5)
- [x] `scripts/check-doc-sync.sh` PASS validates pointers (`AGENTS.md` debt statuses, `lessons ↔
      coding-standards`).
- [x] Rule zero guaranteed: DoD evidence has a `gh run list` pair + pasted output (self-audit [x]).
- [x] No hypothesis without a label in the DoD (gaps labelled, e.g.: debt #26).
- [x] Paste the `check-doc-sync.sh` output in the DoD.

## 4.1 (continuation) Integrate into the CI (GitHub Actions) — existing `ci.yml`
- [x] The **`ci.yml`** workflow (5 jobs) already runs unit + IT + gates — do not duplicate as `test.yml`:
      - `Unit Tests`: unit + ArchUnit + `check-boundaries.sh` (+ self‑test) + `check-doc-sync.sh`
        (+ self‑test).
      - `Observability Gate (Epic 3)`: promtool 3.3.0 / amtool 0.28.1 pinned, check/test rules,
        check config, amtool check‑config, `check-metrics-frozen.sh` (+ self‑test).
      - `Integration Tests`: `*IT` with Testcontainers (failsafe).
      - `Security Gate`: `check-security.sh` (+ self‑test), OWASP Dependency‑Check (NVD mirror).
      - `Build`: jar.
- [x] CI evidence in the DoD: run 34585182724/bfd1253, 5/5 jobs success.
- [x] Required CI: failure in any job blocks merge (green workflow in the flip).

## 4.6 Backward‑compatible integration with EP1–EP3
- [x] Combined `./mvnw verify` → BUILD SUCCESS (03:09 min; unit 271 + IT 140).
- [x] Metric `security_ssrf_blocked_total` covered by `MetricsIT.playbackExportsEpic2BusinessSeries`
      (frozen series; the scrape assertion includes all EP2 series).
- [x] 24 "frozen" series without collision — docs/slos.md §2 + `check-metrics-frozen.sh` PASS.
- [x] Paste a snippet of `./mvnw verify` in the DoD.

---

**Epic 4 completion checklist:**

- [x] Test pyramid documented (`testing-playbook.md` §1–2) and `./mvnw test`/`./mvnw verify`
      green
- [x] Boundary gate (`check-boundaries.sh` + `--self-test` + ArchUnit) → PASS
- [x] `SsrfProtectionIT` (14, with IPv4‑mapped), `ProdConfigValidatorIT` (5), `SecurityHeadersIT` (3)
      → green
- [x] "Frozen" metrics (24 series) + `promtool` + `amtool` → green
- [x] `ProductionLockdownIT` (7/7) + `debug-health.sh` → green
- [x] `check-doc-sync.sh` → PASS
- [x] `ci.yml` green (5/5 jobs) in the flip push
- [x] Backward‑compatible integration with EP1–EP3 green (`./mvnw verify`)
- [x] Full `./mvnw verify` green (unit 271 + IT 140 + gates)

*By marking all the items above, Epic 4 is **complete** and the next epic (EP5 – Performance)
can begin.*