# ADR 0003: Per-instance Caffeine L1 with a short TTL; bloom + Redis L2 stay shared

- **Status:** accepted
- **Date:** 2026-09-11
- **Epic:** 6 (Scalable); anchors the cache-aside design (Epic 5 story 5.4 config)

## Context

The cache-aside stack on the redirect path is: **Caffeine L1 (in-process)** → Redisson
**bloom filter** → **Redis L2** → MongoDB. In a multi-instance fleet an in-process cache
cannot be invalidated cross-instance; a naive L1 would serve stale redirects after a link
is updated or archived (`UrlCachePort.evict` clears Redis + local L1 of the **handling
instance only**).

## Decision

Keep the L1 **per instance** but bound its staleness window instead of trying to
synchronize it:

- TTL **5s** (default `app.cache.l1-ttl`, configurable — `UrlCacheProperties`, Epic 5
  story 5.4), max size 100 (`app.cache.l1-max-size`).
- The bloom filter and Redis L2 remain shared across instances: `put`/`evict` propagate
  through Redis, so every instance converges within the L1 TTL.
- Redirect correctness is preserved by expiry-eager application logic (`410 Gone`) and by
  the Redis TTL being capped at the link's `expiresAt` — the L1 never outlives a cached
  value that matters.

Rejected: distributed invalidation (pub/sub broadcast of evictions — complexity and a new
runtime dependency for at most 5s of staleness); dropping L1 (loses the hottest-key
microsecond win observed in Epic 5 baselines); sticky sessions/LB (would fight the
stateless decision of ADR 0001).

## Consequences

**Positive**
- Worst-case staleness after an update/archive is bounded by the L1 TTL (≤ 5s default),
  without any cross-instance coordination.
- Adding instances requires no cache-coordination config; L1 hit rate is warm per instance
  immediately after boot (`put` populates it).
- Small memory footprint per instance; size/TTL tunable per deployment via
  `APP_CACHE_L1_MAX_SIZE` / `APP_CACHE_L1_TTL`.

**Negative / trade-offs**
- A `PATCH`/`DELETE` on one instance leaves up-to-5s stale entries on the others
  (documented in the link-management API contract as eventual consistency).
- N instances → N× the Redis miss volume for the same hot key set (bounded by L1 sizes).
