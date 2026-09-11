# ADR 0006: Click analytics is at-least-once (not exactly-once)

- **Status:** accepted
- **Date:** 2026-09-11
- **Epic:** 7 (Reliable); anchors the analytics pipeline contract (`docs/reliability.md` §4)

## Context

Clicks are tracked on the redirect hot path, which must never block on analytics (Rule 5).
Events flow: redirect thread → Redis Stream (`urlshortener:clicks`, fire-and-forget) →
`ClickBatchWorker` (consumer group `click-worker`) → bulk insert into `click_events` +
atomic `$inc` of `clickCount` per unique code. A worker crash, a Redis flush or a Mongo
outage can each interrupt the flow at a different point, and the question is what delivery
semantics the pipeline promises.

## Decision

The pipeline is **at-least-once**, with a **bounded-loss** escape valve:

1. **Redelivery, not loss:** a batch that fails to persist stays **un-acked** in the pending
   entries list (PEL) and is redelivered on a later poll tick (`ClickBatchWorker`).
2. **Bounded failure:** after **3 consecutive** batch failures the batch is **finalized
   (acked) and counted** in `analytics.events.failed.total` — a prolonged Mongo outage
   degrades to *bounded loss* instead of an unbounded wedge that would eventually exhaust
   Redis memory.
3. **Duplicates are possible and accepted:** redelivery after a crash-between-persist-and-ack
   can duplicate `click_events` rows; `clickCount` (`$inc`) is best-effort and may run
   slightly ahead of the event rows under redelivery. Analytics is a counting surface, not
   billing — small over-counting under failure is preferred to lost mappings.
4. **Enqueue is fail-open:** if the Stream itself is unreachable, the event is **dropped and
   counted** (`analytics.events.dropped.total`); the redirect answers 302 regardless.
5. **Self-heal:** a missing consumer group/stream (e.g. flushed Redis) is recreated
   automatically (NOGROUP recreate on the next tick).

**Rejected:**
- **Exactly-once / distributed transaction (Stream + Mongo 2-phase commit):** requires
  transactional coordination across Redis and Mongo — complexity and latency on a pipeline
  whose only consumer is counting dashboards; the Rx does not justify the cost.
- **Idempotency keys per event:** dedup by `eventId` would need a second store/lookup on the
  hot path — the duplication window is small and the consumer is analytics, not billing.
- **Unbounded retry (never ack a failed batch):** a poison message or a long Mongo outage
  would wedge the consumer and grow the PEL/Stream without bound.

## Consequences

**Positive**
- Worker crash mid-batch loses **nothing** (PEL redelivery); poison messages cannot wedge the
  group (3-attempt finalize); Mongo outages degrade predictably.
- No transaction coordinator on the hot path; the redirect stays microsecond-cheap.

**Negative / trade-offs**
- `click_events` may contain duplicates and `clickCount` may over-count under redelivery —
  documented, accepted, visible in `analytics.events.failed.total`/`analytics.events.dropped.total`.
- During a Redis outage, clicks are **lost** (fail-open enqueue) — bounded by outage length.
