# Epic 5: Performance – Latency and Throughput

**Project:** url-shortener-service
**Context:** Java 25, Spring Boot 4.1.1, Hexagonal Architecture, MongoDB, Redis, Tomcat 11 (virtual threads)
**Goal:** Validate the performance of the critical paths (shortening and redirection), resolve the "like-for-like" baseline pending item (isolate the effect of the Tomcat 11 vs Undertow platform) and prove that the latency SLOs of `slos.md` (p99 < 200ms) are met under nominal and stress load.

---

## Repo state (pre-existing, not new work)

The repo **already** has the core this epic imagined building:

- **Real k6 harness:** `load-tests/{shorten,redirect,mixed}.js` (thresholds-as-code `p95 < 200ms`, `http_req_failed < 0.1%`) + `scripts/performance-baseline.sh` (boots the app with relaxed rate limits, runs the 3 scenarios, exports summary JSON and prints a p50/p95/p99 table). k6 resolves to a native binary if present, otherwise `grafana/k6` container (host networking). The script accepts `[duration] [redirect-rps] [shorten-rps]` and an isolated mode `BASELINE_SKIP_COMPOSE=1` with `PORT`/`MONGODB_URI`/`REDIS_HOST`/`REDIS_PORT`.
- **Published baseline:** `docs/load-test-baseline.md` — 2026-09-09 post-platform-upgrade (shorten p95 24.1 ms @ 20 rps, redirect p95 12.8 ms @ 200 rps, mixed p95 13.3 ms). All thresholds passed (k6 exit 0).
- **Cache-aside in production:** `RedisUrlCache` = Caffeine L1 (100 items / 5s TTL, **hardcoded**) + Redisson bloom filter (100M / 1% fpp) + Redis L2. Caffeine is already a dependency in the `pom.xml`.
- **MongoDB indexes migrated:** `MongoSchemaMigrator` V1–V7 (short code IS the `_id` — there is no `short_code` field; V3 `userId`, V4 `click_events` compound `(shortCode, timestamp)` + `(timestamp)`, V5 TTL `expiresAt`, V6 `users` (email unique, plan, n), V7 `(userId, createdAt DESC)` for cursor pagination).
- **Frozen metrics:** 24 real series via `MetricsPort` → `MicrometerMetricsAdapter` (names WITHOUT `dargent_` prefix), gate `scripts/check-metrics-frozen.sh` (CI + self-test). Real SLO: p99 < 200ms (enforced by k6 `p95 < 200ms`).

## Why this epic now?

- **EP3 (Observable)** provided the metrics (`shorten.latency`, `redirect.latency`, `url.retrieval.duration`, `cache.hits.total`/`cache.misses.total`, `bloomfilter.rejections.total`) that allow measuring latency and hit-ratio under load.
- **EP4 (Testing)** brought the *IT* (incl. `ReadPathIT`) that prevent regressions in performance changes.
- **Pending item on the agenda:** `docs/load-test-baseline.md` states that the tails 40–85% above the 2026-08-27 baseline **cannot be attributed** to Tomcat 11 vs Undertow, because the measurement stack changed at the same time (k6 v0.58.0 → v2.2.0, Redis 7 → 8.10.1). Epic 5 resolves this by re-running the same current stack and comparing run-to-run.

## Acceptance Criteria (grounded)

1. `./mvnw verify` → **green** with all gates (unit, IT, JaCoCo, SpotBugs, OWASP, check-doc-sync, check-boundaries).
2. **Latency SLOs proven** by the real k6 harness (p95 `GET /{id}` ≤ 200ms and p95 `POST /api/v1/urls` ≤ 200ms — the global target of `slos.md` is p99 < 200ms; there is no "S3 = 300ms" in the repo).
3. **Like-for-like pending item resolved:** baseline re-run on the same stack (k6 v2.2.0, Redis 8.10.1, Mongo 6.0.28) and compared with 2026-09-09; verdict documented (real platform regression vs measurement variance).
4. **Caffeine L1 externalized** to `@ConfigurationProperties` (today `maximumSize(100)`/`expireAfterWrite(5s)` hardcoded in `RedisUrlCache`), with an IT validating the override.
5. **JFR profiling** (built-in JDK 25 via `jcmd`; async-profiler is not installed and not needed) of the hot path under load; ≥2 findings documented in `docs/performance-profiling.md`, mitigations applied if justified by the data.
6. **Stress 2x SLO:** new `load-tests/stress.js` (ramping up to 2x nominal rps) running 10min; degradation/5xx documented.
7. **Rule zero — zero-from-memory:** every number, sha or count in the evidence is pasted from real command output.

**Quick traceability:**

| Story | Reference doc | Key aspect |
|-------|----------------|---------------|
| 5.1 | `slos.md` + `docs/load-test-baseline.md` | Latency p95 GET /{id} + like-for-like |
| 5.2 | `slos.md` + `load-tests/shorten.js` | Latency p95 POST /api/v1/urls |
| 5.3 | JFR via `jcmd` | Bottlenecks identified and mitigated |
| 5.4 | `RedisUrlCache` + frozen metrics | Cache-aside L1/bloom externalized and evidenced |
| 5.5 | `load-tests/stress.js` + `slos.md` | Stability under 2x / ramping load |

---

*Next step: run stories 5.1–5.5 (definition in `epic-5-stories.md`) and the corresponding tasks (`epic-5-technical-tasks.md`).*
