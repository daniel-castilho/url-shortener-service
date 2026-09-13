# Epic 6 – Stories (Acceptance) [grounded]

| # | Story | Acceptance Criteria (grounded) | Real Reference |
|---|-------|------------------------|--------------------------|
| **6.1** | **Scalability ADRs** – record the scaling decisions the code already implements (and the rejected ones) in `docs/adr/`. | • 4 ADRs: 0001 stateless horizontal scaling (vs vertical), 0002 global per-IP rate-limit via Redis, 0003 per-instance Caffeine L1 (bounded staleness), 0004 resilience4j circuit breakers. <br>• Each ADR: status/date/context/decision/consequences. <br>• `git log --oneline -- docs/adr/` pasted in `epic-6-dod.md`. | ADR pattern (Nygard) |
| **6.2** | **`explain` index audit** – prove that the critical queries use the V3–V9 indexes (do not blindly create indexes). | • mongosh on the isolated infra with real data: explain of the `_id` lookup (redirect), cursor pagination (V7), rollup/analytics (V4), TTL (V5). <br>• Evidence: `IXSCAN`/`ID_SCAN` stage and minimal `totalDocsExamined` pasted. <br>• No new index migrations (the model is already complete). <br>• `./mvnw verify` green. | `MongoSchemaMigrator` V1–V9 |
| **6.3** | **Rate-limit + circuit breakers evidenced** – the template asked to "implement"; already implemented. Provide evidence under load. | • `RedirectRateLimitIT` (5 tests) green, output pasted. <br>• `/actuator/circuitbreakers` under 2× load: `databaseCb`/`rateLimiterCb` in `CLOSED` (with config: 50%/20s, window 10, min 5). <br>• `rate-limiter.*` and `resilience4j.circuitbreaker.*` configs documented in the DoD. | `RedisRateLimiterAdapter`, `@CircuitBreaker(name="databaseCb")` |
| **6.4** | **Multi-instance release artifacts** – nginx upstream N servers + weight-flip, systemd template, image with sha tag, runbook. | • `deploy/proxy/nginx.conf` with a multi-server upstream + documented weights (flip 10→30→100). <br>• `deploy/url-shortener@.service` (systemd template instantiated `@1/@2/...`). <br>• Built Docker image: `docker images` with size + `sha` tag pasted. <br>• `docs/release-runbook.md` updated with the multi-instance procedure. | Bare-metal systemd + nginx (not k8s) |
| **6.5** | **Horizontal scale validation** – 2 instances + LB under 2× stress (the single-instance 2× was already validated in Epic 5). | • 2 instances (distinct ports) sharing Mongo/Redis; nginx LB in front. <br>• `stress.js` 2× via LB: p95 < 200ms, 0 5xx, report pasted. <br>• **Shared rate-limit proof:** with real limits, burst via LB → 429 exactly after the global capacity (same Redis bucket across the 2 instances). <br>• Evidence in `epic-6-dod.md`. | `load-tests/stress.js`, `docs/twelve-factor.md` §6 |

---

**Quick traceability:**

| Story | Reference doc | Key aspect |
|-------|----------------|---------------|
| 6.1 | `docs/adr/` | Decisions recorded |
| 6.2 | mongosh explain + V3–V9 | Indexes actually used |
| 6.3 | `RedirectRateLimitIT` + `/actuator/circuitbreakers` | Resilience evidenced |
| 6.4 | `deploy/proxy/` + `Dockerfile` + runbook | Multi-instance |
| 6.5 | `stress.js` via LB | Horizontal scale proven |

---

*Run in the order of `epic-6-technical-tasks.md`; evidence in `epic-6-dod.md`.*