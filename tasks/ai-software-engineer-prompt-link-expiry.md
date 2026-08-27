# AI Software Engineer Prompt — Link Expiry (TTL) & Versioned Schema Migration

**Status:** ready for implementation from current `main` (`0579f5d`).
**Priority:** P1 — product value + operational hygiene. Fourth epic.
**Target:** add optional link expiration (TTL) and replace the ad-hoc `IndexMigration` with a versioned,
ordered, idempotent, fail-fast schema-migration runner — without changing the redirect contract beyond
distinguishing "expired" (410) from "not found" (404).

You implement the complete **Link Expiry & Versioned Schema Migration** epic. Analytics (v0.3.0),
redirect rate-limit (v0.4.0) and SSRF/actuator hardening (v0.5.0) are already shipped. This epic adds
the expiry feature and the migration mechanism.

---

## Sources of truth — read in this order

1. `AGENTS.md` (rules 4, 5, 7, 8, 10; Known Technical Debt item 10)
2. `docs/data-model-decisions.md` (the "Link expiry / TTL" target → apply it)
3. `docs/coding-standards.md`
4. `docs/testing-playbook.md`
5. `pom.xml` and `src/main/resources/application.yaml`
6. `tasks/link-expiry-spec.md`
7. `tasks/link-expiry-backlog.md`
8. `tasks/link-expiry-implementation-sequence.md`
9. `ShortUrl`/`ShortUrlEntity`, `ShortUrlMapper`, `UrlShortenerService` (read path), `UrlController`
   (redirect), `RedisUrlCache`, `MongoCollections`, `IndexMigration`, `GlobalExceptionHandler`

If documentation disagrees with executable configuration, stop, report and resolve in the same change.

---

## Goal

Short URLs can expire. Today: (1) there is no `expiresAt`, so a link lives forever; (2) `IndexMigration`
is an idempotent `@PostConstruct` that is not versioned (debt item 10). This epic adds `expiresAt` +
a MongoDB TTL index, makes the redirect return `410` for expired links (distinct from `404`), makes the
cache expiry-aware so an expired link is **never** served, and introduces a versioned migration runner.

---

## Locked technical decisions

1. **`expiresAt` nullable.** `null` = never expires. On the domain record, the entity and the mapper.
2. **Optional TTL input.** Recommended `ttlSeconds` (capped by `app.shortener.max-ttl-seconds`), or an
   absolute `expiresAt` ISO-8601. Past/zero/over-cap → `400`. `null` → never expires.
3. **`410 Gone` for expired, `404` for not-found.** The read path distinguishes the two; an expired link
   never returns `302`.
4. **Expiry is application-logic truth.** The redirect checks `expiresAt` eagerly; the MongoDB TTL index
   only purges later (~60s cadence). Never rely on TTL to enforce expiry.
5. **Cache is expiry-aware.** Store `expiresAt` in the cached value (or don't cache TTL links), so a
   warm cache never serves an expired link. No perf regression for non-expiring links.
6. **Versioned migration runner** replaces `IndexMigration`. `schema_migrations` collection tracks
   applied migrations; each migration is idempotent, applied in ascending order, and startup aborts on
   failure. **No new Maven coordinate** (implement in-code) unless the human explicitly approves.
7. **Framework-free `core/`.** Expiry predicate and read result live in `core/`; the migration runner and
   TTL index are `infra`.

---

## Non-negotiable engineering rules

- Keep `core/` free of Spring, Mongo, Redis, JWT and `infra.*` types.
- The redirect stays fast (cache-aside + one DB read) and never blocks on analytics.
- Never serve an expired link from a warm cache — this is the easy regression to miss.
- Distributions: expired ≠ not-found (410 vs 404).
- Migrations must be idempotent, ordered, and fail-fast; never subtract data during a migration without
  an explicit rollback/backup plan.
- No new Maven coordinate without approval.
- English only in code, comments, logs, tests and docs.
- Do not push unless the human explicitly asks.
- Do not expand into analytics, rate-limit, geo/device enrichment, dashboards, a read/statistics API, or
  a multi-tool migration framework (Liquibase etc.) without approval.

---

## Required behaviour summary

### Data model
- `ShortUrl` + `ShortUrlEntity`: `Instant expiresAt` (nullable); mapper round-trips it; `withExpiresAt`
  overload.
- Domain predicate `isExpired(now)`.

### Shorten input
- Optional `ttlSeconds` or `expiresAt`; capped; `400` on past/zero/over-cap; `null` = never.

### Read path
- Expired → `410 Gone`; not-found → `404`; valid → `302`.
- Cache stores `expiresAt` and does not serve an expired entry.

### Migrations
- `schema_migrations` + `Migration` interface + runner; port existing index logic (drop `originalUrl_1`,
  `userId`, `click_events` indexes) and add the `expiresAt` TTL index; retire `IndexMigration`.

### Metrics
- `urls.expired.total`, `schema.migrations.applied.total`, `schema.migrations.failed.total` (fixed tags).

---

## Scope exclusions

Do not implement: click analytics, rate-limit on redirect, SSRF/URL hardening, actuator/Swagger lockdown,
geo/device enrichment, dashboards, a read/statistics API, a multi-tool migration framework, or any
redirect HTTP-contract change beyond the 410/404 distinction.

---

## Definition of Done

### Expiry model
- [ ] `expiresAt` on domain/entity + mapper + `withExpiresAt`.
- [ ] Optional TTL input with bounds; `400` on invalid; `null` = never.

### Read path & cache
- [ ] Expired → `410`; not-found → `404`; valid → `302`.
- [ ] Cache is expiry-aware; an expired link is never served from a warm cache.
- [ ] `core/` stays framework-free (read result/exception are pure).

### Migrations
- [ ] Versioned runner applies pending migrations once, in order, idempotently; fail-fast on error.
- [ ] Existing indexes ported; `expiresAt` TTL index added; `IndexMigration` retired.

### Verification & delivery
- [ ] Expiry + migration IT matrix green (incl. cache-expiry, apply-once, fail-fast).
- [ ] `mvn test`, `mvn verify` and the `*Expiry*IT`/`*Migration*IT` pass.
- [ ] `data-model-decisions.md`, `coding-standards.md`, `testing-playbook.md`, `README.md` and
      `AGENTS.md` (item 10) are synced; no doc describes IndexMigration or "links never expire".

Start at **Step 0** of `link-expiry-implementation-sequence.md`. Stop immediately if the baseline is red,
a locked decision cannot be implemented with the approved dependency graph, or repository state
contradicts the specification.
