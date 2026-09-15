# Epic 1 – Stories (Acceptance)

| # | Story | Acceptance Criteria | Agile Reference / Anchor |
|---|-------|------------------------|--------------------------|
| **1.1** | **Formalized hexagonal architecture** – move all `@Component/@Service/@Repository` annotations from `core/` to `infra/`; create `ServiceConfig` registering domain‑only beans. | • `bash scripts/check-boundaries.sh` → **PASS** (0 violations) <br>• `bash scripts/check-boundaries.sh --self-test` → **PASS** (gate self‑verifies) <br>• ArchUnit tests green and free of `infra.*` imports in `core/*.java` | Rule 1 of AGENTS.md (Boundary Architecture) |
| **1.2** | **Promotion of repeated lessons** – review `lessons.md`; every pattern that appeared 3 times migrates to `coding-standards.md` and is removed from the lessons. | • `git diff lessons.md` → lessons > 20 → promoted to `coding-standards.md` <br>• `coding-standards.md` updated with the new rules <br>• No repeated lesson remains without action | Rule 10 of AGENTS.md (Doc Sync is Part of Done) |
| **1.3** | **Standardization of names & packages** – current hexagonal layout verified and documented: `core/model/`, `core/ports/incoming/`, `core/ports/outgoing/`, `infra/adapter/{input,rest}/`, `infra/config/` (canonical layout of AGENTS.md — no renames for the names diverging from the original template). | • Whole-base search: no `package` loose outside the defined folders <br>• `./mvnw compile` green <br>• IDE (IntelliJ/Eclipse) can resolve all imports automatically | Rule 9 of AGENTS.md (Naming & Structure) |
| **1.4** | **Deprecation of legacy code** – remove `unused` classes, English javadocs, "why" comments replacing "what". | • `./mvnw spotless:check` → **PASS** <br>• SpotBugs 0 new bugs <br>• `git diff --stat` shows only removals/cleanups | Rule 1 of AGENTS.md (Code Conventions) |
| **1.5** | **Updated technical debt matrix** – `AGENTS.md` ↔ `lessons.md` ↔ `coding-standards.md` synced; status of each item (open/in-progress/resolved). | • Manual review + checking script <br>• All items have a defined status <br>• No "pending" without a forecast date | Rule 10 + Rule 11 of AGENTS.md (Doc Sync + Lessons Sync) |

---

**Quick traceability:**

| Story | Reference doc | AGENTS.md |
|-------|----------------|-----------|
| 1.1 | `check-boundaries.sh` output | Rule 1 |
| 1.2 | `lessons.md` → `coding-standards.md` diff | Rule 10 |
| 1.3 | Project folder structure | Rule 9 |
| 1.4 | `spotless:check` + SpotBugs | Rule 1 |
| 1.5 | `AGENTS.md` ↔ `lessons.md` ↔ `coding-standards.md` diff | Rule 10 + Rule 11 |