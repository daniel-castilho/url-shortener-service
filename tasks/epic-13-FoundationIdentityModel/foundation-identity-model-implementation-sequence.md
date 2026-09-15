# Foundation (Identity Model) — Implementation Sequence
## Random Base62 codes, no dedup, namespace isolation & quality gate

**Companions:** `foundation-identity-model-spec.md` · `foundation-identity-model-backlog.md`
**Rule:** complete each step's acceptance and verification before starting the next. Do not invent
out-of-scope work.

---

## Global execution rules

1. Work in small, reviewable vertical commits; never deliver the epic as one unreviewable change.
2. Read the referenced story acceptance before coding.
3. Add tests with the production change, not at the end.
4. A red baseline or ambiguous locked decision stops work.
5. Every schema/index change updates README/local setup and integration-test provisioning in the same step.
6. Every route/status/contract change updates tests and minimal OpenAPI in the same step.
7. Never add a dependency without explicit approval (removing `hashids` is fine; adding a migration runner
   is not — ask).
8. After each step, update task status and note deviations; do not silently alter the specification.

### Fast verification (throughout)

```bash
mvn test
```

### Integration verification (when hosting/cache/HTTP touched)

```bash
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

### Full gate

```bash
mvn verify
```

### Boundary verification (when `core` changes)

```bash
grep -rEn "import ca\.tyny\.urlshortener\.infra" src/main/java/ca/tyny/urlshortener/core
grep -rlE "org\.springframework|org\.mongodb|org\.redisson|io\.jsonwebtoken|io\.micrometer" src/main/java/ca/tyny/urlshortener/core
```

Expected: no matches.

---

## Step 0 — Baseline, design lock and dependency gate
### Stories: G0

### Actions

1. Confirm HEAD, working tree and current test status.
2. Run the current fast suite and a representative integration test.
3. Compare the current ID path (`RangeAwareIdGenerator`, `ShortCodeConfig`, `ShortUrlEntity` unique
   index) to the spec.
4. Record locked decisions in `docs/data-model-decisions.md`:
   - random Base62 codes, `SecureRandom`, default length 7;
   - no URL dedup (drop the unique index on `originalUrl`);
   - namespace isolation (structural separation + reserved words);
   - `409` = alias conflict only.
5. Confirm `hashids` is the only Maven coordinate to remove and no new coordinate is needed.
6. If a migration runner is wanted (spec §5.4), request approval with exact coordinate/reason/
   alternatives; otherwise implement the index drop as a committed, idempotent step.
7. Plan the index/rollout order: keep `_id`, `userId`; drop `originalUrl` unique.

### Done when

- baseline green or a blocker report is accepted;
- locked decisions match the spec;
- dependency approach approved;
- no unresolved "or/if available" choice remains.

### Verify

```bash
mvn test
```

---

## Step 1 — Quality gate: failsafe, `*IT`, CI, boundary check
### Stories: G1, G2

The identity change must land behind a gate — do this first.

### Actions

1. Add `maven-failsafe-plugin` so `*IT` runs during `mvn verify`.
2. Rename `*IntegrationTest` → `*IT` (and `MongoUserRepositoryTest` is a unit test — keep; only the
   Testcontainers-backed `*IntegrationTest` classes rename).
3. Confirm `mvn test` no longer runs `*IT`; `mvn verify` runs them.
4. Add `.github/workflows/ci.yml`: `mvn test` → `mvn test -Dtest='*IT'` (Docker) → `mvn clean package`.
5. Add the boundary grep as a script and a CI step; assert zero matches on `core/`.

### Done when

- `mvn verify` runs the integration suite; `mvn test` is Docker-free;
- CI workflow committed;
- boundary check fails the build on a violation.

### Verify

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 2 — Random Base62 generator + collision retry seam
### Stories: I1

### Actions

1. Add `Base62CodeGenerator` in `core` using `java.security.SecureRandom` and the 62-char alphabet.
2. Add configurable length (`app.shortener.code-length`, default 7), validated (`>= 6`).
3. Define the collision seam: on `_id` `DuplicateKeyException`, retry a bounded number of times; a
   domain error on exhaustion.
4. Keep `IdGeneratorPort.generateId()` as the abstraction; wire `RandomUrlIdStrategy` to it.
5. Add unit tests for length, alphabet and bounded retry.

Do not yet remove the counter path; introduce the pure generator alongside.

### Done when

- codes are `code-length` chars from the exact alphabet, from `SecureRandom`;
- collision retries are bounded and never reuse a code;
- a domain error is exposed on exhaustion;
- unit tests pass.

### Verify

```bash
mvn test
```

---

## Step 3 — Remove Hashids / counter / salt
### Stories: I2

### Actions

1. Remove `org.hashids` from `pom.xml` and the `Hashids` bean from `ShortCodeConfig`.
2. Remove `RangeAwareIdGenerator` and the Redis `SEQUENCE_KEY` counter path.
3. Remove `SHORTENER_SALT` / `app.shortener.salt` from `application.yaml`, `application-test.yaml`
   and docs.
4. Ensure `IdGeneratorPort` has one pure implementation.

### Done when

- no `hashids` import/bean/coordinate; no Redis counter path for codes;
- `SHORTENER_SALT` gone from config/docs.

### Verify

```bash
mvn test
```

---

## Step 4 — Strategy wiring, namespace isolation
### Stories: I3

### Actions

1. Wire `RandomUrlIdStrategy` for no-alias; keep `VanityUrlIdStrategy` for aliases; remove the counter
   strategy.
2. Keep `ReservedWordsValidator`; reject reserved words as alias/code.
3. Enforce structural separation: generated codes = exactly `code-length` Base62 chars; vanity aliases
   = different shape (per-plan min length and/or `-`/`_` set) so the sets cannot overlap.
4. Rely on atomic `_id` insert for alias uniqueness (no check-then-put).

### Done when

- no generated code equals a reserved word;
- no vanity alias equals a valid generated code (shape disjoint);
- concurrent duplicate alias → one `409`;
- strategy dispatch is correct.

### Verify

```bash
mvn test
grep -rEn "import ca\.tyny\.urlshortener\.infra" src/main/java/ca/tyny/urlshortener/core   # no matches
```

---

## Step 5 — Remove URL dedup + `urlHash`
### Stories: I4, I6

### Actions

1. Remove `@Indexed(unique = true)` from `ShortUrlEntity.originalUrl`.
2. Add `urlHash` (SHA-256 of the original URL, lowercase hex) with no unique index.
3. Add a versioned/idempotent index step to drop the `originalUrl` unique index (and keep `_id`,
   `userId`). Replace `auto-index-creation` with a committed migration (or a runner with approval).
4. Add `app.shortener.code-length` validation if not already.

### Done when

- `originalUrl` is not unique; a duplicate URL creates a new code;
- `urlHash` is written on save;
- unique index dropped via a committed step; fresh and existing envs converge.

### Verify

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 6 — `409` semantics + duplicate-URL path
### Stories: I5

### Actions

1. Ensure a duplicate original URL creates a new code and returns `200` (not `409`).
2. Keep `409` for "custom alias already exists".
3. Fix any message implying "URL already shortened".

### Done when

- duplicate URL → 200 with a distinct code;
- reserved/invalid/repeated custom alias → 400/409 as specified;
- no misleading messaging.

### Verify

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 7 — Native build fix + dead metrics
### Stories: I7

### Actions

1. Change the `native` profile `mainClass` to `ca.tyny.urlshortener.Application`.
2. Remove (or wire) the dead `id.generation.duration` / `url.retrieval.duration` timers in
   `MetricsService`.
3. Verify `mvn clean package -Pnative` resolves the main class (build may be slow; native build
   optional if toolchain unavailable — at minimum confirm the config is correct).

### Done when

- `mainClass` is correct;
- no dead metric timers remain.

### Verify

```bash
mvn clean package -Pnative   # or confirm config if native toolchain is unavailable
```

---

## Step 8 — Full acceptance, boundary + docs sync
### Stories: V1, V2

### Actions

1. Map every acceptance row to a named test/CI command.
2. Run collision/namespace/dedup tests and the concurrency cases.
3. Run the full gate and boundary check.
4. Update all docs/provisioning listed in the spec.
5. Update `AGENTS.md` debt matrix (resolve 3, 4, 7, 11, 13; keep 5/15 analytics, 6 rate-limit,
   10 migrations, 14 quota, 8 SSRF as not-done here).
6. Produce a short as-built report: changed contracts, schema/config, tests/evidence, migration/rollback,
   and what is explicitly deferred to later epics.

### Full verification

```bash
mvn test
mvn verify
grep -rEn "import ca\.tyny\.urlshortener\.infra" src/main/java/ca/tyny/urlshortener/core
grep -rlE "org\.springframework|org\.mongodb|org\.redisson|io\.jsonwebtoken|io\.micrometer" src/main/java/ca/tyny/urlshortener/core
```

### Done when

- all G/I/V stories are evidenced green;
- full gate + boundary check pass;
- README, AGENTS, data-model-decisions, coding-standards, testing-playbook, lessons match reality;
- no P0 identity item is silently deferred;
- human reviewer accepts the as-built report.

---

## Final smoke / acceptance path

1. Shorten a URL twice with the same original → two distinct codes, both `200`, both redirect correctly.
2. Verify each code is 7 chars from `[0-9A-Za-z]`.
3. Try a custom alias that is a reserved word → `400`.
4. Try a custom alias that matches a generated code shape → rejected per the namespace rule.
5. Create a custom alias as an authenticated user; reuse it → `409`.
6. Restart the app → indexes are stable (no unique index on `originalUrl`), code length preserved.
7. Verify no `hashids`, `SHORTENER_SALT` or Redis counter path remains in config/logs.
8. Verify `core/` has no infra import; `mvn verify` green; CI commits run both stages.

---

_Pre-implementation sequence. Preserve deviations and final evidence as an as-built record after delivery._
