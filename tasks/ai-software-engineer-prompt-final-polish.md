# AI Software Engineer Prompt — Final Polish & Data Integrity (Closure Epic)

**Status:** ready for implementation from current `main` (`f052786`, `v0.9.0`).
**Priority:** P1 — closure. Sixth (final) epic of this stage.
**Target:** close the last open debt item and clean the small residual consistency gaps so the stage is
genuinely complete — no new features.

You implement the complete **Final Polish & Data Integrity** closure epic. All functional/operational
epics (identity, quality gates, analytics, rate-limit, SSRF/actuator, link-expiry, observability) are
done and tagged (v0.1.0–v0.9.0). Your job is the remaining closing items.

---

## Sources of truth — read in this order

1. `AGENTS.md` (rules 4, 7, 8, 10; Known Technical Debt item 10 — the `users.email` unique index)
2. `docs/data-model-decisions.md` (Registration → "unique index on email" as source of truth)
3. `docs/coding-standards.md`
4. `docs/testing-playbook.md`
5. `src/main/resources/application.yaml` (`spring.data.mongodb.auto-index-creation`)
6. `tasks/final-polish-spec.md`
7. `tasks/final-polish-backlog.md`
8. `tasks/final-polish-implementation-sequence.md`
9. `infra/.../migration/*` (`MongoSchemaMigrator`, `SchemaMigration`, V1–V5), `MongoCollections`,
   `UserEntity`, `MongoUserRepository`, `UserService`, `UrlController`, `UrlShortenerService`,
   `ShortenRequest`/`ShortenUrlUseCase`

If documentation disagrees with executable configuration, stop, report and resolve in the same change.

---

## Goal

The `AGENTS.md` debt matrix is nearly all `resolved`. One item remains **open**: the `users.email`
unique index is declared on `UserEntity` (`@Indexed(unique=true)`) but **never created** because
`auto-index-creation` is `false` and no migration makes it — so email uniqueness is not guaranteed at the
storage layer (only via a `findByEmail` check, which is TOCTOU). This epic closes it, and fixes two small
consistency nits left by the link-expiry epic.

It closes:

- the `users.email` unique index is not enforced by the DB;
- the redirect OpenAPI omits `410 Gone`;
- the `ttlSeconds → expiresAt` cap/conversion still lives in the web adapter (should be application logic);
- stale "target / not yet applied" documentation.

---

## Locked technical decisions

1. **Add migration `V6EnsureUserIndexes`** (in-code, no new library): `users.email` **unique** index, plus
   `plan` and `createdAt` non-unique (mirroring `UserEntity`'s `@Indexed`). Idempotent, follows the
   existing `SchemaMigration` pattern. `auto-index-creation` stays `false`.
2. **`MongoCollections.USERS = "users"`** and use it (in V6 and `UserEntity`'s `@Document`).
3. **Document `410 Gone`** in the redirect OpenAPI `@ApiResponses`.
4. **Move `ttlSeconds → expiresAt`** (cap + `Instant.now().plusSeconds`) into `UrlShortenerService`
   (application layer); the controller passes the raw `ttlSeconds`.
5. **Clean stale docs** — remove "remain target until their epics land", "Until the unique index is
   dropped…", "Registry of indexes (target)": state the applied state.
6. **No new Maven coordinate.** No feature work.

---

## Non-negotiable engineering rules

- Keep `core/` framework-free; the `ttlSeconds → expiresAt` rule is application logic.
- Migrations are idempotent, versioned, checksummed, fail-fast.
- Storage-level uniqueness must be proven by a test (not just the app check).
- The public API contract doesn't change — `410` becomes documented (it's already returned).
- English only in code, comments, logs, tests and docs.
- Do not push unless the human explicitly asks.
- Do not expand into: new features, dashboards/provisioning, new dependencies, any scope beyond the four
  items above.

---

## Required behaviour summary

### Data integrity
- Migration `V6EnsureUserIndexes` (email unique, plan, createdAt); `MongoCollections.USERS`.
- Integration test proving storage-level email uniqueness.

### API & architecture
- Redirect `@ApiResponses` includes `410`.
- `ttlSeconds → expiresAt` moved from `UrlController` to `UrlShortenerService`; cap maps to `400`.

### Docs
- `docs/data-model-decisions.md` cleaned of "target / not-yet-applied" phrasing; `users.email` unique
  index stated as applied via V6.
- `README.md`/`AGENTS.md` drop equivalent notes; debt item 10 (email index) marked resolved.

---

## Definition of Done

- [ ] Migration V6 created; `users.email` unique index + `plan`/`createdAt` indexes exist on a real Mongo.
- [ ] `MongoCollections.USERS` defined and used; no `"users"` literal in the migration.
- [ ] Storage-level email-uniqueness integration test proves the second insert fails.
- [ ] Redirect OpenAPI lists `410 Gone`.
- [ ] `ttlSeconds → expiresAt` cap/conversion is in the application layer; over-cap still returns `400`.
- [ ] Stale "target" docs cleaned; `AGENTS.md` marks the email-index item resolved.
- [ ] `mvn test`, `mvn verify` and `bash scripts/check-boundaries.sh` pass.

Start at **Step 0** of `final-polish-implementation-sequence.md`. Stop immediately if the baseline is
red, a locked decision conflicts with the approved dependency graph, or repository state contradicts the
specification.
