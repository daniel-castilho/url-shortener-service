# ADR 0005: Fail-open vs fail-closed per dependency

- **Status:** accepted
- **Date:** 2026-09-11
- **Epic:** 7 (Reliable); anchors Rule 5 (redirect-path integrity) and the failure matrix in
  `docs/reliability.md`

## Context

Every outbound dependency can fail. The question "fail open or fail closed?" is a **product**
decision per dependency, not a blanket rule. The redirect hot path (`GET /{id}`) must stay
available under partial failure (an anti-abuse control must not become a self-inflicted DoS),
while the **data** dependency (MongoDB) must degrade loudly and visibly. This ADR records the
decision the code already implements and the alternatives rejected.

Timeout budget reality (`application.yaml`): Mongo `connect-timeout 10s` / `socket-timeout
30s`; Redis `timeout 500ms`; DNS (validation) `2000ms`; domain TXT verify `3000ms`.

## Decision

| Dependency | Mode | Rationale |
|---|---|---|
| **Rate limiter (Redis)** | **fail-open** | Throttling protects the *service* from abuse; if the limiter itself is down, blocking every request would turn an anti-DoS control into a self-inflicted outage. Allowed + WARN-logged. (`RedisRateLimiterAdapter`) |
| **Tracing (OTel exporter)** | **fail-open** | Observability must never take down the observed path. Proven by `TracingFailOpenIT`. |
| **Click analytics enqueue (Redis Stream)** | **fail-open** | Rule 5: the redirect never blocks on analytics; events are dropped and counted (`analytics.events.dropped.total`). Clicks are expendable; mappings are not. (`RedisClickEventQueue`) |
| **Cache L2 / bloom (Redis)** | **degrade** (skip, fall through) | A cache miss is not an error: bloom errors are caught and the lookup proceeds to Mongo; higher latency, zero client-visible failure. (`RedisUrlCache`) |
| **MongoDB (mappings)** | **fail-closed** with circuit breaker | The mapping is the product. Silently serving without it is impossible; the honest answer is a **fast failure (503)** once `databaseCb` opens (window 10 / min 5 / 50% / 20s open, ADR 0004) rather than queueing threads on a dead socket. Writes fail visibly — no partial state. |

**Mongo socket-timeout 30s — justified, not lowered.** The circuit breaker opens on the
failure-*rate* long before a 30s socket wait becomes the norm: with window 10 / min 5, five
fast connection refusals open the breaker in seconds, and hot-path lookups are served by the
cache-aside stack (L1 + bloom + L2) that never touches Mongo. Lowering the socket timeout to
3–5s would produce false failures under legitimate load spikes (GC pauses, WiredTiger
checkpoints) — the observed p99 under 2× stress is ~10ms against a healthy Mongo
(`docs/load-test-baseline.md`). 30s stands as the *worst-case bound*, with the CB as the
*operational* protection.

## Consequences

**Positive**
- Partial failures degrade predictably: hot codes keep redirecting (L1/L2), abuse protection
  fails open (documented risk window), mappings fail loudly and fast.
- Every fail-open path is observable (counters/logs), so "open" never means "silent".

**Negative / trade-offs**
- During a Redis outage the service runs **without anti-enumeration throttling** and **drops
  clicks** — accepted, bounded by outage length, visible in metrics.
- A slow (not dead) Mongo can hold a cache-miss thread up to 30s before the CB opens —
  bounded by the CB's failure-rate math, not by the timeout.

**Rejected:**
- Fail-closed rate limiter (an outage of the limiter would 503 the entire edge).
- Fail-open Mongo (a redirect serving stale "gone" mappings is worse than a fast 503).
- Aggressive Mongo timeouts (3–5s) — false-positive risk under load; CB is the real guard.
- Replica set / Sentinel (out of scope; SPOF accepted in `docs/reliability.md` §6).
