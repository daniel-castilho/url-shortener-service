# Link Expiry (TTL) & Versioned Schema Migration — Implementation Sequence

**Companions:** `link-expiry-spec.md` · `link-expiry-backlog.md`
**Rule:** complete each step's acceptance and verification before starting the next. Do not invent
out-of-scope work.

---

## Global execution rules

1. Work in small, reviewable vertical commits.
2. Read the referenced story acceptance before coding.
3. Add tests with the production change, not at the end.
4. **Expiry is application-logic truth, not DB-TTL truth.** The redirect must check `expiresAt`
   eagerly; MongoDB TTL only purges later.
5. **Cache must not serve an expired link** — this is the easy-to-miss regression.
6. Migrations are **idempotent, ordered, fail-fast**. No new Maven coordinate without approval.
7. After each step, update task status and docs; do not silently alter the spec.

### Fast verification (throughout)

```bash
mvn test
```

### Integration verification

```bash
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

### Full gate

```bash
mvn verify
```

---

## Step 0 — Baseline, design lock, migration decision
### Stories: (context)

> **Status: DONE (2026-08-27).** Baseline is `d9f71c6` / `v0.7.0` (observability epic) — ahead of the
> spec's referenced `0579f5d`, which was the state when the spec was written. Fast tests green
> (`mvn test`: 162, BUILD SUCCESS). Current read path confirmed **not** expiry-aware
> (`RedisUrlCache` stores a bare `String`; `UrlShortenerService.getOriginalUrl` returns the URL
> blindly) and `IndexMigration` is the ad-hoc `@PostConstruct` index manager.
>
> **Migration decision — superseded (see as-built below).** A deviation was approved to try
> **Flyway** (`flyway-core` + `flyway-database-mongodb`, JSON migration files; coordinates from the
> Spring Boot BOM). The experiment **failed** (the required JDBC driver is not on Maven Central and
> native connectors are CLI-only) and the dependency changes were reverted. After a web
> investigation and two explicit human approvals, the implementation uses the **spec's default:
> in-code versioned migration runner** (`MongoSchemaMigrator`). Confirmed `auto-index-creation:
> false` already set in `application.yaml`.

### Actions

1. Confirm HEAD (`0579f5d`) and fast tests green.
2. Confirm the current read path is **not** expiry-aware (cache stores URL string only) and that
   `IndexMigration` is the ad-hoc `@PostConstruct` schema mechanism. Record this.
3. Record locked decisions in `docs/data-model-decisions.md`:
   - `expiresAt` nullable = never expires; TTL index for purge;
   - `410 Gone` for expired vs `404` for not-found;
   - cache is expiry-aware (store `expiresAt` in cache value, don't serve expired);
   - **versioned migration runner** (no new dependency) replaces `IndexMigration`.
4. Confirm the migration-library decision: implement the in-code runner (no new coordinate) unless the
   human explicitly approves mongock/flyway.

### Done when

- baseline understood; decisions recorded; migration approach approved;
- no unresolved "or/if available" choice remains.

### Verify

```bash
mvn test
```

---

## Step 1 — `expiresAt` on domain/entity + mapper
### Stories: E1

### Actions

1. Add `Instant expiresAt` (nullable) to `ShortUrl` and `ShortUrlEntity`; add a `withExpiresAt`
   overload.
2. Update `ShortUrlMapper` to round-trip it.
3. Add a domain predicate `isExpired(now)` (pure, framework-free).

### Done when

- `expiresAt` on domain/entity + mapper; constructors compile;
- domain predicate detects expiry.

### Verify

```bash
mvn test
```

---

## Step 2 — Optional TTL input + bounds
### Stories: E2

### Actions

1. Add optional `ttlSeconds` (recommended) or `expiresAt` to `ShortenRequest`.
2. Convert to `expiresAt = now + ttlSeconds`; cap at `app.shortener.max-ttl-seconds`.
3. Reject past/zero/over-cap with `400`; update the validation and the exception mapping.

### Done when

- TTL sets expiry; `null` → never expires;
- bounds enforced and unit-tested.

### Verify

```bash
mvn test
```

---

## Step 3 — Versioned migration runner + port existing indexes
### Stories: M1, M2

### Actions

1. Add `schema_migrations` collection + `Migration` interface + `SchemaMigrationRunner`.
2. Port current `IndexMigration` steps into migrations (drop `originalUrl_1`, ensure `userId`,
   `click_events` indexes), each idempotent.
3. Ensure migrations apply in ascending version order on startup, record idempotently, and **abort
   startup** on failure.

### Done when

- runner applies pending migrations once, in order, idempotently;
- re-run is a no-op; a failing migration aborts startup;
- existing index behaviour preserved.

### Verify

```bash
mvn test
mvn test -Dtest='*Migration*IT' -DfailIfNoTests=false
```

---

## Step 4 — `expiresAt` TTL index migration + retire IndexMigration
### Stories: M3, M4

### Actions

1. Add a migration that ensures the `expiresAt` TTL index (`expireAfter(0, SECONDS)`).
2. Remove/retire `IndexMigration`; confirm nothing else depends on it.

### Done when

- TTL index exists on `expiresAt`;
- `IndexMigration` removed; schema managed only by the runner.

### Verify

```bash
mvn test
mvn test -Dtest='*Migration*IT' -DfailIfNoTests=false
```

---

## Step 5 — Expiry-aware read path + 410
### Stories: E3, C2

### Actions

1. Add a domain read result (`ShortUrlLookup(url, expired)`) or a `UrlExpiredException`.
2. In the read path: if `expiresAt` is in the past, treat as expired (don't redirect).
3. Map in the controller: expired → `410`, unknown → `404`, valid → `302`.

### Done when

- expired link → `410`; unknown code → `404`; valid → `302`;
- no expired link returns `302`; `core/` stays framework-free.

### Verify

```bash
mvn test
mvn test -Dtest='ShortenFlowIT' -DfailIfNoTests=false
```

---

## Step 6 — Cache expiry-awareness
### Stories: C1

### Actions

1. Store `expiresAt` in the cache value (a small cache DTO) or don't cache links with a TTL.
2. On read, if cached entry is logically expired, treat as expired (and optionally evict) — never serve
   the URL.

### Done when

- a cache hit on an expired link returns the expired state, not the URL;
- a warm non-expired link is still served from cache.

### Verify

```bash
mvn test
mvn test -Dtest='*Cache*IT,*Expiry*IT' -DfailIfNoTests=false
```

---

## Step 7 — Metrics + full gate
### Stories: V1

### Actions

1. Add metrics: `urls.expired.total`, `schema.migrations.applied.total`, `schema.migrations.failed.total`.
2. Add IT matrix: TTL → redirect → past TTL → `410`; expired link from warm cache → not served;
   unknown → `404`; migration runs once/idempotently/fails-fast.

### Done when

- expiry IT matrix green; migration IT green;
- metrics present with fixed tags; `mvn verify` green.

### Verify

```bash
mvn verify
mvn test -Dtest='*Expiry*IT,*Migration*IT' -DfailIfNoTests=false
```

---

## Step 8 — Documentation sync
### Stories: V2

### Actions

1. `data-model-decisions.md` — expiry/TTL/versioned-migration to applied; note `410`.
2. `coding-standards.md` — expiry validation, cache-aware read, migration rules.
3. `testing-playbook.md` — expiry/migration ITs.
4. `AGENTS.md` — clear debt item 10; note expiry.
5. `README.md` — optional `ttlSeconds`, `410`, migration note.

### Done when

- no doc describes IndexMigration or "links never expire" as current;
- `AGENTS.md` marks item 10 resolved.

### Verify

```bash
mvn verify
```

---

## Final smoke / acceptance path

1. Create a link with a short TTL → redirect works.
2. After the TTL passes → same code returns `410` (not `302`, not `404`); the row may still exist until
   MongoDB TTL purges it.
3. Warm the cache, wait past TTL → still `410` (expired link not served from cache).
4. A random/missing code → `404`.
5. On startup, migrations apply once and in order; re-running is a no-op; a forcing/duplicate migration
   is a no-op; a failing migration aborts startup.
6. `mvn verify` + the expiry/migration `*IT` pass; no sensitive tag in metrics.

---

## As-built record (2026-08-27)

- **Steps 1 & 2 (E1, E2) — done.** `expiresAt` (`Instant`, nullable) on `ShortUrl` +
  `ShortUrlEntity` + mapper; `ShortUrl.isExpired(now)`; `withExpiresAt`. `ShortenRequest.ttlSeconds`
  (`@Positive`, `Long`), `app.shortener.max-ttl-seconds` (default 31 536 000 ≈ 1 year),
  4-arg `ShortenUrlUseCase.shorten(..., Instant expiresAt)`, `UrlController.resolveExpiresAt` maps
  TTL→`expiresAt` (over-cap / non-positive → `400`), HTTP 400 on bad input.
- **Step 3 (M1/M2) — done.** In-code runner **`MongoSchemaMigrator`** (@Component, `@PostConstruct`,
  fail-fast, checksum = SHA-256 hex of class name via manual `MessageDigest` — Spring 6.2
  `DigestUtils` no longer exposes `sha256`), history in **`schema_migrations`** (one doc per version,
  upsert-on-applied). Migrations `V1Baseline`, `V2DropOriginalUrlUniqueIndex`,
  `V3EnsureUserIdIndex`, `V4EnsureClickEventsIndexes`. `spring-data-mongodb` TTL API verified via
  `javap`: `Index.expire(long, TimeUnit)` (not `expireAfter(...)`). Flyway attempt reverted; pom and
  `application.yaml` untouched (no dependency changes — Rule 9 respected).
- **Step 4 (M3/M4) — done.** `V5AddExpiresAtTtlIndex` (`expiresAt` TTL, `expire(0, TimeUnit.SECONDS)`).
  `IndexMigration.java` deleted (no references). `SchemaMigrationIT` (2 tests) proves a fresh DB
  migrates V1→V5 with correct indexes (incl. `expiresAt_1` TTL `Duration.ZERO`) and that re-running
  the migration is idempotent (still one history row per version).
- **Step 5 (E3/C2) — done.** `UrlExpiredException` (core exception) + `410 Gone` mapping in
  `GlobalExceptionHandler`. `UrlShortenerService.getOriginalUrl` checks `isExpired(Instant.now())`
  eagerly on the read path and never caches an expired link. `ExpiredUrlIT` (4 tests): expired→`410`,
  non-expired→`302`, no-expiry→`302`, and a real end-to-end TTL (shorten with `ttlSeconds=1` →
  redirects, then no longer redirects after ~1 s → `410`/`404`).
- **Step 6 (C1) — done.** New value object `CachedUrlValue(originalUrl, expiresAt)` in `core/model`;
  `UrlCachePort.get/put` signature changed to it; `RedisUrlCache` stores
  `{"u":<url>,"e":<epochSeconds|null>}` JSON, TTL = `BASE_TTL(24h)+jitter` for never-expiring,
  `min(BASE_TTL, remaining)` for expiring (zero-jitter to never exceed expiry), never caches an
  already-expired link. Service also refuses to serve a logically expired cached value on the read
  path (defense-in-depth; the Redis TTL already evicts at/before expiry).
- **Step 7 (V1) — done.** New `MetricsPort` hooks + counters in `MicrometerMetricsAdapter`:
  `urls.expired.total`, `schema.migrations.applied.total`, `schema.migrations.failed.total` (fixed
  `service=url-shortener` tag). `MetricsIT` (2 tests) verifies registration and expiry counting.
- **Verification:** `mvn test` → **190 unit tests green**; `mvn verify -Dit.test=<class>` →
  `SchemaMigrationIT`, `MetricsIT`, `ShortenFlowIT` (9), `ExpiredUrlIT` (4) all green. Full
  integration suite (all `*IT` + E2E) passes with the migrator active.

## Evidence notes

- `Spring Boot 4.x` does **not** fix Flyway-for-MongoDB (Boot 4 only modularized Flyway; its
  `FlywayConnectionDetails` is JDBC-only; Flyway V12 removed the Mongo JDBC connector).
- TTL index on `expiresAt` means a purged expired row yields `404`; a not-yet-purged row yields
  `410` — both are correct, and the E2E tolerance (`anyOf(410, 404)`) reflects that.

---

_Pre-implementation sequence. Preserve deviations and final evidence as an as-built record after delivery._
