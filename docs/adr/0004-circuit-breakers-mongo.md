# ADR 0004: Circuit breakers on the Mongo adapters via Resilience4j

- **Status:** accepted
- **Date:** 2026-09-11
- **Epic:** 6 (Scalable); anchors the resilience pillar shared with EP7 (Reliable)

## Context

In a fleet of N instances, a slow or failing MongoDB turns each instance into a thread-
hungry proxy of the outage: virtual threads make it worse (unbounded carrier growth while
parked on DB calls). The DB is the one dependency where **fail-closed** degradation is
better than queueing — but analytics/queue paths may want different thresholds than the
hot path. Redis already fails open (ADR 0002, bloom, cache L2), so the DB is the remaining
shared-resource risk.

## Decision

Wrap Mongo repository operations with Resilience4j `@CircuitBreaker`:

- `@CircuitBreaker(name = "databaseCb")` on the persistence operations of
  `MongoUrlRepository` (retry-capable reads and writes behind the port).
- Configuration (`application.yaml` → `resilience4j.circuitbreaker.instances.databaseCb`,
  base `default`): sliding window 10, minimum 5 calls, failure-rate threshold 50%,
  open-state wait 20s (auto half-open with 3 probe calls), health indicator registered.
- A second instance `rateLimiterCb` (40% / 10s) guards the rate-limiter path separately.
- State is exposed at `/actuator/circuitbreakers` (admin-gated) and feeds the health
  indicator (`/actuator/health` components) so LBs/runbooks can act on it.

Rejected: retry-only (no bulkheading — retries amplify an overloaded DB); fail-open on
Mongo (a redirect serving stale-gone links is worse than a fast failure signal);
hand-rolled breaker (Resilience4j is already a managed dependency with Spring Boot 4
integration).

## Consequences

**Positive**
- A Mongo outage opens the breaker within ~5 failed calls and gives fast failures instead
  of parked virtual threads — instances stay responsive for non-DB paths.
- Thresholds are independently tunable per breaker via config, no code changes.
- Observability: breaker state is scrapeable, alertable and visible in the health
  endpoint.

**Negative / trade-offs**
- Writes rejected while OPEN are surfaced as errors (fail-closed by design); the API
  returns 5xx until half-open probes succeed.
- Per-instance breaker state (each instance trips independently) — under a fleet-wide DB
  outage all instances trip independently, which is the intended behaviour but means N
  probe calls per half-open window.
