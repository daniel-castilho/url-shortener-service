# AI Software Engineer Prompt — Real Analytics
## Persist click events, atomic `$inc` counter, durable queue

**Status:** ready for implementation from current `main` (`d8a2a9c`, `v0.2.0`).
**Priority:** P1 — product value. Third epic.
**Target:** make click analytics real — persisted events, an atomic `$inc` per-link counter, and a
durable, non-blocking pipeline that never loses click data and never slows the redirect.

You implement the complete **Real Analytics** epic. The redirect path must remain fast and non-blocking;
analytics is a decoupled, out-of-band, at-least-once pipeline.

---

## Sources of truth — read in this order

1. `AGENTS.md` (rules 5, 7, 8, 10; Known Technical Debt items 5 and 15)
2. `docs/data-model-decisions.md` (the "Click analytics" target → you will apply it)
3. `docs/coding-standards.md`
4. `docs/testing-playbook.md`
5. `pom.xml` (Spring Data Redis + Lettuce already present) and `src/main/resources/application.yaml`
6. `tasks/real-analytics-spec.md`
7. `tasks/real-analytics-backlog.md`
8. `tasks/real-analytics-implementation-sequence.md`
9. Current `ClickEvent`, `AnalyticsPort`, `AsyncAnalyticsAdapter`, `ClickBatchWorker`, `UrlController`
   (redirect path), `ShortUrl`/`ShortUrlEntity`, `MongoCollections`, `IndexMigration`

If documentation disagrees with executable configuration, stop, report and resolve in the same change.

---

## Goal

Right now `ClickBatchWorker` only logs and `AsyncAnalyticsAdapter` uses an in-memory queue that
**drops events when full** — so no click is persisted and there is no per-link count. This epic:
1. adds a `clickCount` and increments it atomically (`$inc`);
2. persists click events to a `click_events` collection;
3. replaces the in-memory queue with a **durable queue** (Redis Streams) that survives restarts/bursts.

The epic closes these current gaps (AGENTS items 5 and 15):
- analytics not persisted;
- in-memory queue silently drops events;
- no per-link click count.

---

## Locked technical decisions

1. **Persist clicks.** Dedicated `click_events` collection; the `ClickEvent` domain model is the source.
2. **Atomic `$inc` `clickCount`** on `short_urls`. Never read-modify-write (avoid the `QuotaService`
   bug class).
3. **Durable queue = Redis Streams** (Spring Data Redis / Lettuce, already on classpath). **No new
   Maven coordinate**; no alternative broker without explicit approval.
4. **Non-blocking & fail-open.** `track()` in the redirect never blocks or throws. On Redis outage, log
   + increment `analytics.events.dropped.total` and let the redirect proceed.
5. **At-least-once delivery.** A retried batch may duplicate an event; accept at-least-once (and add an
   idempotency key if strict uniqueness is desired — document the choice). The worker never crashes the
   app on Mongo failure (bounded retry/backoff).
6. **Bounded queue.** Redis Stream `MAXLEN ~` so it cannot grow unbounded.
7. **No `@Transactional` across Mongo + Redis.** The pipeline is decoupled and retried/compensated.
8. **No redirect contract change.** Still `302` + single DB read (cache-aside). Analytics is async.

---

## Non-negotiable engineering rules

- Keep `core/` framework-free: `AnalyticsPort`/`ClickEvent` stay in `core/`; all queue/Mongo work is
  `infra/`.
- Never block or throw in the redirect path because of analytics.
- Always `$inc`; never read-modify-write a counter.
- Bounded queue + bounded retries; no unbounded growth or crash loops.
- No sensitive high-cardinality tag (no user ID, IP, or raw code as a metric tag); fixed tags only.
- Do not add a Maven coordinate without explicit approval.
- English only in code, comments, logs, tests and docs.
- Do not push unless the human explicitly asks.
- Do not expand into link expiry (TTL), rate-limit on redirect, geo/device enrichment, dashboards, or a
  search/aggregation API — those are out of scope (or separate epics).

---

## Required behaviour summary

### Data model
- `ShortUrl` and `ShortUrlEntity`: add `long clickCount` (default 0); update the mapper.
- URL repository: `incrementClickCount(String code)` implemented with `$inc`.
- New `click_events` `ClickEventDocument` + bulk-insert repository.
- `IndexMigration`: `(shortCode, timestamp)` and `(timestamp)` indexes (idempotent).

### Pipeline
- `RedisClickEventQueue implements AnalyticsPort` — `XADD` with `MAXLEN ~`; non-blocking, fail-open.
- Consumer (replaces log-only `ClickBatchWorker`) reads a batch → bulk-insert to `click_events` +
  `$inc` `clickCount` per unique code → ack; bounded retry/backoff on Mongo failure.

### Metrics (low-cardinality, fixed tags)
- `analytics.events.enqueued.total`
- `analytics.events.persisted.total`
- `analytics.events.failed.total`
- `analytics.events.dropped.total`

### API
- No outward change; redirect stays `302` + one DB read.

---

## Scope exclusions

Do not implement: link expiry (TTL), rate limiting on the redirect path, geo/device/referrer enrichment
beyond the existing `ClickEvent`, dashboards/UI, a search/aggregation API over clicks, or any redirect
contract change.

---

## Definition of Done

### Data model
- [ ] `clickCount` on domain/entity + mapper round-trip.
- [ ] Atomic `$inc` increment method; no read-modify-write.
- [ ] `click_events` collection exists with indexes.

### Pipeline
- [ ] Durable queue (Redis Stream) replaces the log-only/in-memory path.
- [ ] Worker persists events to `click_events` and `$inc` `clickCount`, then acks.
- [ ] `track()` is non-blocking and fail-open; Redis outage → redirect still `302`, `dropped` metric.
- [ ] Bounded queue and bounded retries; at-least-once delivery; worker never crashes the app.

### Verification & delivery
- [ ] Analytics `*IT` green (persist + count + concurrency + fail-open).
- [ ] Metrics present with fixed tags; no sensitive high-cardinality tag.
- [ ] No `@Transactional` across Mongo and Redis; `$inc` race-free.
- [ ] `mvn test`, `mvn verify` and the analytics `*IT` pass.
- [ ] `data-model-decisions.md`, `coding-standards.md`, `testing-playbook.md`, `README.md` and
      `AGENTS.md` (items 5/15) are synced; no doc describes the log-only worker or drop-on-full queue.

Start at **Step 0** of `real-analytics-implementation-sequence.md`. Stop immediately if the baseline is
red, a locked decision cannot be implemented with the approved dependency graph, or repository state
contradicts the specification.
