# Real Analytics — Implementation Sequence
## Persist click events, atomic `$inc` counter, durable queue

**Companions:** `real-analytics-spec.md` · `real-analytics-backlog.md`
**Rule:** complete each step's acceptance and verification before starting the next. Do not invent
out-of-scope work.

---

## Global execution rules

1. Work in small, reviewable vertical commits.
2. Read the referenced story acceptance before coding.
3. Add tests with the production change, not at the end.
4. The redirect path must never block or throw on analytics failure.
5. Use `$inc` — never read-modify-write. (The `QuotaService` bug is exactly this class; do not repeat it.)
6. No new Maven coordinate without approval (Redis Streams use the existing Spring Data Redis /
   Lettuce graph).
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

## Step 0 — Baseline, design lock, dependency gate
### Stories: (context)

### Actions

1. Confirm HEAD (`d8a2a9c`), fast tests green, and the current analytics is log-only (fila in-memory
   that drops when full).
2. Confirm Spring Data Redis / Lettuce and `StringRedisTemplate` are on the classpath (yes).
3. Record locked decisions in `docs/data-model-decisions.md`:
   - persist to `click_events`; atomic `$inc` `clickCount`;
   - durable queue = Redis Streams (no new coordinate / no alternative broker without approval);
   - analytics is `fire-and-forget` + fail-open; at-least-once delivery.
4. Confirm the redirect path currently calls `analyticsPort.track(...)` and does not block.

### Done when

- baseline understood; locked decisions recorded;
- dependency approach approved;
- no unresolved "or/if available" choice remains.

### Verify

```bash
mvn test
```

---

## Step 1 — clickCount on domain/entity + atomic $inc
### Stories: A1, A2

### Actions

1. Add `long clickCount` (default 0) to `ShortUrl` and `ShortUrlEntity`.
2. Update `ShortUrlMapper` to round-trip it.
3. Add `incrementClickCount(String code)` to the URL repository (or a narrow port) using `$inc`.

### Done when

- `clickCount` on domain/entity + mapper;
- atomic `$inc` method; no read-modify-write;
- unit test proves concurrent increments are not lost (adapter IT).

### Verify

```bash
mvn test
mvn test -Dtest='MongoUrlRepositoryIT' -DfailIfNoTests=false
```

---

## Step 2 — Durable queue adapter (Redis Stream)
### Stories: P1

### Actions

1. Implement `RedisClickEventQueue implements AnalyticsPort` using `StringRedisTemplate`/Lettuce
   (`XADD` with `MAXLEN ~`).
2. Make `track()` non-blocking and fail-open (log + `analytics.events.dropped.total` metric; never
   throw into the redirect).
3. Wire it as the `AnalyticsPort` bean (replace `AsyncAnalyticsAdapter`).

### Done when

- events are XADDed; survive a consumer/restart;
- `track()` never blocks/throws;
- Redis outage degrades to a metric, not a redirect failure.

### Verify

```bash
mvn test
mvn test -Dtest='Redis*IT' -DfailIfNoTests=false
```

---

## Step 3 — Consumer: persist + $inc + ack
### Stories: P2, P3

### Actions

1. Replace the log-only `ClickBatchWorker` with a consumer that reads a batch (consumer group or
   scheduled poll), bulk-inserts `ClickEventDocument` rows, `$inc` `clickCount` per unique code, then
   acks.
2. Implement at-least-once semantics; add an idempotency key if strict uniqueness is required
   (document).
3. Bounded retry + backoff on Mongo failure; never crash the app.

### Done when

- a redirect → a persisted `click_events` row + incremented `clickCount`;
- retry on Mongo outage is bounded and logs;
- pipeline is at-least-once and idempotent per the documented choice.

### Verify

```bash
mvn test
mvn test -Dtest='*Analytics*IT,ShortenFlowIT' -DfailIfNoTests=false
```

---

## Step 4 — click_events collection + indexes
### Stories: P4

### Actions

1. Add `ClickEventDocument` (Mongo) and a bulk-insert repository.
2. Add indexes via `IndexMigration` (idempotent): `(shortCode, timestamp)`, `(timestamp)`.

### Done when

- `click_events` exists with the schema;
- indexes are idempotent;
- bulk insert works against a real Mongo (IT).

### Verify

```bash
mvn test -Dtest='*Analytics*IT,MongoUrlRepositoryIT' -DfailIfNoTests=false
```

---

## Step 5 — Metrics + no-blocking proof + full gate
### Stories: V1

### Actions

1. Add the low-cardinality metrics (`analytics.events.enqueued/persisted/failed/dropped.total`).
2. Add the analytics `*IT` matrix:
   - redirect → persisted + `clickCount` correct;
   - Redis down → redirect still `302`, `dropped` metric recorded;
   - concurrency → exactly N increments.
3. Confirm no sensitive high-cardinality tag (no IP/JWT/raw key as a dimension).

### Done when

- the IT matrix is green;
- fail-open is proven;
- metrics present with fixed tags;
- `mvn verify` green.

### Verify

```bash
mvn verify
mvn test -Dtest='*Analytics*IT' -DfailIfNoTests=false
```

---

## Step 6 — Documentation sync
### Stories: V2

### Actions

1. `data-model-decisions.md` — click analytics to applied; add `click_events` + `$inc` + Redis-Stream
   decision.
2. `coding-standards.md` — pipeline rules (fire-and-forget in redirect, `$inc`, durable queue).
3. `testing-playbook.md` — analytics ITs in suite map/gaps.
4. `AGENTS.md` — clear debt items 5 and 15.
5. `README.md` — note persisted tracking + atomic count.

### Done when

- no doc describes the log-only worker or drop-on-full queue as current;
- `AGENTS.md` marks 5/15 resolved.

### Verify

```bash
mvn verify
```

---

## Final smoke / acceptance path

1. Open a short URL → it redirects (`302`).
2. Wait for the worker → a `click_events` row exists and `short_urls.clickCount` incremented by 1.
3. Open the same URL many times concurrently → `clickCount` = exactly N.
4. Stop Redis → open a URL → redirect still `302`; `analytics.events.dropped.total` increases; no error
   surfaced to the user.
5. Restart the application → queued events are still processed (durability proven).
6. `mvn verify` and the analytics `*IT` pass; no sensitive high-cardinality tag in metrics.

---

_Pre-implementation sequence. Preserve deviations and final evidence as an as-built record after delivery._
