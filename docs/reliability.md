# Reliability — Failure Modes, Degradation & Recovery

**Scope (Epic 7):** make the behaviour under failure **explicit, tested and operable**. This
document is the contract: how the service degrades, what the client sees, what data is at
risk, how to detect it and how to recover. Epic 7 does **not** turn MongoDB/Redis into
clusters — the single-node SPOF is accepted and written down (see "Out of scope").

Anchors: `docs/slos.md` (SLOs/burn-rate), `docs/adr/0004` (circuit breakers), ADR 0005
(fail-open vs fail-closed), ADR 0006 (analytics at-least-once), `docs/release-runbook.md`
(incident playbooks), `docs/load-test-baseline.md` (happy-path SLO evidence).

---

## 1. Dependency-failure matrix

| Component (failure) | Client effect | Data at risk | Detection | Recovery | Target RTO / RPO |
|---|---|---|---|---|---|
| **MongoDB down** (redirect, cache miss / bloom-negative) | `databaseCb` opens after ≥5 calls at ≥50% failure → fast failures surface as **503**; codes in L1/Redis L2 keep answering **302** | None — mappings are durable in Mongo; writes (shorten) fail visibly, no partial state | `resilience4j.circuitbreaker.state{databaseCb}` → OPEN; `http_server_requests{status=~"5.."}` rate; health `mongo` DOWN (readiness DOWN) | Restart Mongo (compose volume persists); CB auto half-open after 20s, 3 probe calls; L1/L2 keep serving hot codes | RTO: minutes (restart) · **RPO mapping = last `backup-mongodb.sh` run** |
| **MongoDB slow** (not down) | p95 grows on cache-miss; socket-timeout 30s bounds worst wait (justified in ADR 0005 — CB opens long before via 50% failure rate) | None | latency timers (`url.retrieval.duration`, `redirect.latency`); burn-rate alerts | Find host cause (disk/CPU); CB may trip on timeout-failures | as above |
| **Redis down — cache L2** (redirect) | Bloom errors are caught and skipped (`RedisUrlCache` catch → falls through to Mongo) → cache-miss path answers from Mongo: **302**, higher latency; no client-visible failure | None (L2 is a cache) | `cache.misses.total` spike; redis connection errors in log; health `redis` DOWN | Restart Redis; AOF replays; bloom/L2 repopulate on demand | RTO: minutes · RPO: n/a (cache) |
| **Redis down — rate limiter** (redirect + shorten) | **Fail-open** (ADR 0005): every request allowed + WARN log; anti-enumeration temporarily off | None | rate-limiter WARN logs; `rl:*` keys absent | Restart Redis; buckets self-expire (TTL `max(60s, 2×refill)`) | RTO: minutes · RPO: n/a (buckets rebuild) |
| **Redis down — click enqueue** | Redirect **never blocks** on analytics (fire-and-forget, `RedisClickEventQueue.track` catch): **302** as usual; events **dropped and counted** (`analytics.events.dropped.total`) | Clicks during the outage are **lost** (accepted — analytics, not mapping) | `analytics.events.dropped.total` > 0; `analytics.queue.depth` stalls | Restart Redis; next clicks re-enqueue | RTO: minutes · **RPO click = whatever is in the Stream** (ADR 0006) |
| **Redis Stream lag / worker dead** | None on the redirect path; analytics dashboards go stale | Events accumulate in the Stream (bounded by memory + retention) | `analytics.queue.depth` gauge grows monotonically | Restart app (worker self-heals group via NOGROUP recreate); PEL entries redelivered (at-least-once, ADR 0006) | RTO: app restart · RPO click = Stream contents |
| **ClickBatchWorker crashes mid-batch** | None | Batch stays **un-acked in the PEL** → redelivered on next tick; after **3 consecutive failures** the batch is finalized (acked) with `analytics.events.failed.total` to avoid wedging (ADR 0006: bounded loss > infinite wedge) | `analytics.events.failed.total` > 0 | PEL redelivery is automatic | RTO: next tick · RPO: failed batch |
| **Poison message in Stream** | None | One malformed event; **does not** block the group (batch finalize after 3 attempts) | `analytics.events.failed.total`; worker WARN/ERROR log | No operator action; healthy events after the poison persist | self-healing |
| **OTel collector down** | **Fail-open** tracing (proven by `TracingFailOpenIT`): requests proceed normally | Traces lost for the window | OTel exporter errors in log | Restart collector; spans resume | RTO: collector restart · RPO: lost spans |
| **nginx (proxy) down** | Total outage at the edge (client cannot reach any instance) | None | Edge monitoring / external probe | `systemctl restart nginx`; peers drop unhealthy instances via `max_fails=2 fail_timeout=10s` | RTO: restart · RPO: n/a |
| **Mongo volume full** | Mongo goes read-only/fails → same as Mongo down; disk health visible in health endpoint `diskSpace` | Writes fail; existing mappings safe | `diskSpace` indicator DOWN; WiredTiger "Too many open files"/disk errors in mongod log | Free space / extend volume; restart Mongo if needed | RTO: ops · RPO mapping = last backup |

Key invariants:

- **The mapping (short_urls) is the only critical data.** Everything else (cache, buckets,
  stream, traces) is rebuildable or expendable.
- **The redirect never blocks on analytics** (Rule 5) and **never takes the process down**
  for a non-critical dependency failure.
- **Liveness ≠ readiness** (§3): a dependency blip takes the instance **out of the LB pool**
  (readiness DOWN) without **killing** the process (liveness UP).

## 2. RTO/RPO summary (targets, on-prem bare metal)

| Data | RPO target | Mechanism |
|---|---|---|
| URL mappings (`short_urls`, `users`, `custom_domains`) | last scheduled `scripts/backup-mongodb.sh` (operator-run schedule; drill in §5) | mongodump → restore drill proven |
| Click analytics (`click_events`) | whatever is in the Redis Stream + 90d retention purge | at-least-once pipeline (ADR 0006) |
| Cache / rate buckets / bloom | 0 (rebuildable) | L1/L2/bloom repopulate on demand |

RTO target for any single-node dependency restart: **minutes** (systemd/compose restart +
CB half-open probes). A full host loss is bounded by backup restore time (drill numbers in
`tasks/epic-7/epic-7-dod.md`).

## 3. Liveness vs readiness semantics

- `GET /actuator/health/liveness` — **process** only. Must stay UP through dependency blips so
  the supervisor (systemd) does not restart-loop the app.
- `GET /actuator/health/readiness` — **process + dependencies** (mongo, redis). DOWN takes the
  instance out of rotation: nginx `max_fails=2 fail_timeout=10s` stops routing to it after 2
  failures in 10s; LBs/K8s use the probe directly.
- `GET /actuator/health` (full) — operator-only detail (debt 26 resolved: BasicAuth operator),
  shows every component incl. circuit breakers (`databaseCb`, `rateLimiterCb`) and diskSpace.

Evidence under real `docker stop` injection: `tasks/epic-7/epic-7-dod.md` §7.3.

## 4. Analytics pipeline contract (ADR 0006)

- Stream: `urlshortener:clicks` (env `APP_ANALYTICS_STREAM_KEY`), consumer group
  `click-worker` (`APP_ANALYTICS_GROUP`), consumer `worker-1`; batch ≤500
  (`APP_ANALYTICS_BATCH_SIZE`), poll every 5s (`APP_ANALYTICS_POLL_INTERVAL_MS`).
- **Enqueue:** fire-and-forget, fail-open; `track()` never throws on the redirect path.
- **Delivery:** **at-least-once** — the worker runs the Redis crash-recovery pattern: it drains
  the consumer-group PEL with `XREADGROUP` offset `0` **before** reading new messages with `>`, so
  a batch that fails to persist stays un-acked in the PEL and is reclaimed on a later tick.
  Retries can duplicate click rows; `clickCount` (`$inc`) is best-effort and may run slightly ahead
  of `click_events` under redelivery. Exactly-once/distributed transactions **rejected** (ADR 0006).
- **Bounded failure:** after **3 consecutive** batch failures the batch is finalized (acked) and
  counted in `analytics.events.failed.total` — a prolonged outage degrades to bounded loss
  instead of an unbounded wedge.
- **Self-heal:** a missing group/stream (e.g. flushed Redis) is recreated on the next tick
  (NOGROUP recreate).
- **Poison:** a malformed event fails its batch up to 3 times, then the batch is finalized;
  the consumer never loops forever on one message.

## 5. Backup / restore (mapping DR)

- Backup: `scripts/backup-mongodb.sh` → mongodump archive under `/var/backups/url-shortener/`
  (or `BACKUP_DIR`); schedule via cron (operator responsibility).
- Restore: `scripts/restore-mongodb.sh <dump-dir>`; **drill executed against the isolated
  infra** (drop `short_urls` → restore → seeds answer 302) — numbers pasted in
  `tasks/epic-7/epic-7-dod.md` §7.5; playbooks in `docs/release-runbook.md`.

## 6. Accepted SPOFs (out of scope — do not "fix" silently)

- MongoDB single node (no replica set), Redis single node (no Sentinel/Cluster) — on-prem,
  single-host topology; backup/restore + fast restarts are the mitigation. Revisit only with
  capacity evidence (ADR 0001 records the scale decision).
- Rate-limiter fail-open during Redis outages (product decision, ADR 0005).
- Click loss during Redis outages (ADR 0006).
- Debt 26 is **resolved** (`c0fbb9c`); no operator-role gaps remain for actuator reading.
