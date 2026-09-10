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

> **Comparison vs 2026-08-27:** at the same load the tail latencies are ~40–85% higher
> (shorten p95 16→24 ms, redirect p99 17→22 ms, mixed p95 7.6→13.3 ms). The measurement
> stack changed too (k6 v0.58.0 native binary → k6 v2.2.0 container; Redis 7 → 8.10.1), so
> the regression cannot be attributed to Tomcat 11 vs Undertow alone. **Next step:** re-run a
> like-for-like comparison (same k6 version, same Redis major) to isolate the platform effect
> before the next release is closed; a real >10% platform regression must be investigated.

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