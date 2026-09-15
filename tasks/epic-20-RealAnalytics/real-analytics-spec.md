# Real Analytics — Technical Specification
## Persist click events, atomic `$inc` counter, durable queue

**Status:** ready for implementation from current `main` (`d8a2a9c`, `v0.2.0`).
**Priority:** P1 — product value. Third epic.
**Companions:** `real-analytics-backlog.md` · `real-analytics-implementation-sequence.md`

---

## 1. Purpose

The redirect path currently reports clicks via a **fire-and-forget in-memory queue** that is dropped
when full, and a worker that only logs — so **no click data is persisted**, there is no per-link click
count, and analytics is effectively broken. This epic makes analytics real: click events are persisted
to a dedicated `click_events` collection, the per-link `clickCount` is incremented **atomically**
(`$inc`), and the event pipeline runs through a **durable queue** so it is no longer lost on a restart
or burst.

This is a product feature (P1), built on the foundation the first two epics established (framework-free
`core/`, boundary gate, quality gates).

---

## 2. Scope

### In scope

- a **durable, out-of-band click pipeline** (replaces the in-memory `LinkedBlockingQueue` and the
  log-only worker);
- persist `ClickEvent` records to a new `click_events` collection;
- an **atomic `$inc` `clickCount`** on each `short_urls` row;
- **never block the redirect** — analytics stays async; a single DB read (cache-aside) and a `302` are
  all the redirect path does;
- **retention/backpressure** — the queue is durable and bounded; events are not silently dropped;
- metrics for the pipeline (enqueued, persisted, failed, dropped) and no sensitive data in keys/logs;
- schema/index management for `click_events` (via the existing `IndexMigration` pattern);
- docs sync (`data-model-decisions.md`, `coding-standards.md`, `testing-playbook.md`, `README.md`,
  `AGENTS.md` debt items 5/15).

### Out of scope

- link expiry (`expiresAt` TTL) — separate epic;
- rate limiting on the redirect path — separate epic;
- geo/device/referrer enrichment beyond what `ClickEvent` already has (additive later);
- dashboards/UI for analytics;
- a search/aggregation API over clicks (unless trivially added);
- changing the redirect HTTP contract (still `302` + one DB read).

---

## 3. Architectural constraints

### 3.1 Dependency direction (already enforced, must be preserved)

- `core/` stays framework-free. The analytics port (`AnalyticsPort`) and model (`ClickEvent`) stay in
  `core/`.
- The durable queue and persistence live in `infra/`. Redis Streams (via Spring Data Redis / Lettuce,
  already in the dependency graph) are the recommended durable queue — **no new Maven coordinate**.
- The `ClickBatchWorker` (consumer) and any Redis Stream adapter are `infra` components.

### 3.2 Cross-system consistency

The redirect and analytics are separate concerns. **Never** put a DB write or a blocking
queue-enqueue in the redirect hot path. `@Transactional` is not used across Mongo and Redis; the click
pipeline is decoupled and can be retried/compensated.

---

## 4. Data model

### 4.1 `click_events` collection (new)

A `ClickEventDocument` (Mongo) mirroring the domain `ClickEvent` and adding provenance:

```text
shortCode    String     # the resolved short URL code
timestamp    Instant    # when the event occurred (or when consumed)
userAgent    String
ip           String
consumedAt   Instant    # when the worker actually persisted it (optional)
```

Do **not** store raw credentials; user-agent/IP are accepted per the existing model (see
`data-model-decisions`). Store a reference to the code, not a nested document.

### 4.2 `short_urls` — atomic `clickCount`

Add `long clickCount` (default 0) to `ShortUrl` (domain, immutable) and `ShortUrlEntity`.

Increment via **`$inc`** in the persistence layer:

```java
// repo
Update update = new Update().inc("clickCount", 1);
mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(code)),
        update,
        ShortUrlEntity.class);
```

Never read-modify-write (that loses increments under concurrency — the known `QuotaService` bug class).

### 4.3 Indexes

Add via `IndexMigration` (idempotent):

| Collection     | Index                     | Type         | Purpose                       |
| -------------- | ------------------------- | ------------ | ----------------------------- |
| `click_events` | `shortCode` + `timestamp` | non-unique   | Aggregate / retention queries |
| `click_events` | `timestamp`               | non-unique   | Retention purge               |
| `short_urls`   | `_id` (existing)          | unique       | identity; `$inc` target       |

---

## 5. Durable queue

### 5.1 Recommendation: Redis Streams

Use **Redis Streams** via Spring Data Redis (already on the classpath through
`spring-boot-starter-data-redis` + Lettuce), which gives:

- durable, ordered, replayable events (survives a consumer restart);
- consumer groups + ack/NAck for reliable at-least-once delivery;
- bounded memory via `XADD MAXLEN ~ n` (approximate trimming).

**No new Maven coordinate** is required. If the team prefers an alternative (e.g. a queued table in
Mongo, or a message broker), it must be an `infra` adapter behind `AnalyticsPort` — and any new broker
dependency requires explicit approval.

### 5.2 Flow

```text
redirect  --track(event)-->  AnalyticsPort (fire-and-forget)  -->  Redis Stream (durable)
                                                                    |
ClickBatchWorker (poll/XREADGROUP, batch)  -->  Mongo bulk insert to click_events
                                                                    +  $inc clickCount per code
                                                                    -->  ack
```

- **Enqueue is non-blocking.** Use `XADD` with `MAXLEN` approx; if the stream is unavailable, the
  `AnalyticsPort` implementation must not throw into the redirect (log + metric, fail-open like the
  cache — the click is lost rather than an error surfaced to the user).
- **Consumer is idempotent.** Use the consumed timestamp as provenance; a retried batch may duplicate
  an event — accept at-least-once and dedupe by an idempotency key on the event if strict uniqueness is
  required (optional; document the choice).

### 5.3 Backpressure and failure

- Bounded stream length prevents unbounded growth.
- On Redis outage: the adapter logs + increments a `dropped` metric and does **not** block the redirect
  (fail-open for analytics; it is non-critical path).
- The worker must not crash the app if Mongo is briefly unavailable — retry with exponential backoff
  and a bounded retry, then log/finalize the batch.

---

## 6. API contract

**No outward contract change.** The redirect remains:

```http
GET /{id}  -> 302 Location: <originalUrl>
```

The only observable difference is that analytics now actually persists (and `clickCount`/`click_events`
become queryable), and a `clickCount` may later be exposed via a read endpoint (out of scope).

---

## 7. Observability

Add low-cardinality metrics (Micrometer):

```text
analytics.events.enqueued.total
analytics.events.persisted.total
analytics.events.failed.total
analytics.events.dropped.total      # stream full / Redis unavailable
analytics.pipeline.queue_size       # gauge (optional)
```

Tags are fixed (no user ID, IP, or raw code as a high-cardinality tag — a code is a key, not a
dimension; aggregate by operation). Logs must never include raw IP in a sensitive context beyond the
event record, and never log JWTs/passwords.

---

## 8. Verification commands

```bash
mvn test                       # fast, Docker-free (core + unit)
mvn verify                     # full gate: unit + *IT + JaCoCo + SpotBugs + jar
mvn test -Dtest='*IT' -DfailIfNoTests=false   # targeted analytics ITs, needs Docker
```

---

## 9. Documentation deliverables

Update in the same epic:

- `docs/data-model-decisions.md` — move "Click analytics" from target to **applied**; add the
  `click_events` schema, the `$inc` `clickCount` decision, and the Redis-Stream durable-queue decision;
- `docs/coding-standards.md` — analytics pipeline rules (fire-and-forget in redirect, `$inc`, no
  read-modify-write, durable queue);
- `docs/testing-playbook.md` — add analytics ITs to the suite map and gaps;
- `AGENTS.md` — clear debt items 5 (analytics not persisted) and 15 (in-memory queue drops events);
- `README.md` — note that click tracking is persisted and `clickCount` is updated atomically.

The epic is **not** Done while `ClickBatchWorker` still only logs or the in-memory queue can silently
drop events.
