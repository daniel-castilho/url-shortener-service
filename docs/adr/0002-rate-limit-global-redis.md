# ADR 0002: Per-IP rate limiting is global via a Redis token bucket (not per instance)

- **Status:** accepted
- **Date:** 2026-09-11
- **Epic:** 6 (Scalable); anchors Rule 5 (redirect-path integrity)

## Context

`GET /{id}` is the hot path and an enumeration surface (probing codes). Rate limiting must
be per client IP and must stay correct when N instances run behind a load balancer: an
in-memory limiter would give each attacker N× the budget, and instance-local clocks would
drift, letting racing instances over-admit.

## Decision

Rate limiting lives **behind `RateLimiterPort`**, implemented by
`infra/adapter/output/redis/RedisRateLimiterAdapter`:

- Token bucket per `(scope, IP)`; scopes SHORTEN (`rate-limiter.limit`, default 60/min) and
  REDIRECT (`rate-limiter.redirect-limit`, default 120/min) are independent budgets.
- Refill is computed **inside one atomic Lua script driven by Redis TIME** — a single
  shared clock; N racing instances can never over-admit. No application clock is read.
- Bucket state is a small hash (`tokens, ts`) with TTL `max(60s, 2× refill period)` so idle
  buckets self-expire (no unbounded key growth under IP rotation).
- **Fail-open**: on any Redis failure or unexpected reply the request is allowed and logged
  — throttling must never take down the endpoint it protects. `rate-limiter.enabled=false`
  makes nothing touch Redis.
- Client IP resolution honours `X-Forwarded-For` only from trusted proxies
  (`rate-limiter.trusted-proxy-cidrs`) — required when nginx fronts N instances.

Rejected: in-memory buckets (breaks global budget under N instances); application-clock
refill (clock drift across instances); fail-closed (an Anti-DoS control must not become a
DoS on itself).

## Consequences

**Positive**
- The per-IP budget is **global** across the fleet: adding instances does not add attacker
  budget. Proven in Epic 6 story 6.5: a burst through the LB gets 429 exactly after the
  shared capacity, regardless of which instance serves it.
- One atomic round-trip per request on the hot path (Lua script), independent of N.
- Fail-open keeps the redirect path available during Redis incidents (bounded by the bloom
  filter still short-circuiting unknown codes).

**Negative / trade-offs**
- Redis is on the request path of the redirect (availability coupling); mitigated by
  fail-open and Redis persistence (AOF).
- Raw IPs in bucket keys (deliberate: on-prem single-tenant Redis, keys are TTL'd) instead
  of HMAC-encoded subjects.
