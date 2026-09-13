# Epic 4 – Stories (Acceptance)

**Landing:** the ACs below reflect the real repo state (Java 25 / Boot 4.1.1, baseline suite
270 unit / 139 IT, 24 frozen series). Cited test names = real classes
(`SsrfProtectionIT`, `ProdConfigValidatorIT`, `SecurityHeadersIT`).

| # | Story | Acceptance Criteria | Reference / Anchor |
|---|-------|------------------------|----------------------|
| **4.1** | **Test pyramid** – keep the clear division between unit (`*Test`, no Docker), slices (`@WebMvcTest`) and integration (`*IT`, Testcontainers singleton via `BaseIntegrationTest`). | • `./mvnw test` (unit + slices) runs green **without Docker** (baseline 270). <br>• `./mvnw test -Dtest='*IT'` → (Testcontainers) a single MongoDB + Redis pair for the whole suite (singleton pattern, without `@DirtiesContext`). <br>• `./mvnw verify` → unit + IT + E2E in sequence (failsafe). <br>• JaCoCo matrix: BUNDLE LINE/BRANCH ≥ 60% and `core.*` LINE/BRANCH ≥ 70%. | `docs/testing-playbook.md` §1–2 (stratification already landed) |
| **4.2** | **Boundary gates** – boundary gate green in every PR; ArchUnit prevents `infra.*` imports in `core/`. | • `bash scripts/check-boundaries.sh` → PASS (0 violations). <br>• `bash scripts/check-boundaries.sh --self-test` → PASS (gate self-verifies). <br>• `ArchUnit BoundaryRulesTest` + `BoundaryRulesSelfTestTest` green in the Unit Tests job. | Rule 1 of `AGENTS.md`; `scripts/check-boundaries.sh`; `ArchUnit` |
| **4.3** | **SSRF, validators and headers** – `SsrfProtectionIT` (8 tests, +1 IPv4‑mapped IPv6 in this story), `ProdConfigValidatorIT` (5/5) fail‑fast, `SecurityHeadersIT` (3/3) HTTP headers. | • `./mvnw test -Dtest='SsrfProtectionIT,ProdConfigValidatorIT,SecurityHeadersIT'` → all green. <br>• Output pasted in the handoff‑DOD. <br>• IPv6: bracket `[::1]` classified as loopback; **gap closed**: CIDR `::ffff:169.254.169.254/128` (IPv4‑mapped) with a regression test. | Epics 2 stories 2.2‑2.5; `DefaultUrlValidator` |
| **4.4** | **Frozen metrics + health checks + diagnosis** – 24 business series frozen; tiered actuator; operational triage. | • `bash scripts/check-metrics-frozen.sh` (+ `--self-test`) → PASS. <br>• `promtool check rules`, `promtool test rules`, `promtool check config`, `amtool check-config` → green. <br>• `ProductionLockdownIT` (7/7) → tiered liveness/readiness. <br>• `bash scripts/debug-health.sh` → readable output with a recommended action. | Epic 3 story 3.7; `docs/slos.md` §2; `deploy/monitoring/` |
| **4.5** | **AGENTS.md / DoD traceability** – every number, sha or count in `epic-4-dod.md` has a corresponding pair in `gh run list` and pasted command output; no unlabelled hypothesis. | • `bash scripts/check-doc-sync.sh` (+ `--self-test`) → PASS. <br>• 5/5 stories with direct traceability to `AGENTS.md` (debt matrix) and to `epic-4-dod.md`. <br>• CI: `ci.yml` (5 jobs) green in the flip push — AC fulfilled by the existing CI (no separate `test.yml` workflow, avoiding duplication). | Rule zero of `handoff-dod.md`; `scripts/check-doc-sync.sh` |

---

**Quick traceability:**

| Story | Reference doc | AGENTS.md |
|-------|----------------|-----------|
| 4.1 | `docs/testing-playbook.md` §1–2 | Test pyramid (`## 🧪 Testing Strategy`) |
| 4.2 | `scripts/check-boundaries.sh`, `ArchUnit` | Rule 1 |
| 4.3 | `AGENTS.md`, `DefaultUrlValidator` | Rules 2‑6 (SSRF) + rule zero |
| 4.4 | `docs/slos.md` §2, `deploy/monitoring/` | `scripts/check-metrics-frozen.sh` (debts #24, #27) |
| 4.5 | `handoff-dod.md`, `ci.yml` | Rule zero; `## 🛠️ Commands Matrix` |

---

*Execution: `tasks/epic-4/epic-4-technical-tasks.md`; evidence in `tasks/epic-4/epic-4-dod.md`.*