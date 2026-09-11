# Load Test Baseline — URL Shortener Service

Coverage: `load-tests/shorten.js`, `load-tests/redirect.js`, `load-tests/mixed.js`.
SLO thresholds enforced by k6: `p95 < 200ms`, error rate `< 0.1%`.

Run against the app started with relaxed per-IP rate limits for the load window:

```bash
RATE_LIMITER_LIMIT=1000000 RATE_LIMITER_REDIRECT_LIMIT=1000000 \
  ./mvnw spring-boot:run &
```

Then:

```bash
k6 run load-tests/shorten.js
k6 run load-tests/redirect.js
k6 run load-tests/mixed.js
```

Or use the wrapper script (boots infra + app + runs all three scenarios and prints a summary):

```bash
bash scripts/performance-baseline.sh [duration] [redirect-rps] [shorten-rps]
```

When other projects already own the default ports (8080/6379/27017), run in isolated mode
against dedicated infra on alternate ports:

```bash
BASELINE_SKIP_COMPOSE=1 PORT=18080 \
MONGODB_URI=mongodb://localhost:27018/url_shortener \
REDIS_HOST=localhost REDIS_PORT=6380 \
bash scripts/performance-baseline.sh 1m 200 20
```

## Baseline — 2026-09-09 (post platform upgrade)

| Workload | Rate | p50 | p95 | p99 | Throughput (req/s) |
|----------|------|-----|-----|-----|--------------------|
| shorten (constant 20 rps) | `load-tests/shorten.js` | 10.3 ms | 24.1 ms | 64.6 ms | 21.5 |
| redirect (constant 200 rps) | `load-tests/redirect.js` | 7.2 ms | 12.8 ms | 21.7 ms | 211.4 |
| mixed 1:10 (20 + 200 rps) | `load-tests/mixed.js` | 7.4 ms | 13.3 ms | 20.4 ms | 230.3 |

- Thresholds: all passed (k6 exit `0`; `p(95) < 200ms`, `http_req_failed < 0.1%`)
- Environment: Linux 6.18.33.2 (WSL2), 16 cores, 15 GB RAM, JVM 25.0.4.1 (Corretto),
  Tomcat 11, Spring Boot 4.1.1, Virtual Threads
  (**post-platform-upgrade baseline** — Java 25 / Spring Boot 4.1.1 / Tomcat 11; commit `875c7d5`)
- MongoDB: 6.0.28 (Docker, single-node, isolated port 27018)
- Redis: 8.10.1 (Docker, single-node, isolated port 6380)
- k6 version: v2.2.0 (grafana/k6 container, host networking)
- Date: 2026-09-09, app version: `main` @ `875c7d5`

> **Comparison vs 2026-08-27:** at the same load the tail latencies were ~40–85% higher
> (shorten p95 16→24 ms, redirect p99 17→22 ms, mixed p95 7.6→13.3 ms). The measurement
> stack changed too (k6 v0.58.0 native binary → k6 v2.2.0 container; Redis 7 → 8.10.1), so
> the regression could not be attributed to Tomcat 11 vs Undertow alone.

## Baseline — 2026-09-11 (like-for-like re-run, Epic 5 story 5.1)

Like-for-like re-run to isolate the platform effect: **identical measurement stack** to
2026-09-09 (k6 v2.2.0 `grafana/k6` container with host networking, Redis 8.10.1, Mongo 6.0.28,
same WSL2 host, Java 25 / Boot 4.1.1 / Tomcat 11 virtual threads). App: `main` @ `e805c1b`.

| Workload | Rate | p50 | p95 | p99 | Throughput (req/s) |
|----------|------|-----|-----|-----|--------------------|
| shorten (constant 20 rps) | `load-tests/shorten.js` | 6.59 ms | 11.97 ms | 29.53 ms | 20.0 |
| redirect (constant 200 rps) | `load-tests/redirect.js` | 3.80 ms | 5.36 ms | 8.75 ms | 202.6 |
| mixed 1:10 (20 + 200 rps) | `load-tests/mixed.js` | 3.91 ms | 5.49 ms | 7.56 ms | 223.4 |

- Thresholds: all passed (k6 exit `0`; `p(95) < 200ms`, `http_req_failed < 0.1%`); `http_req_failed` = 0 on every scenario
- Reqs: 1201 / 12156 / 13402 (shorten/redirect/mixed) over a 1m window
- Command: `BASELINE_SKIP_COMPOSE=1 PORT=8089 MONGODB_URI=mongodb://localhost:27018/url_shortener REDIS_HOST=localhost REDIS_PORT=6380 bash scripts/performance-baseline.sh 1m 200 20`
- Environment note: the dev station was hosting other local projects (dargent on 8080–8082, spotpobre Redis on 6379); the baseline ran isolated on 8089 + 27018/6380

> **VERDICT (like-for-like resolved):** the 2026-09-09 tails were not a Tomcat 11 platform
> regression. With the measurement stack held identical, the 2026-09-11 re-run produced
> **lower** tail latencies than both prior baselines (redirect p99 8.75 ms vs 21.7/17 ms,
> shorten p95 11.97 ms vs 24.1/16 ms, mixed p95 5.49 ms vs 13.3/7.6 ms) while staying far
> inside the SLO (p95 < 200 ms). All thresholds pass and `http_req_failed` = 0. The variance
> between 08-27/09-09 was measurement noise (host load + stack switch), not an app/platform
> regression. Baseline and SLOs are validated; no performance investigation is warranted.

## Stress — 2026-09-11 (Epic 5 story 5.5, 2× nominal, ramping)

`load-tests/stress.js` (ramping-arrival-rate): redirect 100→200→400 rps and shorten
10→20→40 rps, holding **2× nominal** for 4m (total window ~8m; pool 500 codes).

| Scenario | p50 | p95 | p99 | http_req_failed |
|----------|-----|-----|-----|-----------------|
| redirect (peak 400 rps) | 3.75 ms | 4.63 ms | 5.52 ms | 0 (0 / 165,498 reqs) |
| shorten (peak 40 rps) | 3.60 ms | 4.45 ms | 5.72 ms | 0 |

- 165,498 requests over the window (~375 req/s peak combined), **zero failures**, p95 well
  under the 200 ms SLO at every stage — no degradation to document; the service absorbs 2×
  nominal load with cache-warm latency (L1 + Redis L2 + bloom absorbing the redirect storm).
- Artifacts: `load-tests/results/stress-20260911-074441.summary.json`

## Baseline — 2026-08-27 (pre platform upgrade)

| Workload | Rate | p50 | p95 | p99 | Throughput (req/s) |
|----------|------|-----|-----|-----|--------------------|
| shorten (constant 20 rps) | `load-tests/shorten.js` | 6 ms | 16 ms | 59 ms | 20.0 |
| redirect (constant 200 rps) | `load-tests/redirect.js` | 5 ms | 7.5 ms | 17 ms | 200 |
| mixed 1:10 (20 + 200 rps) | `load-tests/mixed.js` | 5 ms | 7.6 ms | 11 ms | 220 |

- Environment: Linux 6.18 (WSL2), 16 cores, 32 GB RAM, JVM 21.0.2, Virtual Threads, Undertow
  (**pre-platform-upgrade baseline** — measured on Java 21 / Spring Boot 3.5.7 / Undertow)
- MongoDB: 6.0 (Docker), single-node
- Redis: 7-alpine (Docker), single-node
- k6 version: v0.58.0
- App version: `v0.10.0`
- Date: 2026-08-27