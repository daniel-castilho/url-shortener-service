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

## Baseline — <DATE>

Recording a new baseline? Replace this section with the measured p50/p95/p99,
throughput and environment, then commit. Template:

| Workload | Rate | p50 | p95 | p99 | Throughput (req/s) |
|----------|------|-----|-----|-----|--------------------|
| shorten (constant 20 rps) | `load-tests/shorten.js` |   |   |   |   |
| redirect (constant 200 rps) | `load-tests/redirect.js` |   |   |   |   |
| mixed 1:10 (20 + 200 rps) | `load-tests/mixed.js` |   |   |   |   |

- Environment: <host/hardware/JVM>
- MongoDB: <version / tier>
- Redis: <version>
- k6 version: <x.y.z>
- App version: `<git describe --tags>`
- Date: <yyyy-mm-dd>

Compare against the previous baseline; regressions > 10% at the same load
should be investigated before the release milestone is closed.