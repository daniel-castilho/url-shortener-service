# Foundation (Identity Model) — Backlog
## Random Base62 codes, no dedup, namespace isolation & quality gate

**Priority:** P0 — architecture foundation. First epic.
**All stories:** Must.
**Companions:** `foundation-identity-model-spec.md` · `foundation-identity-model-implementation-sequence.md`

**Execution status:** completed — all stories landed (G0–G2, I1–I7, V1–V2). Story checkboxes below are updated
per phase.

---

## Epic outcome

The codebase converges on the locked identity model: short codes are cryptographically random Base62
(7 chars), the same URL can be shortened multiple times (no dedup), generated codes and vanity aliases
occupy disjoint namespaces, `409` means only "alias already exists", and the change is delivered behind
a working quality gate (failsafe + CI + boundary check).

---

## Story map

```text
QUALITY GATE (prerequisite)
G0   Baseline, dependency and schema plan
G1   Failsafe + rename *IT + CI
G2   Architecture boundary gate

IDENTITY MODEL
I1   Base62 code generator (SecureRandom) + collision retry seam
I2   Remove Hashids/counter/RangeAwareIdGenerator/SHORTENER_SALT
I3   RandomUrlIdStrategy wiring + namespace isolation
I4   Remove URL dedup (drop unique index) + urlHash
I5   Semantics: 409 = alias exists only; duplicate URL allowed
I6   Code-length + index/config/migration management
I7   Fix native mainClass + remove dead metrics

VERIFICATION & DELIVERY
V1   Collision/namespace/dedup test matrix
V2   Documentation & provisioning sync
```

---

## G0 — Baseline, dependency and implementation preconditions

**Goal:** do not change the identity model on an unknown/red baseline.

### Work

- verify current `main` is clean and the existing tests pass (or record blockers);
- confirm the current ID path: `RangeAwareIdGenerator` (Redis counter + Hashids), `ShortCodeConfig`,
  `ShortUrlEntity.originalUrl` unique index;
- confirm the `core` boundary is enforced for the shortening flow, and locate the `core/service/UserService`
  infra leak (debt item 1, tracked separately/parallel);
- confirm the `native` profile `mainClass` is wrong (points at `.infra.Application`);
- record the schema/index rollout order (drop `originalUrl` unique; keep `_id`, `userId`);
- confirm no new Maven coordinate is required for the identity change (Hashids removal is a removal;
  a migration runner is optional and needs approval).

### Acceptance

- [ ] Baseline commands pass or blockers are reported before feature edits.
- [ ] Current ID/counter path and the unique-on-URL index are confirmed against the spec.
- [ ] No unresolved "or/equivalent/replace" choice remains for the generator.
- [ ] Dependency approach is approved and recorded (Hashids removal; no new coordinate without approval).

---

## G1 — Failsafe, `*IT` rename and CI

**Goal:** make the integration suite runnable as a gate, decoupled from the fast loop.

### Work

- add `maven-failsafe-plugin` so `*IT` runs during `mvn verify`;
- rename the integration test classes `*IntegrationTest` → `*IT` (and the test-suite selector);
- confirm `mvn test` (fast, no Docker) does not run `*IT`;
- add `.github/workflows/ci.yml` running `mvn test`, `mvn test -Dtest='*IT'` and `mvn clean package`.

### Acceptance

- [ ] `mvn verify` runs the integration suite (needs Docker).
- [ ] `mvn test` is fast and Docker-free.
- [ ] CI workflow is committed and runs both stages.

---

## G2 — Architecture boundary gate

**Goal:** ensure the identity refactor and later work cannot reintroduce infra into `core`.

### Work

- add the two `grep` checks from `AGENTS.md` rule 1 as a script and a CI step;
- run them on `core/` and assert zero matches;
- add to the pre-commit/CI so it fails the build on a violation.

### Acceptance

- [ ] Boundary grep returns 0 matches on `core/`.
- [ ] The check is committed and invoked in CI.

---

## I1 — Base62 code generator (SecureRandom) with collision retry seam

**Goal:** produce random, collision-safe codes in `core`, framework-free.

### Work

- add a pure Base62 alphabet constant and a `Base62CodeGenerator` (uses `java.security.SecureRandom`);
- add configurable length (`app.shortener.code-length`, default 7) with validation (≥ 6);
- define the collision-retry seam: retry on `_id` `DuplicateKeyException`, bounded attempts, domain error
  on exhaustion;
- keep `IdGeneratorPort.generateId()` as the abstraction; `RandomUrlIdStrategy` uses it.

### Acceptance

- [ ] Codes are length `n`, from the exact 62-char alphabet.
- [ ] Generated with `SecureRandom`; never a counter or `java.util.Random`.
- [ ] Collision triggers a bounded retry and a domain error if exhausted; never reuses a code.
- [ ] Unit test covers length, alphabet and finite-bounded retry behaviour.

---

## I2 — Remove Hashids / counter / `RangeAwareIdGenerator` / `SHORTENER_SALT`

**Goal:** remove the reversible, counter-coupled scheme.

### Work

- remove `org.hashids` dependency (and its `ShortCodeConfig` bean);
- remove `RangeAwareIdGenerator` and the Redis `SEQUENCE_KEY` counter path;
- remove `SHORTENER_SALT` / `app.shortener.salt` from config;
- ensure `IdGeneratorPort` has a single, pure implementation.

### Acceptance

- [ ] No `hashids` import/bean/coordinate remains.
- [ ] No Redis counter path for code generation; `IdGeneratorPort` is pure.
- [ ] `SHORTENER_SALT` is gone from config/docs.

---

## I3 — `RandomUrlIdStrategy` wiring + namespace isolation

**Goal:** generated codes and vanity aliases are structurally disjoint.

### Work

- wire `RandomUrlIdStrategy` as the default when no alias; keep `VanityUrlIdStrategy` for aliases;
- keep `ReservedWordsValidator`; reject reserved words as alias/code;
- enforce structural separation: generated codes are exactly `code-length` chars from the Base62
  alphabet; vanity aliases follow a different shape (per-plan min length and/or `-`/`_` set) so the
  two sets cannot overlap;
- rely on the atomic `_id` insert for alias uniqueness (no check-then-put race).

### Acceptance

- [ ] No generated code equals a reserved word.
- [ ] No vanity alias can equal a valid generated code (shape disjoint).
- [ ] Concurrent duplicate alias resolves to one `409`.
- [ ] `CompositeUrlIdGenerator` selects the right strategy.

---

## I4 — Remove URL dedup (drop unique index) + `urlHash`

**Goal:** same URL can be shortened many times; no 1:1 mapping.

### Work

- remove `@Indexed(unique = true)` from `ShortUrlEntity.originalUrl`;
- add `urlHash` (SHA-256 of the original URL, lowercase hex) for future analytics — no unique index;
- add an index-management/migration step to drop the existing unique index (deterministic, versioned);
- confirm the `_id` stays the unique key for code resolution.

### Acceptance

- [ ] `originalUrl` is no longer `UNIQUE`; a duplicate URL creates a new code.
- [ ] `urlHash` is written on save; no unique index on it.
- [ ] Existing unique index is dropped via a versioned/migration step (not auto-index-creation).

---

## I5 — Semantics: `409` = alias only; duplicate URL allowed

**Goal:** correct the misleading conflict and the duplicate-URL path.

### Work

- ensure a duplicate original URL does **not** raise `AliasAlreadyExistsException`/`409`; it
  shortens to a new code and returns `200`;
- keep `409` for custom alias already exists;
- fix the error message so it never implies "URL already shortened".

### Acceptance

- [ ] Duplicate URL → 200 with a distinct code.
- [ ] Reserved/invalid/repeated custom alias → 400/409 as specified.
- [ ] No confusing "URL already shortened" messaging remains.

---

## I6 — Config, index & migration management

**Goal:** deterministic, configurable code length and index changes.

### Work

- add `app.shortener.code-length` (default 7) with validation;
- replace `auto-index-creation` with a committed, versioned, idempotent index/migration step (or a
  migration runner with approval);
- keep `userId` index; drop the `originalUrl` unique index.

### Acceptance

- [ ] `code-length` is configurable and validated.
- [ ] Index changes are applied via a committed migration, not recreated on every startup.
- [ ] A fresh environment and an existing environment give identical index states.

---

## I7 — Fix native `mainClass` + remove dead metrics

**Goal:** repair the native build and drop never-recorded metrics.

### Work

- change the `native` profile `mainClass` to `ca.tyny.urlshortener.Application`;
- remove (or wire) the dead `id.generation.duration` / `url.retrieval.duration` timers in
  `MetricsService`;
- ensure no metric is registered but never recorded.

### Acceptance

- [ ] `mvn clean package -Pnative` resolves the correct main class.
- [ ] No dead metric timers remain; all registered metrics are written.

---

## V1 — Collision / namespace / dedup test matrix

**Goal:** prove the identity model under retries, duplicates and concurrency.

### Work

- unit: code length/alphabet, bounded collision retry, `urlHash`;
- unit: namespace isolation (generated vs. reserved word vs. vanity alias);
- adapter (`*IT`): duplicate original URL → new code; concurrent duplicate alias → one `409`;
- security: no infra import; boundary grep green;
- run full `mvn verify` + `*IT`.

### Acceptance

- [ ] Every scenario has a named test that pins behaviour.
- [ ] `mvn test`, `mvn verify` and the boundary check pass.
- [ ] No forbidden `core` import is introduced.

---

## V2 — Documentation & provisioning sync

**Goal:** docs converge on the new identity model.

### Work

- `README.md` current state / tech stack (remove Hashids; state no-dedup);
- `AGENTS.md` clear resolved debt items (3, 4, 7, 11, 13);
- `docs/data-model-decisions.md` apply the Base62 / no-dedup / namespace decisions;
- `docs/coding-standards.md` update ID/index/namespace rules;
- `docs/testing-playbook.md` update suite map + commands (`*IT`);
- `docs/lessons.md` note the durable lessons.

### Acceptance

- [x] No document describes counter+Hashids or the unique-on-URL dedup as current.
- [x] `AGENTS.md` debt matrix reflects the resolved items; new debt is flagged.
- [x] A new contributor/agent can follow the locked identity model from docs alone.

---

## Epic Definition of Done

- [ ] G0–G2 (quality gate) complete: fillsafe integration gate, CI, boundary check.
- [ ] I1–I7 complete: random Base62 codes, Hashids/counter removed, namespace isolated, dedup removed,
  `409` semantics corrected, config/migration deterministic, native build fixed, dead metrics removed.
- [ ] V1–V2 complete: full test matrix green and documentation converged.
- [ ] Full CI mirror and boundary check pass.
- [ ] No `core` import of infra/Spring/Mongo/Redis/JWT.
- [ ] `originalUrl` unique index is gone; `_id` is the identity key.
