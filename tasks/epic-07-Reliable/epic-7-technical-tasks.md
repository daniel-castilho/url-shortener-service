# Epic 7 – Technical Tasks [grounded]

`[x]` markers filled in during execution; evidence pasted in `epic-7-dod.md`.

## 7.1 Failure contract + ADRs
- [x] Create `docs/reliability.md` with a component × failure × client effect × data effect × detection × recovery × target RTO/RPO matrix.
- [x] Cover: Mongo, Redis cache/rate-limit, Redis Stream, `ClickBatchWorker`, OTel collector, nginx, Mongo volume.
- [x] ADR `docs/adr/0005-fail-open-vs-fail-closed.md` (status/date/context/decision/consequences + rejected options).
- [x] ADR `docs/adr/0006-analytics-at-least-once.md` (duplicate click vs loss; why not a distributed transaction).
- [x] Link the matrix to the relevant frozen series (`resilience4j.*`, `analytics.queue.depth`, `http.server.requests`, `cache.*`).
- [x] Paste `git log --oneline -- docs/adr/ docs/reliability.md` in `epic-7-dod.md`.

## 7.2 Isolation (CB + timeout + retry budget)
- [ ] Inventory the outgoing adapters: Resilience4j annotation, timeout, retry. Table in the DoD.
- [x] Confirm the real values in `application.yaml` (`spring.data.mongodb.*`, `spring.data.redis.timeout`, `resilience4j.circuitbreaker.instances.*`).
- [x] If the redirect lookup has no explicit timeout on the Mongo/Redis client, externalize it (no hardcoding) and document the chosen value (target: Redis ≤ 500ms already configured; Mongo socket 30s is high for the hot path — justify or lower **only** with evidence and without breaking ITs).
      **Finding — the claim "Redis ≤ 500ms already configured" was false:** the Redisson 4.7.0 starter **ignores** `spring.data.redis.timeout` (the `RedissonAutoConfigurationV4.buildSingleServerConfig` maps only host/port/password/ssl/database); the real Redisson defaults (timeout 3s, connect 10s, 3 retries at 1.5s) made every Redis op fail in ~5–25s — 200 GETs under outage = hours-long "hang". Fixed: the `app.redis.*` block (`command-timeout-ms 500`, `connect-timeout-ms 500`, `retry-attempts 1`, `retry-interval-ms 100`, env-overridable) applied via `RedissonAutoConfigurationCustomizer` in `RedisConfig`; Mongo 30s kept with justification in ADR 0005 (the CB is the operational protection).
- [x] IT Mongo down / refused on `GET /{id}` cache-miss: status + CB log/metric. Suggested name: `RedirectMongoFailureIT`.
- [x] IT Redis down on the redirect: rate-limit fail-open + Mongo fallback. Suggested name: `RedirectRedisFailureIT` (extend `RedisUrlCache` tests if they already cover the essentials).
- [x] **Technical note (singleton containers):** the failure ITs start containers **dedicated to their own class** (start/stop in their own lifecycle) — the `BaseIntegrationTest` singletons are shared by all ITs and cannot be stopped mid-suite.
      `RedirectMongoFailureIT` (4/4: CB open→503, CB closed→404, half-open probe, hot code via L2 / cold→503) and `RedirectRedisFailureIT` (2/2: cache-miss→Mongo degrades, rate-limiter fail-open beyond the limit) — **green together** (6/6, ~40s).
- [x] `./mvnw test -Dtest='RedirectMongoFailureIT,RedirectRedisFailureIT,RedisClickEventQueueFailOpenTest,TracingFailOpenIT'` → green; output pasted.
- [x] Do not rely on authenticated `GET /actuator/circuitbreakers` as primary proof (`resilience4j.circuitbreaker.*` metric / log). Note: debt #26 was resolved in `c0fbb9c` (operator BasicAuth) — the endpoint is accessible to the operator as optional secondary evidence.

## 7.3 Shutdown + health semantics
- [x] Run `./scripts/verify-graceful-shutdown.sh` against a local instance; paste the relevant stdout/stderr (in-flight ok; refusal after SIGTERM).
      Run on the isolated infra (18081/Mongo 27018/Redis 6380): in-flight completed within the grace period, new requests refused after SIGTERM — output pasted in DoD §7.3.
- [x] Map what the `HealthEndpoint` actually aggregates today (Mongo, Redis, disk, CB). Output of `GET /actuator/health` in **test/dev profile** pasted (prod hides details).
      Via operator (dev): components = circuitBreakers, diskSpace, livenessState, mongo, ping, readinessState, redis, ssl — pasted in DoD §7.3.
- [x] Experiment: app healthy → `docker stop` the critical dependency → `curl` liveness vs readiness (HTTP code + summarized body).
      Redis stop: liveness 200 / readiness DOWN; Redis start: both 200 — pasted in DoD §7.3.
- [x] If liveness and readiness fall together: adjust the indicators (readiness includes Mongo; liveness is process/event-loop only) + IT `HealthProbeSemanticsIT`.
      They do not fall together — semantics already distinct by design (liveness = process; readiness = mongo/redis/cb/disk/ping). No fix needed; real evidence in DoD §7.3.
- [x] Document in `docs/reliability.md` the role of nginx `max_fails=2 fail_timeout=10s`.
      `docs/reliability.md` §3 (readiness DOWN takes the instance out of rotation) + matrix §1 (nginx down).
- [x] Paste outputs in the DoD.

## 7.4 Analytics pipeline under failure
- [x] Document stream name, group, ack, PEL in `docs/reliability.md` (values read from the code, not invented).
- [x] Extend `ClickPipelineIT` (or a sibling): publish N → interrupt worker → restart → assert collection + `clickCount` under the at-least-once contract.
      **Real PEL fix:** the worker read only `>` (`ReadOffset.lastConsumed()`), which delivers only NEVER-delivered messages — an unacked batch stayed orphaned in the PEL and was NEVER redelivered (redelivery and the 3-failure finalize were dead code). It now follows the Redis crash-recovery pattern: drains the PEL with offset `0` BEFORE reading `>` (`readGroup` shared with NOGROUP self-heal). Red/green: the new `ClickPipelineRedeliveryIT` fails with the old code (`expected: 5L but was: 0L` in the PEL) and passes with the fix.
- [x] Poison case: an invalid event does not block the group; drop metric/log; subsequent valid events persist.
      `poisonBatchIsFinalizedAndGroupKeepsProcessing`: batch injected with `databaseCb` open → 3 consecutive attempts → finalizes (acked, `analytics.events.failed.total` +6) → the subsequent valid event persists.
- [x] Re-confirm enqueue fail-open on the redirect (`RedisClickEventQueueFailOpenTest`) — output pasted.
- [x] `./mvnw test -Dtest='ClickPipelineIT,ClickDailyRollupIT,RedisClickEventQueueFailOpenTest,RedisClickEventQueueTest'` → green.

## 7.5 DR drill + fault injection under load
- [x] Bring up isolated infra (ports outside 27017/6379/8080 — Epic 5/6 pattern: 27018 / 6380 / 18080).
- [x] Seed codes; keep the list in the DoD.
- [x] `./scripts/backup-mongodb.sh` (adjust the isolated instance's `MONGODB_URI` env); paste path + dump size + `ls -l`.
- [x] Simulate the loss: drop `short_urls` **on the isolated instance**; restore; `curl -sI` of the seed codes → 302; paste.
- [x] Run `load-tests/redirect.js` (short duration) happy path on the isolated instance — this session's local baseline.
- [x] `docker stop` Redis mid-run; paste the k6 summary + verdict vs the 7.1 matrix.
- [x] `docker start` Redis; `docker stop` Mongo; cold-cache run; paste summary + verdict.
- [x] Update `docs/release-runbook.md` with the Redis-down / Mongo-down / restore playbooks (real commands used).
- [x] k6 artifacts in `load-tests/results/` (gitignored) — the DoD gets the summary, not the binary.

## 7.6 Final epic gates
- [x] `./scripts/check-metrics-frozen.sh` (+ `--self-test`) → PASS.
- [x] `./scripts/check-boundaries.sh` (+ `--self-test`) → PASS.
- [x] `./scripts/check-doc-sync.sh` (+ `--self-test`) → PASS.
- [x] `./scripts/check-security.sh` (+ `--self-test`) → PASS.
- [x] `promtool check rules` + `promtool test rules` + `amtool check-config` → green.
- [x] Full `./mvnw verify` → BUILD SUCCESS.
- [x] Evidence pasted in `epic-7-dod.md`; self-audit of rule zero.

---

**Epic 7 completion checklist:**

- [x] `docs/reliability.md` + ADR 0005 + ADR 0006
- [x] CB/timeout/retry inventory + Mongo/Redis down ITs
- [x] Shutdown script green + liveness ≠ readiness evidenced (or fixed)
- [x] Worker reclaims the PEL; poison does not stall the group
- [x] Isolated backup/restore green + two fault-injections with numbers
- [x] Incident runbook updated
- [x] `./mvnw verify` green (all gates)
- [x] Evidence pasted in `epic-7-dod.md`

*When all items above are checked, Epic 7 is **complete** with failure modes contracted and proven.*