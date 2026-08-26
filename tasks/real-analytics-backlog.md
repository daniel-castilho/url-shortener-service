# Real Analytics — Backlog
## Persist click events, atomic `$inc` counter, durable queue

**Priority:** P1 — product value. Third epic.
**All stories:** Must.
**Companions:** `real-analytics-spec.md` · `real-analytics-implementation-sequence.md`

**Execution status:** ready from `main` (`d8a2a9c`, `v0.2.0`).

---

## Epic outcome

Click analytics is real: every redirect enqueues an event onto a **durable queue** that survives
restarts and bursts, a worker **persists** them to `click_events` and increments an **atomic `$inc`
`clickCount`** per short code, and the redirect path stays fast and non-blocking. No click is silently
lost.

---

## Story map

```text
FOUNDATION
A1  clickCount field on ShortUrl + ShortUrlEntity (default 0)
A2  Atomic $inc persistence method on the URL repository

CLICK PIPELINE
P1  Durable queue adapter (Redis Stream) behind AnalyticsPort
P2  ClickBatchWorker: persist to click_events + $inc clickCount + ack
P3  Idempotent-at-least-once + backpressure + Redis-outage fail-open
P4  click_events document collection + IndexMigration indexes

VERIFICATION & DELIVERY
V1  Analytics IT + metrics + no-blocking-redirect proof
V2  Documentation sync (data-model-decisions, coding-standards, testing-playbook, AGENTS, README)
```

---

## A1 — clickCount field on ShortUrl + ShortUrlEntity

**Goal:** every short URL can carry a running click count.

### Work

- add `long clickCount` (default 0) to the `ShortUrl` record and `ShortUrlEntity`;
- update the mapper (`ShortUrlMapper`) to carry it across domain ↔ entity;
- ensure the existing `ShortUrl(...)` constructors keep backward compatibility (add an overload or
  default).

### Acceptance

- [ ] `ShortUrl` and `ShortUrlEntity` have `clickCount` (default 0).
- [ ] Mapper round-trips it; existing uses of the shortened constructors still compile.

---

## A2 — Atomic `$inc` persistence method

**Goal:** increment the counter without losing updates under concurrency.

### Work

- add `incrementClickCount(String code)` to the domain `UrlRepositoryPort` (or a narrow analytics port);
- implement with `$inc` on `MongoTemplate.updateFirst` keyed by `_id`;
- never read-modify-write.

### Acceptance

- [ ] Concurrent increments are not lost (real `$inc`, not `set(get()+1)`).
- [ ] A missing code does not create a row (no-op or explicit).

---

## P1 — Durable queue adapter (Redis Stream)

**Goal:** replace the in-memory, drop-on-full queue with a durable stream.

### Work

- implement a Redis Stream `AnalyticsPort` adapter (`RedisClickEventQueue`) using Spring Data Redis /
  Lettuce (`XADD` with `MAXLEN ~`);
- make `track()` non-blocking and fail-open (log + metric on Redis failure; never throw into the
  redirect);
- wire it as the `AnalyticsPort` bean.

### Acceptance

- [ ] Events are XADDed and survive a consumer/restart.
- [ ] `track()` never blocks or throws into the redirect path.
- [ ] On Redis outage it degrades (logs a `dropped` metric) rather than failing the redirect.

---

## P2 — ClickBatchWorker: persist + `$inc` + ack

**Goal:** drain the stream, persist events, update the counter, ack (at-least-once).

### Work

- replace the log-only `ClickBatchWorker` with a consumer that:
  - reads in batches (`XREADGROUP`, or an equivalent reliable poll);
  - **bulk-inserts** `ClickEventDocument` rows into `click_events`;
  - **`$inc` `clickCount`** once per unique code in the batch;
  - **acks** the batch; on partial failure, retries with bounded backoff and logs, then finalizes.
- keep the scheduled/background execution (the existing `@Scheduled` is acceptable, or switch to a
  consumer-group loop — document the choice).

### Acceptance

- [ ] A redirect results in a persisted `click_events` row and an incremented `clickCount`.
- [ ] Delivery is at-least-once; a retried batch does not crash and is idempotent per the documented
      choice.
- [ ] Mongo unavailability does not crash the worker (retry + bounded backoff).

---

## P3 — Idempotency, backpressure, fail-open

**Goal:** the pipeline is reliable and never blocks or breaks the redirect.

### Work

- bounded stream length (`MAXLEN ~`) to prevent unbounded growth;
- explicit at-least-once semantics; optionally an idempotency key on the event for strict uniqueness
  (document the choice);
- fail-open on Redis outage (analytics is non-critical).

### Acceptance

- [ ] Queue is bounded; no unbounded memory/stream growth.
- [ ] A Redis outage is logged as a metric and the redirect still works.
- [ ] The pipeline never surfaces an analytics failure to the user.

---

## P4 — click_events collection + indexes

**Goal:** persist events with queryable indexes.

### Work

- add `ClickEventDocument` (Mongo) in `infra/adapter/output/persistence/entity`;
- add a repository/port to bulk-insert events;
- add indexes via `IndexMigration` (idempotent): `(shortCode, timestamp)`, `(timestamp)`.

### Acceptance

- [ ] `click_events` collection exists with the schema.
- [ ] Indexes are created idempotently (no duplicate-index error on repeat).
- [ ] Bulk insert works against a real Mongo (IT).

---

## V1 — Analytics integration test + metrics + no-blocking proof

**Goal:** prove the pipeline end-to-end and that the redirect is unaffected.

### Work

- IT: redirect → event persisted + `clickCount` incremented;
- IT: Redis down → redirect still `302` (fail-open) and a `dropped` metric is recorded;
- IT: concurrency — many concurrent redirects to the same code increment by exactly N;
- add the low-cardinality metrics (`analytics.events.*`) and a no-sensitivity check (no IP/JWT/raw
  key as a high-cardinality tag).

### Acceptance

- [ ] The full pipeline IT is green (event persisted, `clickCount` correct).
- [ ] Fail-open on Redis outage is proven.
- [ ] Concurrent redirects produce exactly N increments.
- [ ] Metrics are present with fixed tags; no sensitive high-cardinality tag.

---

## V2 — Documentation sync

**Goal:** docs reflect applied analytics.

### Work

- `data-model-decisions.md` — move click analytics from target to applied, add `click_events` schema +
  `$inc` + Redis-Stream decision;
- `coding-standards.md` — analytics pipeline rules;
- `testing-playbook.md` — add analytics ITs to suite map / gaps;
- `AGENTS.md` — clear debt items 5 and 15;
- `README.md` — note persisted click tracking + atomic count.

### Acceptance

- [ ] No document describes the log-only worker or the drop-on-full queue as current.
- [ ] `AGENTS.md` marks items 5/15 resolved.

---

## Epic Definition of Done

- [ ] A1–A2 complete: `clickCount` on domain/entity + atomic `$inc` in the repository.
- [ ] P1–P4 complete: durable Redis-Stream queue, worker persists + `$inc` + ack, fail-open/backpressure,
      `click_events` + indexes.
- [ ] V1–V2 complete: IT + metrics + no-blocking-redirect proven; docs and `AGENTS.md` synced.
- [ ] `mvn test`, `mvn verify` and the analytics `*IT` pass.
- [ ] The redirect path never blocks or throws on analytics failure.
- [ ] No `@Transactional` straddling Mongo and Redis; the `$inc` counter is race-free.
