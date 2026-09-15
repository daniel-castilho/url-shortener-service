# Link Expiry (TTL) & Versioned Schema Migration — Technical Specification

**Status:** ready for implementation from current `main` (`0579f5d`).
**Priority:** P1 — product value + operational hygiene. Fourth epic.
**Companions:** `link-expiry-backlog.md` · `link-expiry-implementation-sequence.md`

---

## 1. Purpose

Two related gaps remain after Analytics (v0.3.0), redirect rate-limit (v0.4.0) and SSRF/actuator
hardening (v0.5.0):

1. **Link expiration does not exist.** A short URL lives forever; there is no way to set an expiry, no
   TTL index, and no distinction between "never existed" (404) and "was valid but expired".
2. **Schema/index management is ad-hoc, not versioned.** `IndexMigration` is an idempotent
   `@PostConstruct` that runs on every boot — it is not a versioned, ordered, replayable migration, and
   it already manages three collections' indexes by hand. This is `AGENTS.md` debt item 10.

This epic adds **link expiry (TTL)** and a small **versioned schema-migration runner** so schema
changes are applied deterministically, once, in order, and are auditable.

---

## 2. Scope

### In scope

- **`expiresAt`** on a short URL (nullable; `null` = never expires), on the domain model, the entity and
  the mapper.
- **Input contract**: allow an optional expiry in the shorten request (e.g. `ttlSeconds` or
  `expiresAt`), bounded to a maximum, validated.
- **MongoDB TTL index** on `expiresAt` so expired links are purged automatically.
- **Redirect path expiry awareness** — the read path must not serve an expired link (from cache *or*
  DB), and must distinguish **expired** from **not-found**.
- **A versioned schema-migration runner** (replaces the ad-hoc `IndexMigration` logic) that tracks
  applied migrations, runs each once and in order, and is idempotent.
- Observability/metrics for expiry and migration.
- Docs sync (`data-model-decisions.md`, `coding-standards.md`, `testing-playbook.md`, `README.md`,
  `AGENTS.md` debt items 10).

### Out of scope

- click analytics / counters (done in v0.3.0);
- rate limiting on the redirect path (done in v0.4.0);
- SSRF/URL validation (done in v0.5.0);
- actuator/Swagger lockdown (done in v0.5.0);
- geo/device enrichment, dashboards, a read/statistics API;
- introducing a Maven coordinate for migration (e.g. mongock/flyway) **without explicit approval**;
- a full multi-tool migration framework (Liquibase etc.) unless approved.

---

## 3. Architectural constraints

- `core/` stays framework-free. The `expiresAt` field and any "is expired" domain predicate live in
  `core/`.
- The migration runner and the TTL index are `infra` concerns; the collection-name constants stay in
  `core` (via `MongoCollections`), but the execution is `infra`.
- The redirect path stays **fast** (cache-aside + one DB read) and must not block on analytics.

---

## 4. Data model

### 4.1 `short_urls` — add `expiresAt`

Add `Instant expiresAt` (nullable) to the domain `ShortUrl` record and `ShortUrlEntity`:

- `null` = never expires.
- Non-null = the link is valid only until `expiresAt`.
- Add a constructor overload `withExpiresAt` / include it in the canonical record so existing
  call sites compile.

Mapper: carry `expiresAt` across domain ↔ entity.

### 4.2 TTL index

MongoDB **TTL index** on `expiresAt`:

```java
new Index("expiresAt", Sort.Direction.ASC).expireAfter(0, TimeUnit.SECONDS)
```

Using `expireAfter(0, SECONDS)` makes MongoDB delete the document at `expiresAt`. Only documents
with a non-null `expiresAt` are affected. Add this to the versioned migration (Step 3 of the sequence).

> **Important:** a TTL index deletes the row automatically but the **redirect path must still check
> expiry eagerly** — MongoDB TTL runs roughly every 60 s, so a row may not be physically gone yet.
> The application logic (not the DB) is the source of truth for "expired".

### 4.3 Index registry (target after this epic)

| Collection     | Index                        | Type         | Purpose                              |
| -------------- | ---------------------------- | ------------ | ------------------------------------ |
| `short_urls`   | `_id`                        | unique       | identity                             |
| `short_urls`   | `userId`                     | non-unique   | user link listing                    |
| `short_urls`   | `expiresAt`                  | **TTL**      | auto-purge expired links             |
| `short_urls`   | `urlHash`                    | non-unique   | future aggregate queries             |
| `click_events` | `(shortCode, timestamp)`     | non-unique   | aggregation                          |
| `click_events` | `(timestamp)`                | non-unique   | retention prune                      |

---

## 5. Input contract (shorten)

The shorten request accepts an **optional** expiry. Two options — pick one (recommend **A**):

- **A. `ttlSeconds`** (long, optional, positive, capped) → converted to `expiresAt = now + ttlSeconds`.
  Simpler for clients; bounded by a server config `app.shortener.max-ttl-seconds`.
- **B. `expiresAt`** (ISO-8601 `Instant`) → exact absolute time; must be in the future.

**Bounded:** a server-configured maximum TTL caps both (e.g. `maxTtlSeconds`). Values over the cap or
in the past are rejected with `400`. The TTL is **not** required to be enforced for anonymous vs.
authenticated beyond the cap; plan-based limits (if desired) are out of scope.

Do **not** change the existing `ShortenResponse`; optionally add the resolved `expiresAt` to the
response.

---

## 6. Read path (redirect) — expiry awareness

This is the subtle part. The current `${cache}.get(id)` returns a **String URL** and the DB path
returns the URL; neither is expiry-aware, so an expired-but-not-yet-purged link could still be served
(including from a warm cache).

**Required behaviour:**

- The redirect must return **`410 Gone`** for an expired link (distinct from `404` not-found).
- **The cache must not serve an expired link.** Options:
  - **Recommended:** keep the URL in cache but also store the `expiresAt` (cache a small value
    `{ url, expiresAt }`), and at read, if `expiresAt` is in the past, treat as expired (don't redirect)
    and optionally evict.
  - Alternative (simpler but less clean): on a cache hit you can't tell if the link expired — so the
    read path must either (a) not cache links with an expiry, or (b) always validate expiry against the
    DB (loses the cache benefit for those). Prefer storing `expiresAt` in the cached value so the cache
    remains correct.

**Domain contract:** introduce a small read result so the caller can distinguish state without leaking
framework types into `core/`:

```java
// core/model — framework-free
public record ShortUrlLookup(String originalUrl, boolean expired) {}
```

`getOriginalUrl` becomes `shortUrlLookup(id)` returning this (or the service throws a domain
`UrlExpiredException`). If you keep `getOriginalUrl` returning `String`, the service must instead throw
a dedicated `UrlExpiredException` for expired links.

The controller maps:

| State            | Response |
| ---------------- | -------- |
| valid            | `302` → original URL |
| expired          | `410 Gone` + message |
| unknown code     | `404`    |

---

## 7. Versioned schema migration

Replace the ad-hoc `IndexMigration` `@PostConstruct` with a small, deterministic, versioned runner.

### 7.1 Design (no new dependency)

- A `schema_migrations` collection records applied migrations: `{ version, name, appliedAt }`.
- A `SchemaMigrationRunner` (or `MigrationConfig`) runs on startup **before** serving traffic:
  - reads the latest applied version;
  - applies each pending migration **in ascending version order**;
  - records it in `schema_migrations` idempotently (dedupe by version).
- Each migration is a small, self-contained class implementing a `Migration` port/interface:
  ```java
  public interface Migration { int version(); String name(); void apply(MongoTemplate t); }
  ```
- Migrations must be **idempotent** (safe to re-run on partial failure) and **additive where possible**.
- If a migration fails, startup aborts (fail-fast) rather than continuing in a half-migrated state.

### 7.2 Migrations to port from the current `IndexMigration`

1. `null`: baseline schema (no-op; establishes the framework / initial collection creation if needed).
2. `2`: drop `originalUrl_1` unique index.
3. `3`: ensure `userId` index.
4. `4`: `click_events` `(shortCode, timestamp)` + `(timestamp)` indexes.
5. `5`: **`expiresAt` TTL index** (new in this epic).

> This epic adds the **runner** and migration **5**; migrations 2–4 are ported from the existing
> `IndexMigration` so nothing is lost. The `IndexMigration` class is removed/retired in favor of the
> runner.

### 7.3 Decision on a migration library

A third-party lib (mongock, flyway-mongodb) gives more features (history, checksums, locking) but is a
**new Maven coordinate**. Per the conventions ("no new dependency without approval"), implement the
lightweight in-code runner in this epic — **unless** you explicitly approve a library. Record the
decision and don't silently add one.

---

## 8. Observability

Add low-cardinality metrics:

```text
urls.expired.total                          # redirects that returned 410 (expired)
schema.migrations.applied.total{version}    # or a counter per migration that ran
schema.migrations.failed.total
```

Fixed tags; no user IP/secret as a dimension. Logs: log `LinkExpired` at `info`/`warn` with the code
(not a secret — it's a public identifier) and the expiry; log migration application/skip at `info`.

---

## 9. Verification commands

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false    # expiry + migration ITs, needs Docker
mvn verify                                      # full gate (unit + *IT + JaCoCo + SpotBugs + jar)
```

---

## 10. Documentation deliverables

- `docs/data-model-decisions.md` — move "Link expiry / TTL" from target to **applied**; add the
  `expiresAt` + TTL index decision and the versioned-migration design; note `410` for expired.
- `docs/coding-standards.md` — expiry validation, cache expiry-awareness, migration-runner rules
  (idempotent, ordered, fail-fast).
- `docs/testing-playbook.md` — add expiry/migration ITs to the suite map and gaps.
- `AGENTS.md` — clear debt item 10 (versioned schema migrations) and note expiry.
- `README.md` — optional `ttlSeconds`, `410` on expired, and the versioned migration note.

The epic is **not** Done while `IndexMigration` (ad-hoc) is still the schema mechanism, or while an
expired link can still be served (including from cache).
