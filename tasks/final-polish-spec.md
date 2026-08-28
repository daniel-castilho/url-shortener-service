# Final Polish & Data Integrity (Closure Epic) — Technical Specification

**Status:** ready for implementation from current `main` (`f052786`, `v0.9.0`).
**Priority:** P1 — closure / hardening. Sixth (final) epic of this stage.
**Companions:** `final-polish-backlog.md` · `final-polish-implementation-sequence.md`

---

## 1. Purpose

The six functional/operational epic areas (identity, quality gates, analytics, rate-limit, SSRF/actuator,
link-expiry, observability/operations) are implemented and tagged (v0.1.0–v0.9.0). The `AGENTS.md` debt
matrix is almost fully `resolved`. This **closure** epic closes the one remaining open item and cleans up
the small, residual consistency gaps left by the last two epics so the stage is genuinely done.

It is intentionally **small and precise** — no new features, no scope creep.

---

## 2. Scope

### In scope

- **Close the open debt item 10** — the `users.email` **unique index** is declared on `UserEntity`
  (`@Indexed(unique = true)`) but is **never created by any migration** because `auto-index-creation`
  is `false`. The email-uniqueness guarantee documented as "source of truth" therefore does **not**
  exist at the storage layer. Add a **V6 migration** to create it (and the other declared `users`
  indexes), and add a `USERS` constant to `MongoCollections`.
- **Document `410 Gone` in the redirect OpenAPI** — the endpoint returns `410` via `UrlExpiredException`
  but the `@ApiResponses` only list `302`/`404`/`429`.
- **Move the `ttlSeconds → expiresAt` rule into the application layer** (currently in the `UrlController`
  adapter) so domain policy (cap + conversion) is not in the web adapter.
- **Clean stale/transitory documentation** in `docs/data-model-decisions.md` (phrases like "remain
  target until their epics land", "Until the unique index is dropped… treat as a bug not the product
  rule", "Registry of indexes (target)") and any equivalent notes in `README.md`/`AGENTS.md`.
- Final verification of the full gate and boundary check.

### Out of scope

- any new feature beyond the above;
- adding a Maven coordinate (a library) — the unique index uses the existing in-code migrations;
- infrastructure dashboards/provisioning beyond what exists;
- changing the public API contract (only documenting the already-true `410`).

---

## 3. Architectural constraints

- `core/` stays framework-free. The `ttlSeconds → expiresAt` conversion stays domain/app logic but moves
  out of the web adapter into the application/service layer (no framework types).
- Migrations remain the in-code, versioned, checksummed, idempotent, fail-fast runner
  (`MongoSchemaMigrator`). Adding `V6` follows the exact `SchemaMigration` pattern.
- `MongoCollections` gains a `USERS` constant so the collection name is not a string literal.

---

## 4. Exact change list

### 4.1 Migration V6 — `users` indexes

Add `infra/adapter/output/persistence/migration/V6EnsureUserIndexes.java implements SchemaMigration`:

- version `6`, description `"Ensure users collection indexes (email unique, plan, createdAt)"`.
- Apply (idempotent `ensureIndex`):
  - `email` **unique** index;
  - `plan` non-unique;
  - `createdAt` non-unique.
  (Mirror the `@Indexed` annotations on `UserEntity`; `email` carries `unique = true`.)
- `idempotent()` returns `true`.

Add `MongoCollections.USERS = "users"` and use it in the migration (and optionally in `UserEntity`'s
`@Document` for consistency).

### 4.2 OpenAPI — document `410`

In `UrlController.redirect`, add to `@ApiResponses`:

```java
@ApiResponse(responseCode = "410", description = "Short URL has expired", content = @Content)
```

### 4.3 Move `ttlSeconds → expiresAt` into the application layer

- Move the cap/conversion (`resolveExpiresAt`) out of `UrlController` into the service/use-case
  (`UrlShortenerService` / a small domain helper), so the adapter just passes the raw `ttlSeconds`
  (or a validated `Long`) and the application layer enforces `maxTtlSeconds` and computes the
  `Instant expiresAt`.
- Validate the cap in the application layer (throw a domain `InvalidExpiryException`/`IllegalArgument`
  mapped to `400`), and remove the adapter-side logic.
- Update the tests accordingly.

### 4.4 Clean stale documentation

In `docs/data-model-decisions.md`:
- Remove/rewrite phrases implying the work is not yet applied (e.g. the top note "…remain target until
  their epics land", the "Until the unique index is dropped…" note under URL dedup, and "Registry of
  indexes (**target**)").
- Ensure the "Registration (email uniqueness)" section now states the unique index is **applied** via
  migration V6 and is the storage-level guarantee.

In `README.md` / `AGENTS.md`: remove any equivalent "target/not yet applied" phrasing and mark the
`users.email` unique index item as resolved (migration V6).

---

## 5. Verification commands

```bash
mvn test
mvn test -Dtest='SchemaMigrationIT,MongoUserRepositoryIT' -DfailIfNoTests=false
mvn verify
bash scripts/check-boundaries.sh
```

- Confirm `schema_migrations` records version `6` and the `users.email` unique index exists against a
  real Mongo (SchemaMigrationIT).
- Possibly also assert that two concurrent register calls with the same email cannot both insert —
  if the existing E2E does not already cover storage-level uniqueness, add it.

---

## 6. Documentation deliverables

- `docs/data-model-decisions.md` — clean transitory phrasing; mark `users.email` unique index as applied.
- `README.md` / `AGENTS.md` — drop "target" notes; mark item 10 fully resolved.
- OpenAPI (the `410` response) — now accurate.

The epic is **not** Done while `users.email` has no unique index, the OpenAPI omits `410`, the
`ttlSeconds → expiresAt` logic still lives in the adapter, or transitory "target" phrasing remains.

---

## 7. Why this is the closure epic

After this, the documented single-source-of-truth decisions all match the code, the last open debt item
is closed, and the small consistency nits are resolved. It is the "delete the TODO and ship" step that
makes the stage genuinely complete rather than merely feature-complete.
