# Epic 4 – Testing Strategy

**Real context:** Java 25 / Spring Boot 4.1.1 / Testcontainers 2.0.5 / JUnit 5 + Mockito +
RestAssured 6.0.1 + ArchUnit. Complete stratification documented in
`docs/testing-playbook.md` §1–2 and §Placement — single source, already landed.

## 4.1 Test pyramid (unit / slice / IT)

- **Objective:** confirm the stratification works as expected and is fast in the dev loop.
- **Action:**
  - `./mvnw test` (unit `*Test` + slices `@WebMvcTest`; **no Docker**) — measure time, paste in the DoD.
  - `./mvnw test -Dtest='*IT'` (Testcontainers: MongoDB + Redis **singleton** via
    `BaseIntegrationTest`, no `@DirtiesContext`) — measure time, paste in the DoD.
  - Verify JaCoCo: BUNDLE LINE/BRANCH ≥ 60%, PACKAGE `core.*` LINE/BRANCH ≥ 70% (check bound
    at `verify`; report pasted).
- **Acceptance criterion:** deterministic suite; coverage floors respected; timings pasted.

## 4.2 Boundary gates

- **Objective:** confirm that `core/` never depends on `infra/` (Rule 1 of AGENTS.md).
- **Action:**
  - `bash scripts/check-boundaries.sh` → PASS (0 violations).
  - `bash scripts/check-boundaries.sh --self-test` → PASS (plants a temporary violation and catches it).
  - `ArchUnit`: `BoundaryRulesTest` + `BoundaryRulesSelfTestTest` green (Unit Tests job of the CI).
  - `./mvnw spotless:check` → no pending formatting.
- **Acceptance criterion:** all PASS; outputs pasted in the DoD.

## 4.3 SSRF, ConfigValidator and Security Headers

- **Objective:** validate the stories brought from Epic 2 with real names/counts.
- **Action:**
  - `SsrfProtectionIT` — 8 tests (literal IPv4 IPs, loopback, link‑local, ULA/fd00, literal IPv6
    `[::1]`) + **1 new test of this story**: IPv4‑mapped IPv6 `https://[::ffff:169.254.169.254]/`
    (regression of the CIDR that already exists in the validator). Corresponding unit case in
    `DefaultUrlValidatorTest`.
  - `ProdConfigValidatorIT` — 5/5: missing / short / default secret → fail‑fast; strong secret +
    full config → passes; non‑prod ignores.
  - `SecurityHeadersIT` — 3/3: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
    `Referrer-Policy: strict-origin`.
  - `./mvnw test -Dtest='SsrfProtectionIT,ProdConfigValidatorIT,SecurityHeadersIT'` → all three
    green; output pasted.
- **Acceptance criterion:** all green; output pasted; IPv6 brackets and IPv4‑mapped covered.

## 4.4 "Frozen" Metrics and Health Checks

- **Objective:** confirm that the **24 business series** (docs/slos.md §2) are frozen and the
  tiered health checks work.
- **Action:**
  - `bash scripts/check-metrics-frozen.sh` (+ `--self-test`) → PASS.
  - `promtool check rules`, `promtool test rules`, `promtool check config` → green
    (pinned: Prometheus 3.3.0).
  - `amtool check-config` → green (pinned: Alertmanager 0.28.1).
  - `./mvnw test -Dtest='ProductionLockdownIT'` → green (7/7).
  - `bash scripts/debug-health.sh` → readable output with a recommended action.
- **Acceptance criterion:** all checks green; output pasted.

## 4.5 Traceability and CI

- **Objective:** ensure Rule zero (zero‑from‑memory) and merge blocking on any red.
- **Action:**
  - `bash scripts/check-doc-sync.sh` (+ `--self-test`) → PASS.
  - Existing CI **`ci.yml`** workflow (5 jobs; without duplicating as `test.yml`):
    Unit Tests → Observability Gate (Epic 3) → Integration Tests → Security Gate → Build.
    Runs unit, ArchUnit, boundaries, doc‑sync, promtool/amtool, `check-metrics-frozen.sh`,
    `*IT`/failsafe, `check-security.sh`, OWASP, jar.
  - CI evidence: `gh run list` + job conclusions of the flip (run on the flip tree).
- **Acceptance criterion:** green gates; any failure blocks merge.

## 4.6 Backward‑compatible integration (EP1–EP3)

- **Objective:** ensure that existing security metrics and logs continue to work.
- **Action:**
  - Combined `./mvnw verify` → green.
  - `security_ssrf_blocked_total` series incremented — covered by
    `MetricsIT.playbackExportsEpic2BusinessSeries`.
  - 24 series without collision (docs/slos.md §2).
- **Acceptance criterion:** green test and pasted output.

---

**Epic 4 completion checklist:**

- [x] `./mvnw test` → green (271 unit + slices, no Docker)
- [x] `./mvnw test -Dtest='*IT'` → green (140 IT, Testcontainers singleton)
- [x] `./mvnw verify` → green (JaCoCo, SpotBugs, ArchUnit, OWASP, metrics‑frozen, promtool, amtool)
- [x] `check-boundaries.sh` + `--self-test` + ArchUnit → PASS
- [x] `SsrfProtectionIT` (14), `ProdConfigValidatorIT` (5), `SecurityHeadersIT` (3) → green
- [x] `check-doc-sync.sh` → PASS
- [x] `ci.yml` green (5 jobs) in the flip push
- [x] Backward‑compatible integration with EP1–EP3 green

*By marking all the items above, Epic 4 is **complete** and the next epic (EP5 – Performance)
can begin.*