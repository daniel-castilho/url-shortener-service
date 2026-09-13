# Epic 1 – Technical Tasks

## 1.1 Audit and fix the `core/` boundaries
- [x] Run `bash scripts/check-boundaries.sh` and record the output. _(2026‑09‑10: PASS — 0 violations)_
- [x] Run `bash scripts/check-boundaries.sh --self-test` (plants a temporary violation and asserts the gate catches it). _(2026‑09‑10: PASS — gate detects the planted violation and accepts clean code)_
- [x] Fix any `infra.*` imports found in `core/*.java`:
    - Move the dependency to `infra/` behind the corresponding outbound port.
    - Or create an abstract port in `core/` and an implementation in `infra/`.
    - _(N/A — 2026‑09‑10 audit: no `infra.*`/framework imports in `core/`; historical debt already resolved in AGENTS.md matrix items 1–2)_
- [x] Confirm that `./mvnw compile` is still green after each change. _(2026‑09‑10: BUILD SUCCESS)_
- [x] Confirm that `./mvnw spotless:check` remains green. _(2026‑09‑10: exit 0)_
- [x] Encode Rule 1 as ArchUnit tests (deliberate redundancy with the script: bytecode vs grep). _(BoundaryRulesTest 2/2 + BoundaryRulesSelfTestTest 2/2 green)_

## 1.2 Promote repeated lessons to `coding-standards.md`
- [x] Run a script or manual review that counts occurrences of each lesson in `lessons.md`. _(2026‑09‑10: grep by pattern — fail-open/best-effort/degrade 4×; atomic/$inc/read-modify-write 3×)_
- [x] Identify lessons with count ≥ 3. _(2 patterns: deliberate fail-open/fail-fast degradation; atomic mutation of counters)_
- [x] Copy the pattern (excerpt + golden rule) to `coding-standards.md` under a new section "Inherit Lessons". _(§14 Inherit Lessons: §14.1 + §14.2)_
- [x] Remove the promoted lesson from `lessons.md` (or mark it as `→ coding-standards`). _(3 markings: Metrics/counters §14.2, Caching/bloom §14.1, Fail-open-vs-fail-fast §14.1, OTLP §14.1)_
- [x] Update the debt matrix in `AGENTS.md` with the new reference. _(Item 22 added, status `resolved`)_

## 1.3 Standardize packages and class names
- [x] List all current packages under `src/main/java`. _(2026‑09‑10: 30 packages — all under `core/` or `infra/`)_
- [x] Verify adherence to the canonical AGENTS.md layout (no renames — the current layout is the source of truth):
    - `core/model/`
    - `core/ports/incoming/`
    - `core/ports/outgoing/`
    - `core/service/`
    - `infra/adapter/input/rest/`
    - `infra/adapter/output/persistence/`
    - `infra/adapter/output/redis/`
    - `infra/config/`
    - _(Verified: also canonical are the subpackages `core/command`, `core/exception`, `core/idgeneration`, `core/validation`, `infra/adapter/output/{analytics,dns,security,validation}`, `infra/config/properties`, `infra/observability`, `infra/security`)_
- [x] Confirm that no `package` is loose outside the defined folders. _(The only package in the root `ca.tyny.urlshortener` is the `Application.java`, canonical entry point)_
- [x] Confirm that `./mvnw compile` and `./mvnw test` are still green. _(2026‑09‑10: compile BUILD SUCCESS; test 269/269)_

## 1.4 Remove legacy code and "why" comments
- [x] Run `./mvnw spotless:check` in *check* mode to identify files that do not follow Google Java Style (4‑space, 120‑col). _(Base normalized via `spotless:apply` — dedicated style commit; check green)_
- [x] Identify `unused` classes (via `./mvnw dependency:tree -uf` or manual removal). _(Reverse-reference scan: 23 candidates were Spring beans by component‑scan (configs/controllers/adapters/migrations) — false positives; 0 real unused classes)_
- [x] Remove unnecessary classes/files; confirm there is no side effect. _(N/A — nothing to remove; 0 TODO/FIXME/System.out/@Deprecated)_
- [x] Rewrite comments that explain *what* the code does to explain *why* the decision was made (e.g.: "we use Instant instead of Date to avoid accidental timezone in TTL generation"). _(Scan in `core/`: 1 real "what" comment (`QuotaUsage` "// Getters and Setters") rewritten as "why")_
- [x] Confirm `./mvnw spotless:check` green. _(2026‑09‑10: exit 0)_

## 1.5 Sync the technical debt matrix
- [x] Ensure that each item in `AGENTS.md` "Known Technical Debt" has a `status: open/in-progress/resolved` field and a forecast date. _(2026‑09‑10: 22 items, all `resolved` with evidence; verified by the gate)_
- [x] Cross‑check with `lessons.md`: every promoted lesson must have its trace in the matrix. _(4 markings `→ coding-standards §14.x` point to existing sections — verified by the gate)_
- [x] Keep `resolved` items in the matrix as an audit trail (Rule 10 policy of AGENTS.md); just ensure each item has correct status and date. _(Owner decision: keep; "remove resolved" template rejected)_
- [x] Commit the changes in `AGENTS.md` and send a PR with label `docs: sync technical debt matrix`. _(Commits per phase per the agreed flow; PR only under explicit request)_
- [x] New gate: `scripts/check-doc-sync.sh` (+ `--self-test`) — validates matrix status and promotions; wired in CI (job `doc-sync`).

--- 

**Epic 1 completion checklist:**

- `check-boundaries.sh` → PASS (0 violations)  
- `lessons.md` → promoted to `coding-standards.md`  
- `coding-standards.md` → latest versioning with all new rules  
- `AGENTS.md` → debt matrix synced, status updated  
- `./mvnw verify` (full gate) → all sub‑gates green (unit, SpotBugs, JaCoCo, ArchUnit)  

*Next epic: EP2 – Secure (security by design).*