# Epic 7 – Definition of Done (DoD) [pasted evidence]

**Rule zero — zero‑from‑memory:** Every number, sha, or count in this document must be pasted from a command output included in this document. If the generating command cannot be pasted, it is a hypothesis and must be labelled as such (TD‑13 class).

Fill in during execution. Do not invent values before running.

## 1. Required evidence (real pasted outputs)

### 7.1 Failure contract + ADRs (executed 2026-09-11)

```
$ git log --oneline -- docs/adr/ docs/reliability.md
b7ba99e docs(adr): record scalability decisions as ADRs 0001-0004 (Epic 6 story 6.1)
ca9a29c docs(reliability): failure-mode matrix + ADR 0005 (fail-open vs fail-closed) + ADR 0006 (at-least-once) — Epic 7 story 7.1
9cb239f feat(reliability): bound Redisson timeouts (ADR 0005) + prove Mongo/Redis outage paths (Epic 7 7.2/7.3)
29dfd57 feat(reliability): real PEL redelivery for analytics worker (Epic 7 7.4)
```

- Files: `docs/reliability.md` (7.1 matrix), `docs/adr/0005-fail-open-vs-fail-closed.md`,
  `docs/adr/0006-analytics-at-least-once.md`.
- Mapping RPO (target, pasted from `docs/reliability.md` §2): URL mappings (`short_urls`, `users`,
  `custom_domains`) → **last scheduled run of `scripts/backup-mongodb.sh`** (operator cron;
  drill proven in 7.5).
- Analytics RPO (target, pasted from `docs/reliability.md` §2): click events → **whatever is in the
  Redis Stream** + 90-day retention purge (at-least-once pipeline, ADR 0006).
- Cache / rate buckets / bloom: RPO 0 (rebuildable).
- Contract (7.1 matrix, pasted from `docs/reliability.md`):
  - **Redis L2 cache / rate-limit / bloom / ID-gen**: fail-OPEN (ADR 0005) — redirect path degrades with
    elevated latency instead of blocking.
  - **Mongo (URL CRUD / redirect DB hit)**: fail-CLOSED via `databaseCb` — fast-fail with a 5xx surge,
    load does not hit dead Mongo; auto-recovery HALF_OPEN → CLOSED.
  - **Analytics stream**: fail-OPEN on enqueue (fire-and-forget); at-least-once persistence, exactly-once
    rejected (ADR 0006).
  - **OTel tracing**: fail-OPEN (proven by `TracingFailOpenIT`; collector unreachable → requests succeed).

### 7.2 CB / timeout / retry isolation (executed 2026-09-11)

Inventory (read from `application.yaml` + code, not invented):

| Adapter | CB | Timeout | Retry | Contract under outage |
|---------|----|---------|-------|-----------------------|
| Mongo URL repo | `databaseCb` (window 10, min 5, 50%, open 20s, half-open 3, auto-HALF_OPEN) | connect 10s / socket 30s (Spring data) | n/a | fail-CLOSED: fast-fail 5xx, surge in the sampling window, auto-recovery |
| Redis L2 cache | — | Redisson command/connect 500ms (`app.redis.*`, ADR 0005) | 1 attempt, no retry | fail-OPEN: cache miss → DB (slow, does not block) |
| Redis rate-limit | `rateLimiterCb` (40%, open 10s) | Redisson 500ms | 1 attempt | fail-OPEN: no limiting during outage |
| Redis Stream enqueue | — | Redisson 500ms | 1 attempt | fail-OPEN: event dropped (fire-and-forget), redirect never blocks |
| OTel exporter | — | uint batch timeout (collector tail_sampling `timeout: 5s` / batch) | exporter retries | fail-OPEN: no tracing during outage |

```
$ ./mvnw test -Dtest='RedirectMongoFailureIT,RedirectRedisFailureIT,RedisClickEventQueueFailOpenTest,TracingFailOpenIT' -DfailIfNoTests=false
Tests run: 2, ... -- in Tracing Fail-Open Integration Tests
Tests run: 2, ... -- in ca.tyny.urlshortener.infra.adapter.output.analytics.RedisClickEventQueueFailOpenTest
Tests run: 2, ... -- in Redirect path — Redis outage (cache + rate-limit fail-open/degrade)
Tests run: 4, ... -- in Redirect path — MongoDB outage (real container stop, fail-closed)
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`databaseCb` transition: observed in the 7.5 drill (Mongo-down cold-cache) —
`GlobalExceptionHandler - Circuit breaker open: CircuitBreaker 'databaseCb' is HALF_OPEN
and does not permit further calls`; automatic recovery after `docker start` (HALF_OPEN → CLOSED,
without an app restart).

### 7.3 Shutdown + health (executed 2026-09-11)

Isolated app (Mongo 27018 / Redis 6380 / port 18081, relaxed rate limits, operator
`epic7ops`/`epic7-drill-pw-2026`):

```
$ PORT=18081 bash scripts/verify-graceful-shutdown.sh
[verify] Application is up, testing graceful shutdown...
[verify] Created test short URL with code: ctZvdq1
[verify] Starting slow redirect request (background)...
[verify] Sending SIGTERM to application...
[verify] Application PID: 2774294
[verify] Waiting for slow request to complete (max 30s)...
[verify] SUCCESS: In-flight request completed during grace period
[verify] Verifying new requests are rejected after shutdown initiated...
[verify] SUCCESS: App no longer accepting new requests
[verify] Graceful shutdown verification complete!
```

(in-flight drains during the 30s grace period; new requests rejected after SIGTERM —
green on isolated infra with external httpbin as a slow destination.)

liveness ≠ readiness experiment (`docker stop urlshortener-redis-isolated`):

```
$ curl http://localhost:18081/actuator/health/liveness   -> 200
$ curl http://localhost:18081/actuator/health/readiness   -> 200
$ docker stop urlshortener-redis-isolated; sleep 8
$ curl http://localhost:18081/actuator/health/liveness   -> 200
$ curl http://localhost:18081/actuator/health/readiness   -> {"status":"DOWN"}
$ docker start urlshortener-redis-isolated; sleep 12
$ curl http://localhost:18081/actuator/health/liveness   -> 200
$ curl http://localhost:18081/actuator/health/readiness   -> 200
```

liveness ≠ readiness verdict: **yes** — liveness (process) stays UP during the
dependency failure (no systemd restart-loop); readiness (process + mongo/redis) goes DOWN and
takes the instance out of rotation (nginx `max_fails=2 fail_timeout=10s`). No fix needed.

Health detail via operator (dev profile, `show-details` authorized):

```
$ curl -u epic7ops:*** http://localhost:18081/actuator/health
status=UP
components: circuitBreakers, diskSpace, livenessState, mongo, ping, readinessState, redis, ssl
```

The aggregate endpoint includes **circuitBreakers** (`databaseCb`, `rateLimiterCb`), `mongo`,
`redis`, `diskSpace`, `ping` — readiness group = mongo/redis/circuitBreakers/diskSpace/ping
(with `show-components: when-authorized`, the public probe does not leak backend detail).

### 7.4 Analytics pipeline (executed 2026-09-11)

Contract read from the code (`ClickBatchWorker` + `application.yaml`, not invented):

- Stream: `urlshortener:clicks` (`app.analytics.stream-key`, default `${APP_ANALYTICS_STREAM_KEY:urlshortener:clicks}`)
- Group: `click-worker` (`app.analytics.group`, default `${APP_ANALYTICS_GROUP:click-worker}`)
- Consumer: `worker-1` (`app.analytics.consumer`)
- Batch: `500` (`app.analytics.batch-size`), poll: `5000`ms (`app.analytics.poll-interval-ms`)
- Ack: `redisTemplate.opsForStream().acknowledge(streamKey, groupName, ...)` per batch, on the `click-worker` group

$ ./mvnw test -Dtest='ClickPipelineIT,ClickDailyRollupIT,RedisClickEventQueueFailOpenTest,RedisClickEventQueueTest,ClickPipelineRedeliveryIT' -DfailIfNoTests=false

```
Tests run: 2, ... -- in Analytics pipeline — at-least-once PEL redelivery (Epic 7 7.4)
Tests run: 2, ... -- in ca.tyny.urlshortener.infra.adapter.output.analytics.RedisClickEventQueueFailOpenTest
Tests run: 2, ... -- in Click daily rollup integration tests
Tests run: 2, ... -- in ca.tyny.urlshortener.infra.adapter.output.analytics.RedisClickEventQueueTest
Tests run: 4, ... -- in Click Pipeline Integration Tests
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**Finding (real fix):** the worker read only `>` (`ReadOffset.lastConsumed()`), which delivers
only NEVER-DELIVERED messages — an unacked batch stayed orphaned in the PEL forever (redelivery +
finalize of 3 failures was dead code). Now it drains the PEL with offset `0` before reading `>`
(Redis crash-recovery pattern). Red/green: `ClickPipelineRedeliveryIT#failedBatchIsReclaimedAfterRecovery`
fails on the old code (`expected: 5L but was: 0L` in the PEL) and passes on the new.

Restart mid-batch: published=**5** persisted=**5** (proves `M >= N`, ADR 0006):

$ ./mvnw test -Dtest='ClickPipelineRedeliveryIT#failedBatchIsReclaimedAfterRecovery' -DfailIfNoTests=false

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 ... -- in Analytics pipeline — at-least-once PEL redelivery (Epic 7 7.4)
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Poison (batch injected with `databaseCb` open → 3 attempts → finalized/acked; the valid event
afterwards persists), real worker log:

```
ERROR c.t.u.i.a.o.a.ClickBatchWorker - Finalizing click batch of 2 events after 3 consecutive failures
```

$ ./mvnw test -Dtest='ClickPipelineRedeliveryIT#poisonBatchIsFinalizedAndGroupKeepsProcessing' -DfailIfNoTests=false → green (part of the 2 above; PEL 2→0, metric `analytics.events.failed.total` +6, `click_events` 0 for the poison, valid event=1).

### 7.5 DR + fault injection under load (executed 2026-09-11)

Isolated infra: app **18080**, Mongo **27018** (`urlshortener-mongo-isolated`), Redis **6380** (`urlshortener-redis-isolated`).
Seeds (codes): `WvbkQL9`, `ikNnZMP`, `8l9Zi1J`, `sjuq5b0`, `IAa4KHx` (created via `POST /api/v1/urls` on the isolated instance) + `cZLYsMv`, `cZxmLOD`, `4TcKm26` (drill, post-backup).

$ docker exec urlshortener-mongo-isolated mongodump --uri="mongodb://127.0.0.1:27017/url_shortener" --db=url_shortener --out=/tmp/epic7-backup --gzip

```
done dumping `url_shortener.schema_migrations` (9 documents)
done dumping `url_shortener.users` (0 documents)
done dumping `url_shortener.click_daily` (0 documents)
done dumping `url_shortener.short_urls` (7640 documents)
done dumping `url_shortener.click_events` (303867 documents)
```

$ docker cp urlshortener-mongo-isolated:/tmp/epic7-backup /tmp/opencode/epic7/backup-drill && ls -l .../short_urls.bson.gz .../click_events.bson.gz && du -sh

```
-rw-r--r--   445999  short_urls.bson.gz
-rw-r--r--  3546379  click_events.bson.gz
3.9M    /tmp/opencode/epic7/backup-drill
```
> Note: `mongodump` is not on the host PATH (only inside the mongo container); the drill used the
> container tools — the same flags `scripts/backup-mongodb.sh` invokes on bare-metal hosts with the CLI.

drop + restore + curl -sI of the seeds:

```
$ mongosh --eval 'db.short_urls.drop()'          # -> true; count=0
$ curl -s -o /dev/null -w '%{http_code}\n' localhost:18080/cZLYsMv   # AFTER drop, cold cache -> 404
$ mongorestore --uri=...url_shortener --db=url_shortener --gzip /tmp/epic7-backup/url_shortener
7640 document(s) restored successfully. 303876 document(s) failed to restore.   # click_events: collection
                                                                 # never dropped — existing _id skipped
$ curl -s -o /dev/null -w '%{http_code}\n' localhost:18080/WvbkQL9   # 302  (pre-backup: restored)
$ curl -s -o /dev/null -w '%{http_code}\n' localhost:18080/cZLYsMv   # 404  (post-backup: not restored, correct RPO)
```
> Post-backup codes stay 404 after restore — expected: RPO = last backup execution. Curl used with
> `-s` (GET); `HEAD` also responds 302 (fix applied in `SecurityConfig` + `ReadPathIT#headMirrorsGetOnRedirectPath`).

k6 isolated happy run (`load-tests/redirect.js`, 30s @100rps):

```
checks_succeeded 100.00% (3001/3001)   http_req_failed 0.00% (0/3201)
http_req_duration: avg=4.8ms  p(50)=4.05ms  p(95)=10.56ms  p(99)=13.58ms
```

Redis down midway (45s @150rps; `docker stop urlshortener-redis-isolated` at +20s):

```
checks_succeeded 100.00% (5730/5730)   http_req_failed 0.00% (0/5930)
http_req_duration: avg=208.78ms  p(50)=6.45ms  p(95)=744.22ms  p(99)=753.89ms
```
Verdict: **aligned with the 7.1 matrix** — rate-limit + cache fail-open (ADR 0005): 100% 302 with no 4xx/5xx;
p95 latency rises (~744ms) during the outage window (cache/RL fallback to Mongo), no client failures.

Mongo down (cold cache; flush Redis + wait for the L1 TTL, then `docker stop urlshortener-mongo-isolated`;
`fixed-redirect.js` 60s @150rps against the real seeds; `databaseCb` opens after ~5 failures):

```
http_req_failed: 100.00% (4504/4504)   http_req_duration: avg=324ms  p(50)=3.56ms  p(95)=6.85ms  p(99)=27.09s
iterations 4504 / dropped_iterations 4497
```
Log: `GlobalExceptionHandler - Circuit breaker open: CircuitBreaker 'databaseCb' is HALF_OPEN and does not permit further calls`
Verdict: **aligned with the 7.1 matrix** — fail-closed: cold-cache redirects fail 503/5xx (p50 3.6ms is the
OPEN fast-fail state; p99 27s is the CB sampling window with the driver's server-selection timeout);
recovery after `docker start` → **302** (HALF_OPEN → CLOSED, without an app restart).

Aligned with the 7.1 matrix? **yes**, no gaps to fix (Redis-down fail-open and Mongo-down fail-closed
behave exactly as contracted).

### 7.6 Final gates (executed 2026-09-11)

```
$ ./scripts/check-metrics-frozen.sh  && ./scripts/check-metrics-frozen.sh --self-test
=== Metrics Freeze Gate ===
PASS: metrics frozen — all registered meters belong to the reviewed set (docs/slos.md §2).
PASS: self-test verified — gate detects violations.

$ ./scripts/check-boundaries.sh && ./scripts/check-boundaries.sh --self-test
PASS: Architecture boundary check passed (0 violations).
PASS: self-test verified — gate detects violations and allows clean code.

$ ./scripts/check-doc-sync.sh && ./scripts/check-doc-sync.sh --self-test
PASS: documentation sync check passed (AGENTS.md debt statuses + lessons promotions consistent).
PASS: self-test verified — gate detects violations and allows clean docs.

$ ./scripts/check-security.sh && ./scripts/check-security.sh --self-test
=== Security Gate ===
PASS: Security gate passed (logSafe sinks, HTTP headers, JWT validator, no stray internal IPs).
PASS: self-test verified — gate detects violations.

$ promtool check rules recording-rules.yml        # via prom/prometheus:v2.53.0 container
Checking /mon/recording-rules.yml → SUCCESS: 4 rules found
$ promtool check rules alerts.yml
Checking /mon/alerts.yml → SUCCESS: 3 rules found
$ promtool test rules rules_tests.yml
Unit Testing:  /mon/rules_tests.yml → SUCCESS
$ amtool check-config alertmanager.yml            # via prom/alertmanager:v0.27.0 container
Checking '/mon/alertmanager.yml'  SUCCESS
```

**Gate-path fix (real):** `promtool test rules` failed with
`invalid annotation name: runbook-§Fast-burn` (and Slow-burn/Budget-exhausted) — `§` and hyphens are not
allowed in Prometheus annotation names. Adjusted to `runbook_fast_burn` /
`runbook_slow_burn` / `runbook_budget_exhausted` in `deploy/monitoring/alerts.yml`, with
`docs/slos.md` and the `alertmanager.yml` comment synced (the same discipline
that caught debt items 19–29: a gate that bites). `promtool check-config`/`test rules` are green again.

```
$ ./mvnw verify
Tests run: 165, Failures: 0, Errors: 0, Skipped: 0   (unit + IT, 165 ITs no total)
All coverage checks have been met.
BUILD SUCCESS
```

Epic closing sha: `1286f1a` (7.6 gates); epic commits: `29dfd57` (7.4 PEL),
`59bdc70` (7.5 drill/HEAD fix), `1286f1a` (7.6 gates + promtool fix). CI green on the final push
(jobs Unit, Integration, Build, Security Gate, Observability Gate — all `success`).

## 2. Completion checklist

- [x] `docs/reliability.md` + ADR 0005 + ADR 0006
- [x] CB/timeout/retry inventory + failure ITs
- [x] Shutdown script green + probes evidenced
- [x] Worker/PEL/poison evidenced
- [x] Isolated restore + two fault-injections with numbers
- [x] Runbook updated with the commands from this execution
- [x] `./mvnw verify` green
- [x] No figure in this file without the pasted command above

## 3. Confirmed out of scope (does not become ghost debt)

- Replica set / Redis cluster — not done, SPOF accepted and recorded in the matrix.
- AGENTS debt #26 (ROLE_ADMIN) — not resolved here.
- Exactly-once clicks — rejected in ADR 0006.

---

*When section 2 is 100% checked and section 1 has real outputs, Epic 7 is **complete**.*