# Data Model Decisions

Record of the single-source-of-truth decisions taken for the URL Shortener Service. Keep this file in
sync whenever the data model changes.

> **Identity model (locked):** random Base62 codes, **no URL dedup**, namespace isolation, `409` =
> alias conflict only. That is the product contract. All entries (analytics persistence, link
> expiry, atomic quota `$inc`, email uniqueness) are applied.

---

## Short URL ↔ short code (identity & uniqueness)

- **Source of truth:** the `short_urls` collection (`MongoCollections.SHORT_URLS`), keyed by the **code**
  as `_id`.
- **Code generation — decision: random Base62, no counter, no Hashids.** Short codes are generated from
  `java.security.SecureRandom` over a 62-char alphabet (`0-9 A-Z a-z`), default length **7**
  (configurable via `app.shortener.code-length`). Collisions are resolved by **retrying** on the
  `_id` `DuplicateKeyException` (bounded retries) — never by reusing or dropping a code.
  - _Code landing: stories I1–I2 (`AGENTS.md` debt item 3). Until those land, `IdGeneratorPort` may
    still be backed by a leftover adapter; that adapter is not the identity model._
- **Code is the public identifier; no separate internal `id` is needed.** Because codes are generated
  randomly and stored as `_id`, there is no sequential UUID to leak, so a dedicated internal ID is not
  required. If a sequential id is later wanted for ordering/audit, add an explicit `id` column and keep
  `code` as the lookup key (never re-encode an existing code).

## URL deduplication — decision: **NO**

- The **same** long URL may be shortened multiple times, producing **distinct** codes.
- There is **no `UNIQUE` index on `originalUrl`.**
- **`409 Conflict` means only "custom alias already exists"** — never "URL already shortened".
- A repeated URL is simply a new row with its own code.
- **Future analytics (optional):** store a SHA-256 **`urlHash`** to enable de-duplicated aggregate
  queries later, and add a **non-unique** index on it only if querying by URL becomes a requirement.

## Custom (vanity) alias ↔ generated code — namespace isolation

- Auto-generated codes and user vanity aliases **never collide**.
- **Reserved words** (`api`, `auth`, `health`, `admin`, `v1`, `swagger`, `metrics`, `actuator`,
  `login`, ...) are rejected as alias/code via `ReservedWordsValidator` — protects system routes.
- **Structural separation:** generated codes are **exactly** `app.shortener.code-length` chars from a
  pure Base62 alphabet; vanity aliases enforce a different rule set (regex `[a-zA-Z0-9-_]+`, a minimum
  length per plan, and an explicit `existsById` check). This keeps the two namespaces disjoint.
- **Collision safety:** alias creation relies on the atomic `_id` insert, **not** a check-then-put
  (which races). A concurrent duplicate alias resolves to a single `409`.
- `CompositeUrlIdGenerator` selects `RandomUrlIdStrategy` (no alias) vs. `VanityUrlIdStrategy`
  (alias). Alias uniqueness is the atomic `_id` insert (not check-then-put). Structural isolation
  by length/character set is story I3 (`AGENTS.md` debt item 7).

## User ↔ short URLs

- **Source of truth:** `short_urls` carries an optional `userId` (`@Indexed`). The `User` document
  does **not** embed a list of short URLs — no divergent collection to keep in sync.
- Anonymous short-creates are allowed (`userId = null`); vanity aliases require an authenticated user.
- Listing a user's links (if later added) queries `short_urls` by the `userId` index — never a user
  embedded collection.

## Subscription plan ↔ quota (vanity URLs)

- **Quota is per-user, per-plan.** `SubscriptionPlan` (`FREE` / `SILVER` / `GOLD` / `DIAMOND`) defines
  `vanityUrlsPerMonth` (-1 = unlimited) and `minAliasLength`. `QuotaUsage` tracks per-month and total
  vanity URLs created.
- **Enforcement:** `QuotaService.checkVanityUrlQuota` and `User.canCreateVanityUrls()` gate creation;
  quota is checked before write and incremented after.
- **Atomicity — decision: `$inc` — LOCKED for v0.3.0.** The "created this month" / "created total"
  counters are incremented with an **atomic `$inc`** on the user document (targeted update), never
  read-modify-write. `incrementVanityUrlUsage` moves from `set(get()+1)` to `$inc`
  (`AGENTS.md` debt item 14, cleared alongside the click-pipeline epic).
- **Lazy monthly reset:** `QuotaUsage.needsReset()` resets the monthly counter on first access.

## Click analytics

- **Decision: persisted, out-of-band, atomic — LOCKED for v0.3.0.** Click events are written to a
  dedicated `click_events` collection **asynchronously** (never in the redirect path). The
  `short_urls` row carries a `clickCount` incremented **atomically** via `$inc`.
- **Durable queue = Redis Streams** (`XADD`, bounded via `MAXLEN ~`) through Spring Data Redis /
  Lettuce — **no new Maven coordinate**; any alternative broker requires explicit approval and must
  stay behind `AnalyticsPort`.
- **Delivery semantics = at-least-once, without an idempotency key** (locked). A retried batch may
  duplicate a rare event row; `clickCount` stays exact because the worker increments once per unique
  code per batch. Strict uniqueness is explicitly rejected for now.
- **Timestamp type:** `click_events.timestamp` is stored as **`Instant` (UTC)**, converted at the
  adapter boundary; the domain `ClickEvent` keeps `LocalDateTime` unchanged.
- **Policy:** analytics is **fire-and-forget + fail-open** — a Redis outage logs and counts a
  `dropped` metric; it never blocks or fails the redirect.
- Store the minimum needed for aggregates: `shortCode`, `timestamp`, `ip`, `userAgent`
  (`ClickEvent`). Optional enrichments (geo, referrer, device) are additive columns on
  `click_events`.
- **Policy:** analytics data is subject to retention (e.g. purge events older than N days) and
  **never logs raw credentials** or full destinations containing secrets.

## Link expiry / TTL

- **Decision:** add `expiresAt` (nullable; `null` = never expires) to a short URL. Stored as an
  `Instant` on the domain record; a domain predicate `isExpired(now)` is the source of truth.
- Use a **MongoDB TTL index** on `expiresAt` (`expireAfter(0, SECONDS)`) so expired links are purged
  automatically by the database (cadence ~60 s).
- **Expiry is application-logic truth, not DB-TTL truth.** The redirect path checks `expiresAt`
  eagerly — a not-yet-purged expired row must still be treated as expired.
- **The redirect returns `410 Gone` for an expired link, `404` for a not-found code, `302` for a
  valid link.** An expired link must **never** be redirectable (not even from a warm cache).
- **The cache is expiry-aware:** the cached value holds `{ originalUrl, expiresAt }`; on read, if the
  entry is logically expired it is treated as expired (and evicted) rather than served.
- **Migration mechanism: in-code versioned runner** replaces the ad-hoc `IndexMigration`
  `@PostConstruct`. Migrations are plain Java classes implementing `SchemaMigration`
  (`infra/adapter/output/persistence/migration`), applied on startup by `MongoSchemaMigrator` in
  ascending version order, once, idempotently, checksummed (SHA-256 of the class name) and recorded in
  the `schema_migrations` history collection. Failure aborts startup (fail-fast).
  `spring.data.mongodb.auto-index-creation` is disabled so indexes are managed only by migrations.
- **Why not Flyway:** community Flyway-for-MongoDB is not usable here — the JDBC driver it depends on
  (`com.github.kornilova203:mongo-jdbc-driver`) is not published to Maven Central (falls back to a
  private GitHub Packages repo → 401), the native-connector mode is CLI-only (requires `mongosh`,
  which is not installed) and its Java API artifact is unpublished, and Spring Boot 4.x does not
  change any of this. **Explicit human approval** granted for the spec's default in-code runner after
  the experiment failed.
- **Epic status: applied (link-expiry epic).** `expiresAt` (nullable), TTL index, the `410`/`404`
  distinction, the expiry-aware cache and the versioned in-code migration runner are all live
  (`V1`–`V5` migrations: baseline, drop `originalUrl_1` unique, `userId`, `click_events` indexes,
  `expiresAt` TTL).

## Registry of indexes (applied)

| Collection     | Index                      | Type      | Purpose                                            |
| -------------- | -------------------------- | --------- | -------------------------------------------------- |
| `short_urls`   | `_id`                       | unique    | Code identity + retry-on-collision                 |
| `short_urls`   | `userId`                    | non-unique | User link listing                                  |
| `short_urls`   | `expiresAt`                 | TTL       | Auto-purge expired links (applied via migration V5)   |
| `short_urls`   | `urlHash`                   | non-unique | Optional URL aggregate queries (future)            |
| `click_events` | `shortCode` + `timestamp`   | non-unique | Aggregate/retention queries (applied)              |
| `click_events` | `timestamp`                 | non-unique | Retention purge (applied)                          |
| `users`        | `_id`                       | unique    | User identity                                     |
| `users`        | `email`                     | unique    | Email uniqueness (registration guard)              |
| `users`        | `plan`                      | non-unique | Plan-based queries                                 |
| `users`        | `createdAt`                 | non-unique | Time-based queries                                 |

- Indexes are managed via the **in-code versioned migration runner** (`MongoSchemaMigrator`, history
  in `schema_migrations`; not `auto-index-creation`), so removal of the `originalUrl` unique index and
  addition of the TTL index are deterministic and auditable. Migrations: `V1Baseline` (create
  `short_urls`/`click_events`), `V2DropOriginalUrlUniqueIndex`, `V3EnsureUserIdIndex`,
  `V4EnsureClickEventsIndexes`, `V5AddExpiresAtTtlIndex`, `V6EnsureUserIndexes`
  (`src/main/java/ca/tyny/urlshortener/infra/adapter/output/persistence/migration`).
  `AGENTS.md` debt item 10 resolved.

## Registration (email uniqueness)

- **Source of truth:** the `users` collection with a **unique index on `email`** (normalized), applied
  via migration `V6EnsureUserIndexes`.
- Registration writes the user and relies on the unique index; a concurrent second registration with
  the same email is rejected atomically by the DB (not by a pre-check).
- Passwords stored as BCrypt hash only — never plaintext, never logged.

## Multi-write / partial failure

- **Never wrap Mongo + Redis in one `@Transactional`** — they are separate systems; a transaction
  would give a false sense of atomicity.
- Cross-system operations sequence: **write to the system of record first, then best-effort cache
  update.** If the cache fails, the next read falls back to the DB.
- Analytics writes are decoupled from the redirect and can be retried/compensated independently.

## Rollout / rollback order

- Identity-model index/code changes (drop `originalUrl` unique; Base62 generator) are **not**
  optional product rules — they are the locked contract. Order for remaining landing:
  1. Switch ID generation to random Base62 (keep `_id` unique).
  2. Drop the `originalUrl` unique index; add `urlHash` (non-unique).
  3. Later epics: `expiresAt` TTL, `click_events`, atomic `clickCount`.
- Rollback of additive columns/indexes is reverse-adoption order; dropping a new index/collection
  never corrupts existing domain data keyed by `_id`.
