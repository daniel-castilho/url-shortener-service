# Load Test Baseline — URL Shortener Service

Coverage: `load-tests/shorten.js`, `load-tests/redirect.js`, `load-tests/mixed.js`.
SLO thresholds enforced by k6: `p95 < 200ms`, error rate `< 0.1%`.

Run against the app started with relaxed per-IP rate limits for the load window:

```bash
RATE_LIMITER_LIMIT=1000000 RATE_LIMITER_REDIRECT_LIMIT=1000000 \
  mvn spring-boot:run &
```

Then:

```bash
k6 run load-tests/shorten.js
k6 run load-tests/redirect.js
k6 run load-tests/mixed.js
```

## Baseline — 2026-08-27

| Workload | Rate | p50 | p95 | p99 | Throughput (req/s) |
|----------|------|-----|-----|-----|--------------------|
| shorten (constant 20 rps) | `load-tests/shorten.js` | 6 ms | 16 ms | 59 ms | 20.0 |
| redirect (constant 200 rps) | `load-tests/redirect.js` | 5 ms | 7.5 ms | 17 ms | 200 |
| mixed 1:10 (20 + 200 rps) | `load-tests/mixed.js` | 5 ms | 7.6 ms | 11 ms | 220 |

- Environment: Linux 6.18 (WSL2), 16 cores, 32 GB RAM, JVM 21.0.2, Virtual Threads, Undertow
- MongoDB: 6.0 (Docker), single-node
- Redis: 7-alpine (Docker), single-node
- k6 version: v0.58.0
- App version: `v0.10.0`
- Date: 2026-08-27

Compare against the previous baseline; regressions > 10% at the same load
should be investigated before the release milestone is closed.