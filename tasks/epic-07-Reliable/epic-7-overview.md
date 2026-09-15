# Epic 7: Reliable – Fault Tolerance and Recovery

**Project:** url-shortener-service
**Context:** Java 25, Spring Boot 4.1.1, Hexagonal Architecture, MongoDB 6.0 (single-node), Redis (single-node + AOF + Stream), Tomcat 11 (virtual threads), bare-metal + nginx
**Goal:** Make failure behaviour **explicit, tested, and operable** — the service degrades in a known way, recovers without silently losing URL mappings, and does not lie on health. This epic does **not** turn Mongo/Redis into a cluster; it documents the SPOF and proves the degradation modes.

---

## Repository state (pre-existing, not new work)

The repository **already has** a resilience base that Epic 7 must **surface as evidence**, not rewrite:

- **Resilience4j circuit breakers:** `databaseCb` (`@CircuitBreaker` on the Mongo adapters; window 10 / min 5 / 50% / 20s open) and `rateLimiterCb` (40% / 10s). Exposed at `/actuator/circuitbreakers`. ADR 0004 already records the decision.
- **Targeted fail-open:** rate-limit when Redis goes down (`RedisRateLimiterAdapter`); OTel tracing (`TracingFailOpenIT`); click queue (`RedisClickEventQueueFailOpenTest`). The redirect does **not** wait on analytics.
- **Tiered health:** `liveness`/`readiness` public; detailed `health` gated. Probes in the Dockerfile and systemd.
- **Graceful shutdown:** `server.shutdown: graceful` + 30s timeout + `scripts/verify-graceful-shutdown.sh` (in-flight completes; probe stops accepting).
- **Partial durability:** Redis Stream (`RedisClickEventQueue` + `ClickBatchWorker`); atomic `$inc` of `clickCount`; AOF on the compose Redis; TTL index V5; `ClickEventsRetentionPurge` purge (idempotent batches).
- **Backup/restore:** `scripts/backup-mongodb.sh` and `scripts/restore-mongodb.sh` + a mention in `docs/release-runbook.md`.
- **Config fail-fast:** `ProdConfigValidator` aborts the `prod` profile with default JWT / localhost Mongo-Redis.
- **Proxy:** nginx `max_fails=2 fail_timeout=10s` (Epic 6).

**What is actually missing:**

1. **Failure-mode matrix** (Mongo down, Redis down, stalled Stream, dead worker, OTel down, disk full) with the effect on the client (302 / 429 / 503 / 404) and on the data (URL mapping vs analytics).
2. **Explicit timeouts and retry budget** on the outgoing ports (the CB exists today; timeout/retry/bulkhead are neither contracted nor tested end to end).
3. **liveness ≠ readiness semantics** evidenced under dependency failure (readiness DOWN, liveness UP — kube/systemd/nginx takes it out of rotation without killing the process).
4. **Analytics queue contract:** at-least-once, PEL/ack, poison message, what happens if the worker crashes mid-batch.
5. **DR drill and fault injection under load:** Mongo restore with verification; Redis/Mongo taken down during `stress.js` with 5xx/latency/degradation **pasted**, not narrated.

## Why this epic now?

- **EP3 (Observable)** delivered metrics, fail-open tracing, SLOs and burn-rate — without them failure is invisible.
- **EP4 (Tests)** delivered the IT/Testcontainers harness; EP7 adds the *induced-failure* tests.
- **EP5 (Performance)** proved the SLO on the happy path; EP7 proves what remains of the SLO when a dependency disappears.
- **EP6 (Scalable)** proved 2 instances + LB + CB CLOSED on the happy path. **Debt #26 resolved in
  `c0fbb9c`** (operator BasicAuth over the actuator tiers, env `OPERATOR_USERNAME`/`OPERATOR_PASSWORD`):
  HTTP reads of `/actuator/circuitbreakers` are available to the operator as
  **secondary** evidence; the primary proof in this epic's stories remains functional (HTTP status +
  `resilience4j.*` metrics + logs), so the failure tests are not coupled to credentials.
- **EP8 (Deployable)** needs correct readiness, drained shutdown and an incident runbook — otherwise blue-green/canary becomes a cutover gamble.

**Out of scope (not to be done in this epic):**

- Mongo replica set, Redis Sentinel/Cluster, multi-AZ.
- Switching the rate-limit from fail-open to fail-closed (product decision; at most an ADR).
- Resolving debt #26 (`ROLE_ADMIN`).
- Exactly-once for clicks (the contract is at-least-once; `clickCount` may diverge slightly from `click_events` under retry — this must stay documented, not "fixed" with a distributed transaction).

**Acceptance criteria (grounded):**

1. `docs/reliability.md` with a component × failure × client effect × data effect × how to detect × how to recover matrix.
2. ≥2 new ADRs in `docs/adr/` (target: 0005 fail-open vs fail-closed per dependency; 0006 analytics at-least-once + PEL).
3. Timeouts/retry budget documented in the real configs and covered by failure ITs (Mongo timeout → CB opens; Redis down on the redirect → contracted behaviour).
4. `scripts/verify-graceful-shutdown.sh` green **and** proof of liveness UP / readiness DOWN with Mongo or Redis freshly taken down (output pasted).
5. Analytics worker: a crash mid-batch does not lose the Stream; a poison message does not stall the consumer; evidence pasted.
6. Drill: `backup-mongodb.sh` → selective drop/restore on **isolated** infra → lookup of known codes 302; Redis-down and Mongo-down injection under load with pasted numbers.
7. `./mvnw verify` green with all gates.
8. **Rule zero — zero-from-memory:** every number, sha, or count is pasted from real output.

**Quick traceability:**

| Story | Reference doc | Key aspect |
|-------|----------------|---------------|
| 7.1 | `docs/reliability.md` + `docs/adr/0005`, `0006` | Failure contract written |
| 7.2 | Resilience4j + timeout/CB ITs | Dependency isolation |
| 7.3 | `verify-graceful-shutdown.sh` + probes | Drain and health semantics |
| 7.4 | `RedisClickEventQueue` + `ClickBatchWorker` | Queue recovery |
| 7.5 | `scripts/backup-mongodb.sh` + fault injection | DR + degradation under load |

---

*Next step: run stories 7.1–7.5 (`epic-7-technical-tasks.md`) and paste evidence in `epic-7-dod.md`.*