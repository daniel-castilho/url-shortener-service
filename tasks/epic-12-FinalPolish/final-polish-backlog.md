# Final Polish & Data Integrity (Closure Epic) — Backlog

**Priority:** P1 — closure. Sixth (final) epic of this stage.
**All stories:** Must.
**Companions:** `final-polish-spec.md` · `final-polish-implementation-sequence.md`

**Execution status:** ready from `main` (`f052786`, `v0.9.0`).

---

## Epic outcome

The last open debt item (`users.email` unique index) is closed, the redirect OpenAPI is accurate
(`410` documented), the `ttlSeconds → expiresAt` rule lives in the application layer (not the web
adapter), and stale "target / not yet applied" documentation is cleaned. The stage is genuinely complete.

---

## Story map

```text
DATA INTEGRITY
F1  V6 migration: users.email unique index + plan/createdAt indexes
F2  USERS constant in MongoCollections; use in migration + UserEntity
F3  Storage-level email-uniqueness integration test

API & ARCHITECTURE
F4  Document 410 Gone in redirect OpenAPI
F5  Move ttlSeconds → expiresAt into application layer (out of controller)

DOCS
F6  Clean stale "target / not yet applied" phrasing + mark item 10 resolved

VERIFY
V1  Full gate + boundary check green; stage-closure review
```

---

## F1 — V6 migration: users.email unique index

**Goal:** the documented email-uniqueness guarantee exists at the storage layer.

### Work

- add `V6EnsureUserIndexes implements SchemaMigration` (version 6) creating:
  - `email` **unique** index;
  - `plan` and `createdAt` non-unique (mirroring `UserEntity`'s `@Indexed`);
- idempotent; follows the existing `SchemaMigration` pattern.

### Acceptance

- [ ] A real Mongo has a unique index on `users.email` after migration V6.
- [ ] The migration is recorded in `schema_migrations` and is idempotent (re-run safe).

---

## F2 — `USERS` constant in MongoCollections

**Goal:** no string-literal collection name.

### Work

- add `MongoCollections.USERS = "users"`;
- use it in V6 and in `UserEntity`'s `@Document`.

### Acceptance

- [ ] `MongoCollections.USERS` exists and is used; no hardcoded `"users"` in the migration.

---

## F3 — Storage-level email-uniqueness test

**Goal:** prove unique-email is enforced at the DB, not only the app check.

### Work

- add an integration test: two concurrent/sequential register attempts with the same (normalized) email
  — or two direct `MongoUserRepository` inserts of the same email — cannot both succeed (second gets a
  duplicate-key error).

### Acceptance

- [ ] Storage-level uniqueness is proven (second insert fails).
- [ ] The existing app-level check remains (defense in depth); an E2E flow still passes.

---

## F4 — Document 410 in redirect OpenAPI

**Goal:** the API docs match the true behaviour.

### Work

- add `@ApiResponse(responseCode = "410", description = "Short URL has expired")` to the redirect `@Operation`.

### Acceptance

- [ ] Swagger/OpenAPI lists `410` alongside `302`/`404`/`429`.

---

## F5 — Move `ttlSeconds → expiresAt` into the application layer

**Goal:** domain policy (cap + conversion) is not in the web adapter.

### Work

- move `resolveExpiresAt` (cap via `maxTtlSeconds` + `Instant.now().plusSeconds`) from `UrlController`
  to the `UrlShortenerService` (or a small domain helper);
- the adapter passes the raw `ttlSeconds`; the service validates the cap (maps to `400`) and computes
  `expiresAt`;
- update the controller, DTO flow and tests.

### Acceptance

- [ ] No cap/conversion rule in the controller; it is in the application layer.
- [ ] Over-cap `ttlSeconds` still returns `400`.

---

## F6 — Clean stale documentation

**Goal:** no "target / not yet applied" phrasing; item 10 fully resolved.

### Work

- `docs/data-model-decisions.md`: remove "remain target until their epics land", "Until the unique index
  is dropped…", and "Registry of indexes (target)" → state applied; note the email-unique index is applied
  via migration V6 and is the storage-level guarantee.
- `README.md` / `AGENTS.md`: drop equivalent target notes; mark debt item 10 resolved.

### Acceptance

- [ ] No "target / not yet applied" phrasing remains for applied features.
- [ ] `AGENTS.md` marks the `users.email` unique index item resolved.

---

## V1 — Full gate + stage-closure review

**Goal:** everything green and consistent.

### Work

- run `mvn test`, `mvn verify`, `bash scripts/check-boundaries.sh`;
- confirm `schema_migrations` records V6 and the email unique index exists (integration test);
- review that no documented decision contradicts the code.

### Acceptance

- [ ] Full gate + boundary check green.
- [ ] Stage-closure criteria met (see spec §7).

---

## Epic Definition of Done

- [ ] F1–F3 complete: `users.email` unique index created & enforced at storage; `USERS` constant; test passes.
- [ ] F4 complete: `410` documented in redirect OpenAPI.
- [ ] F5 complete: `ttlSeconds → expiresAt` rule in application layer.
- [ ] F6 complete: stale docs cleaned; item 10 marked resolved.
- [ ] V1 complete: `mvn test`, `mvn verify`, `check-boundaries.sh` all green.
- [ ] No documented decision contradicts code; the stage is genuinely complete.
