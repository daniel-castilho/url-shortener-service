# Final Polish & Data Integrity (Closure Epic) — Implementation Sequence

**Companions:** `final-polish-spec.md` · `final-polish-backlog.md`
**Rule:** complete each step's acceptance and verification before starting the next. Do not invent
out-of-scope work.

---

## Global execution rules

1. Work in small, reviewable vertical commits.
2. Read the referenced story acceptance before coding.
3. Add tests with the production change, not at the end.
4. Migrations stay idempotent, versioned, checksummed, fail-fast — follow the exact `SchemaMigration`
   pattern.
5. `core/` stays framework-free; the `ttlSeconds → expiresAt` rule lives in the application layer.
6. No new Maven coordinate.
7. After each step, update task status and docs; do not silently alter the spec.

### Fast verification (throughout)

```bash
mvn test
```

### Integration verification

```bash
mvn test -Dtest='SchemaMigrationIT,MongoUserRepositoryIT' -DfailIfNoTests=false
```

### Full gate

```bash
mvn verify
```

---

## Step 0 — Baseline, confirm the open item
### Stories: (context)

### Actions

1. Confirm HEAD (`f052786`) and fast tests green.
2. Confirm the `users.email` unique index is **not** created by any migration (with
   `auto-index-creation: false`), i.e. the app check `findByEmail(...).isPresent()` is the only guard.
3. Confirm the `ttlSeconds → expiresAt` logic is in `UrlController`.
4. Record the decision: add `V6EnsureUserIndexes` (in-code, no new library).

### Done when

- baseline understood; the open item and the adapter-side rule are confirmed.

### Verify

```bash
mvn test
```

---

## Step 1 — V6 migration: users.email unique index
### Stories: F1, F2

### Actions

1. Add `MongoCollections.USERS = "users"`.
2. Add `infra/.../migration/V6EnsureUserIndexes.java implements SchemaMigration` (version 6) that
   `ensureIndex` on `email` (unique), `plan`, `createdAt` (non-unique). Idempotent.
3. Use `MongoCollections.USERS` in `UserEntity` `@Document` and in the migration.

### Done when

- migration V6 exists, idempotent, follows the pattern;
- `MongoCollections.USERS` used; no `"users"` literal in the migration.

### Verify

```bash
mvn test
mvn test -Dtest='SchemaMigrationIT' -DfailIfNoTests=false
```

---

## Step 2 — Storage-level email-uniqueness test
### Stories: F3

### Actions

1. Add an integration test that attempts to insert two users with the same normalized email and asserts
   the second fails (duplicate key) at the storage layer.

### Done when

- storage-level uniqueness is proven.

### Verify

```bash
mvn test -Dtest='MongoUserRepositoryIT' -DfailIfNoTests=false
```

---

## Step 3 — Document 410 in redirect OpenAPI
### Stories: F4

### Actions

1. Add `@ApiResponse(responseCode = "410", description = "Short URL has expired")` to the redirect
   operation.

### Done when

- Swagger/OpenAPI lists `410`.

### Verify

```bash
mvn test
```

---

## Step 4 — Move `ttlSeconds → expiresAt` into the application layer
### Stories: F5

### Actions

1. Move `resolveExpiresAt` (cap + `Instant.now().plusSeconds`) from `UrlController` to
   `UrlShortenerService` (or a domain helper).
2. The controller passes the raw `ttlSeconds`; the service validates the cap (maps to `400`) and computes
   `expiresAt`.
3. Update the DTO flow, the `ShortenRequest`/use-case signature, and tests.

### Done when

- no cap/conversion rule in the controller; it is in the application layer;
- over-cap `ttlSeconds` still returns `400`.

### Verify

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 5 — Clean stale documentation
### Stories: F6

### Actions

1. `docs/data-model-decisions.md`: rewrite/remove "target / until their epics land / until the unique
   index is dropped / Registry of indexes (target)"; state `users.email` unique index is applied via V6
   and is the storage guarantee.
2. `README.md` / `AGENTS.md`: drop equivalent target notes; mark debt item 10 (email index) resolved.

### Done when

- no "target / not yet applied" phrasing remains for applied features;
- `AGENTS.md` marks the email-index item resolved.

### Verify

```bash
grep -rniE "remain target|until .*epics land|registry of indexes \(target\)|until the unique index is dropped" docs README.md AGENTS.md   # expect no hits
```

---

## Step 6 — Full gate + stage-closure review
### Stories: V1

### Actions

1. Run `mvn test`, `mvn verify`, `bash scripts/check-boundaries.sh`.
2. Confirm `schema_migrations` records V6 and the `users.email` unique index exists (via the IT).
3. Review that no documented decision contradicts the code.

### Done when

- full gate + boundary check green;
- stage-closure criteria met.

### Verify

```bash
mvn verify
bash scripts/check-boundaries.sh
```

---

## Final smoke / acceptance path

1. Start the app → migration V6 applies; `schema_migrations` has version 6.
2. Inspect MongoDB `users` indexes → a **unique** index on `email` exists (plus `plan`, `createdAt`).
3. Attempt to insert two users with the same email → the second fails at the storage layer.
4. Swagger shows `410` on the redirect endpoint.
5. `ttlSeconds` over the cap → `400`; a valid one still sets `expiresAt`.
6. `mvn verify` + `check-boundaries.sh` green; no stale "target" phrasing in docs.

---

_Pre-implementation sequence. Preserve deviations and final evidence as an as-built record after delivery._
