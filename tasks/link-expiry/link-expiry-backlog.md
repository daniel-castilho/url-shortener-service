# Link Expiry (TTL) & Versioned Schema Migration — Backlog

**Priority:** P1 — product value + operational hygiene. Fourth epic.
**All stories:** Must.
**Companions:** `link-expiry-spec.md` · `link-expiry-implementation-sequence.md`

**Execution status:** ready from `main` (`0579f5d`).

---

## Epic outcome

A short URL can expire: an optional TTL is set at creation, a MongoDB TTL index purges expired rows, and
the redirect path returns **`410 Gone`** for expired links (distinct from `404`), never serving an
expired link from cache. Schema/index changes are applied by a **versioned, ordered, idempotent,
fail-fast migration runner** instead of the ad-hoc `IndexMigration`.

---

## Story map

```text
EXPIRY MODEL
E1  expiresAt field on ShortUrl + ShortUrlEntity + mapper
E2  Optional TTL input (ttlSeconds | expiresAt) with bounds
E3  Return expired (410) distinct from not-found (404)

CACHE & READ PATH
C1  Cache is expiry-aware (won't serve an expired link)
C2  ShortUrlLookup / UrlExpiredException domain contract

MIGRATION
M1  Versioned schema-migration runner (schema_migrations)
M2  Port existing IndexMigration steps into migrations (drop unique, userId, click_events indexes)
M3  Add expiresAt TTL index migration
M4  Retire IndexMigration; fail-fast + idempotent migrations

VERIFICATION & DELIVERY
V1  Expiry + migration ITs (incl. cache expiry)
V2  Documentation sync (data-model-decisions, coding-standards, testing-playbook, AGENTS, README)
```

---

## E1 — `expiresAt` field on ShortUrl + ShortUrlEntity + mapper

**Goal:** every short URL can carry an optional expiry.

### Work

- add `Instant expiresAt` (nullable) to the `ShortUrl` record and `ShortUrlEntity`;
- add a constructor overload / `withExpiresAt` so existing call sites compile;
- update `ShortUrlMapper` to round-trip the field.

### Acceptance

- [ ] `expiresAt` on domain/entity (null = never expires).
- [ ] Mapper round-trips it; existing constructors remain callable.
- [ ] A link with `expiresAt` in the past is treated as expired by the domain predicate.

---

## E2 — Optional TTL input with bounds

**Goal:** clients can set an expiry, bounded by server config.

### Work

- accept optional `ttlSeconds` (recommended) or `expiresAt` in `ShortenRequest`;
- convert to `expiresAt = now + ttlSeconds` and cap at `app.shortener.max-ttl-seconds`;
- reject past/zero/over-cap values with `400`.

### Acceptance

- [ ] `ttlSeconds` (or `expiresAt`) sets the expiry; `null` → never expires.
- [ ] Over-cap / past / zero values → `400`.
- [ ] The value is bounded and unit-tested at min/over/absent.

---

## E3 — Return expired (410) distinct from not-found (404)

**Goal:** the redirect distinguishes "expired" from "never existed".

### Work

- map an expired link to `410 Gone` in the controller;
- keep `404` for unknown code;
- add the `UrlExpiredException` (or a read result) and a handler.

### Acceptance

- [ ] Expired link → `410`; unknown code → `404`.
- [ ] The response has a clear, stable message.
- [ ] No expired link returns `302`.

---

## C1 — Cache is expiry-aware

**Goal:** an expired link is never served from a warm cache.

### Work

- store `expiresAt` alongside the URL in the cache value (recommended), or don't cache links with a TTL;
- on read, if the cached entry is logically expired, treat as expired (and optionally evict) rather than
  redirecting.

### Acceptance

- [ ] A cache hit on an expired link returns the expired state, not the URL.
- [ ] A warm non-expired link still served from cache (no perf regression).
- [ ] Blooms/cache are consistent with the expiry logic.

---

## C2 — Expiry-aware read contract (framework-free)

**Goal:** `core/` distinguishes valid/expired/absent without leaking framework types.

### Work

- add a domain read result (`ShortUrlLookup(url, expired)`) or a `UrlExpiredException` thrown by the
  service;
- keep `core/` dependency-free (no Mongo/Redis/JWT types).

### Acceptance

- [ ] The domain read path returns/throws clear state (valid/expired/absent).
- [ ] No framework type in `core/`.
- [ ] The controller maps the domain state to `302` / `410` / `404`.

---

## M1 — Versioned schema-migration runner

**Goal:** a deterministic, ordered, idempotent, fail-fast migration mechanism.

### Work

- add a `schema_migrations` collection tracking `{ version, name, appliedAt }`;
- add a `Migration` interface (`version`, `name`, `apply(MongoTemplate)`) and a runner that applies
  pending migrations in ascending order and records them idempotently;
- abort startup (fail-fast) if a migration fails.

### Acceptance

- [ ] Migrations run once, in order; re-running is a no-op.
- [ ] A failed migration aborts startup.
- [ ] The runner is `infra` (framework allowed); the port/interface is clean.

---

## M2 — Port existing IndexMigration steps into migrations

**Goal:** preserve existing index management under the versioned runner.

### Work

- port into migrations: drop `originalUrl_1`, ensure `userId`, ensure `click_events` indexes
  (`(shortCode, timestamp)` + `(timestamp)`);
- make each idempotent.

### Acceptance

- [ ] Existing index behaviour is preserved and now versioned.
- [ ] No duplicates on re-run.

---

## M3 — Add `expiresAt` TTL index migration

**Goal:** MongoDB auto-purges expired links.

### Work

- add a migration that ensures the `expiresAt` TTL index (`expireAfter(0, SECONDS)`).

### Acceptance

- [ ] TTL index exists on `short_urls.expiresAt`.
- [ ] Expired rows are gradually purged by MongoDB (TTL).
- [ ] The migration is idempotent.

---

## M4 — Retire IndexMigration

**Goal:** remove the ad-hoc `@PostConstruct` index work.

### Work

- remove/retire `IndexMigration` (replace its logic with the runner + migrations);
- confirm nothing else depends on it.

### Acceptance

- [ ] `IndexMigration` is gone; schema is managed only by the runner.
- [ ] Startup migrates correctly and serves traffic after migrations complete.

---

## V1 — Expiry + migration integration tests

**Goal:** prove expiry (incl. cache) and migration end-to-end.

### Work

- IT: create with TTL → redirect works → past TTL → `410`;
- IT: expired link not served from a warm cache;
- IT: `404` unknown code stays `404`;
- IT: migration runner applies pending migrations once, re-running is a no-op, a failing migration
  aborts startup.

### Acceptance

- [ ] Expiry IT matrix green (redirect, 410, cache).
- [ ] Migration IT green (apply-once, idempotent, fail-fast).
- [ ] `mvn verify` green.

---

## V2 — Documentation sync

**Goal:** docs reflect applied expiry + versioned migrations.

### Work

- `data-model-decisions.md` — expiry + TTL + versioned-migration decisions to applied; note `410`;
- `coding-standards.md` — expiry validation, cache-aware read, migration rules;
- `testing-playbook.md` — expiry/migration ITs;
- `AGENTS.md` — clear debt item 10; note expiry;
- `README.md` — optional `ttlSeconds`, `410` on expired, migration note.

### Acceptance

- [ ] No doc describes IndexMigration or "links never expire" as current.
- [ ] `AGENTS.md` marks item 10 resolved.

---

## Epic Definition of Done

- [ ] E1–E3 complete: `expiresAt` on domain/entity, optional TTL input with bounds, `410` for expired.
- [ ] C1–C2 complete: cache is expiry-aware; domain read contract is framework-free and clear.
- [ ] M1–M4 complete: versioned migration runner, existing indexes ported, `expiresAt` TTL added,
      `IndexMigration` retired.
- [ ] V1–V2 complete: expiry + migration ITs green; docs and `AGENTS.md` synced.
- [ ] `mvn test`, `mvn verify` and the expiry/migration `*IT` pass.
- [ ] No expired link is served (including from cache); expired ≠ not-found (410 vs 404).
