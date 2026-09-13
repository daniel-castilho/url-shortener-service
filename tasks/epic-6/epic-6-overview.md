# Epic 6: Scalable – Foundation for Growth

**Project:** url-shortener-service
**Context:** Java 25, Spring Boot 4.1.1, Hexagonal Architecture, MongoDB, Redis, Tomcat 11 (virtual threads)
**Objective:** Prepare the system to grow sustainably — multiple instances without degradation or restructuring — and **provide evidence** (not re-implement) of the scaling mechanisms the repo already has.

---

## Repo state (pre-existing, not new work)

The repo **already has** most of the scaling foundation:

- **Stateless by design** (`docs/twelve-factor.md` §6/§8): stateless JWT auth; Mongo/Redis are shared resources; the analytics queue is a durable Redis Stream (`RedisClickEventQueue` + `ClickBatchWorker`) — no in-process state.
- **Shared per-IP rate-limit:** `RedisRateLimiterAdapter` — token bucket **over Redis** (a single atomic Lua script, TIME-driven), independent SHORTEN (60/min) and REDIRECT (120/min) scopes, trusted-proxy CIDR, fail-open. All instances hit the same bucket → the limit is global, not per instance. `RedirectRateLimitIT` (5 tests) covers capacity, anti-enumeration, scopes, and concurrent burst.
- **Resilience4j circuit breakers:** `databaseCb` (`@CircuitBreaker`) in `MongoUrlRepository`, sliding window 10 / min 5 calls / 50% failure / 20s open; `rateLimiterCb`; exposed at `/actuator/circuitbreakers`.
- **MongoDB indexes (V1–V9 via `MongoSchemaMigrator`):** redirect lookup is by `_id` (the short code IS the PK — an index `{short_code:1}` would be useless); cursor pagination uses V7 `(userId, createdAt DESC)`; analytics uses V4 `(shortCode, timestamp)` + `(timestamp)`; expiration uses TTL V5 `expiresAt`.
- **Shared cache-aside:** bloom filter + Redis L2 shared; **Caffeine L1 is per instance** (TTL 5s → bounded staleness, acceptable), now configurable via `app.cache.l1-*` (Epic 5).
- **Single-instance baseline/stress (Epic 5):** nominal 200/20 rps p95 < 12ms; stress 2× (ramping 400/40, hold 4m): 165.498 reqs, 0 failures, p95 < 5ms.
- **Real SLO:** p99 < 200ms (k6 thresholds `p95 < 200ms`, err < 0.1%) — there is no "S3 = 300ms".

**What is truly missing:** (a) scaling decisions recorded as ADRs; (b) an `explain` audit with evidence of index usage; (c) multi-instance deploy artifacts (nginx upstream N servers, systemd template, image with sha tag, runbook); (d) a **real multi-instance validation** (2 instances + LB under 2× load, proving a shared rate-limit).

## Why this epic now?

- **EP5 (Performance)** validated single-instance SLOs; EP6 validates that the stateless design **actually scales horizontally** and records the decisions that support it.
- **EP7/EP8** depend on a scalable foundation (fault tolerance, blue-green/canary deploy without disruption).

**Acceptance Criteria (grounded):**

1. ≥2 ADRs (target: 4) in `docs/adr/` with the status/date/context/decision/consequences template.
2. `explain` audit on the critical queries with pasted evidence (IXSCAN, minimal `totalDocsExamined`) — **without** blindly creating any index.
3. Rate-limit and circuit breakers **proven with evidence** under load (green IT + `/actuator/circuitbreakers`).
4. Multi-instance artifacts: nginx upstream with weight-flip, systemd template `url-shortener@.service`, built Docker image (size + sha tag in the DoD), updated runbook.
5. Run **2 instances + LB** under 2× stress: SLOs held, 0 5xx, shared rate-limit proven.
6. `./mvnw verify` green with all gates.
7. **Rule zero — zero-from-memory:** every number, sha, or count is pasted from a real output.

**Quick traceability:**

| Story | Reference doc | Key aspect |
|-------|----------------|---------------|
| 6.1 | `docs/adr/` | Scale decisions recorded |
| 6.2 | `MongoSchemaMigrator` V3–V9 + mongosh explain | Indexes actually used |
| 6.3 | `RedisRateLimiterAdapter` + resilience4j | Global limits + circuit breakers |
| 6.4 | `deploy/proxy/nginx.conf`, `deploy/url-shortener@.service`, `Dockerfile` | Multi-instance deploy |
| 6.5 | `load-tests/stress.js` via LB | Horizontal scale validated |

---

*Next step: run stories 6.1–6.5 (`epic-6-technical-tasks.md`) and paste the evidence into `epic-6-dod.md`.*