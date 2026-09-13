# Epic 1 – Testing Strategy

This epic focuses on the foundation; therefore the tests here are about **structure checking** and **convention validation**, not business logic (that responsibility comes in the following epics).

## 1.1 Boundary unit tests (boundary checks)
- **Objective:** Confirm that `check-boundaries.sh` reports 0 violations.
- **Action:** 
  - `./mvnw test` (only unit tests of `core/`), without Docker.
  - Verify the script output and capture the return code.
- **Acceptance criteria:** Script returns 0 and prints "PASS".

## 1.2 Package integration tests (`*Test`)
- **Objective:** Ensure that the package reorganization does not break compilation or the unit tests.
- **Action:** 
  - `./mvnw test -Dtest='*Test'` (class ending in `Test`, not `IT`).
  - Verify JaCoCo coverage per module (`core` ≥ 70 % line, if the floor is raised after measurement — current gate: LINE ≥ 60% global).
- **Acceptance criteria:** Coverage not below the defined value; `./mvnw verify` green.

## 1.3 Debt matrix self‑audit
- **Objective:** Validate that `AGENTS.md` ↔ `lessons.md` ↔ `coding-standards.md` are aligned.
- **Action:** 
  - Simple script (bash/python) that extracts `status:` labels from each file and compares them.
  - If there is divergence, the script fails and prints the differences.
- **Acceptance criteria:** "Synced" output or list of divergences to fix before closing the epic.

## 1.4 Documentation as code (Handoff‑DOD)
- **Objective:** Ensure that every completion report of this epic follows the *zero‑from‑memory* rule of `handoff-dod.md`.
- **Action:** 
  - When generating the epic summary, always paste real commands (`git log`, `git status`, `gh run list`, Surefire counts).
  - Mark any number or sha that comes from memory as **Hypothesis (TD‑13)**.
- **Acceptance criteria:** The handoff evidence block contains only pasted outputs; no line "I think it was …".

## 1.5 Continuous integration (CI)
- **Objective:** Every *push* to `main` validates maintainability before allowing merge.
- **Pipeline (summary):**
  - `build` → `./mvnw test` + `./mvnw verify` (includes ArchUnit, SpotBugs, JaCoCo).
  - `boundary-gate` → run `scripts/check-boundaries.sh` (with `--self-test`).
  - `doc-sync` → verification of lesson promotion and matrix sync.
- **Acceptance criteria:** Green pipeline on branch `main`; failure in any of the gates blocks merge.

--- 

**Post‑Epic 1:** All the tests above are part of the `./mvnw verify` gate from now on; any new story that touches `core/` must pass the boundary checks before being merged.