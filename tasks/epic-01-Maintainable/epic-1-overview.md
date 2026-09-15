# Epic 1: Maintainable – Foundation & Patterns

**Project:** url-shortener-service  
**Context:** Java 25, Spring Boot 4.1.1, Hexagonal Architecture (Ports & Adapters), MongoDB, Redis  
**Objective:** Consolidate the project foundation so that all future deliverables respect clear contracts, shared conventions and verifiable code structure, avoiding rework and architectural decay.

---

## Why this epic first?

Maintainability is the foundation upon which all other pillars are built. Without a common standard defined now, the following problems multiply in subsequent iterations:

- Cross imports of `infra.*` in `core/` (violating the hexagonal rule) → requiring later refactoring in EP2/EP3.
- Undefined logging, naming and error conventions → blind tests and log noise (EP3).
- Technical debt matrix disconnected from the code documents → onboarding and audit difficulty (EP7/EP8).

**Relationship with other epics:**

| Epic | Direct dependency |
|--------|-------------------|
| EP2 – Secure | Logging conventions (`logSafe`), class and DTO names; input validation rules. |
| EP3 – Observable | Prometheus metrics format, `dargent_*` series, MDC structure and correlation‑id. |
| EP4 – Testing | Test stories derived from the *stories* below; coverage rule per module. |
| EP5 – Performance | Bottlenecks identified only after the code is within stable conventions. |
| EP6 – Scalable | Front‑door (NGINX, rate‑limiter) depends on endpoint and payload names defined here. |
| EP7 – Reliable | Circuit‑breaker and bulkhead names aligned with the defined packages and exceptions. |
| EP8 – Deployable | Docker images, tags and CI scripts are based on the stable packages and configurations. |

**Elevated Acceptance Criteria:**

1. `bash scripts/check-boundaries.sh` → **PASS** (0 violations)  
2. `bash scripts/check-boundaries.sh --self-test` → **PASS** (gate self‑verifies).  
3. All `@Component/@Service/@Repository` annotations removed from `core/`; beans registered via `infra/config/ServiceConfig`.  
4. `lessons.md` → `coding-standards.md` promotions completed; lessons repeated > 2 migrated, removed from the pending list.  
5. `core/` package free of wildcard imports; all imports are explicit and ≤ 3 lines.  
6. Technical debt matrix in `AGENTS.md` synced: `open/in-progress/resolved` status for each item, with a forecast date.  
7. `./mvnw compile` + `./mvnw spotless:check` → **green**; `./mvnw spotbugs:check` → 0 new bugs; ArchUnit boundary tests → green.

**Traceability:**

| Story | Reference document | Agents/AGENTS.md |
|-------|------------------------|-------------------|
| 1.1 | `core/` free of `infra.*` imports | Rule 1 (Boundary Architecture) |
| 1.2 | `lessons.md` → `coding-standards.md` promotion | Rule 10 (Doc Sync is Part of Done) |
| 1.3 | `core/model/`, `core/ports/{incoming,outgoing}/`, `infra/adapter/` packages defined | Rule 9 (Namig & Structure) |
| 1.4 | Removal of `unused` classes, English javadocs, "why" comments | Rule 1 (Coding Conventions) |
| 1.5 | `AGENTS.md` ↔ `lessons.md` ↔ `coding-standards.md` synced | Rule 10 + Rule 11 (newly added) |

--- 

*Next step: review `core/` with `check-boundaries.sh` and promote the first repeated lesson to `coding-standards.md`.*