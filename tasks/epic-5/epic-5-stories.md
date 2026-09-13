# Epic 5 – Stories (Acceptance) [grounded]

| # | Story | Acceptance Criteria (grounded) | Real Reference |
|---|-------|------------------------|--------------------------|
| **5.1** | **p95 latency SLO validation (redirect) + like-for-like pending item** – confirm that `GET /{id}` has p95 ≤ 200ms under nominal load and resolve the note in `docs/load-test-baseline.md` (tails 40–85% above the 08-27 baseline, measurement stack changed at the same time). | • Re-run `load-tests/redirect.js` via `scripts/performance-baseline.sh` on the current `main` checkpoint (same stack: k6 v2.2.0 container, Redis 8.10.1, Mongo 6.0.28) → p95 ≤ 200ms in the summary export. <br>• Comparison of the 3 baselines (08-27, 09-09, new) with a verdict on real regression vs variance. <br>• `docs/load-test-baseline.md` updated. <br>• k6 report pasted in `epic-5-dod.md`. | `slos.md` (p99 < 200ms via k6 `p95 < 200ms`); `docs/load-test-baseline.md` |
| **5.2** | **p95 latency SLO validation (shorten)** – confirm that `POST /api/v1/urls` has p95 ≤ 200ms under nominal load. | • Re-run `load-tests/shorten.js` (k6 thresholds `p95 < 200ms`, `http_req_failed < 0.1%`) → p95 within the limit. <br>• k6 report pasted in `epic-5-dod.md`. | `slos.md` (single target p99 < 200ms; **there is no** "S3 = 300ms" in the repo) |
| **5.3** | **JVM profile and bottleneck mitigation** – identify ≥2 bottlenecks in the hot path and apply a mitigation if justified by the data. | • **JFR** profile (built-in JDK 25 via `jcmd`; async-profiler **not installed** and not needed) of 30s during k6 load on the `GET /{id}` and `POST /api/v1/urls` routes. <br>• ≥2 findings (e.g.: GC pause, contention, allocation on the critical path) documented in `docs/performance-profiling.md`. <br>• Mitigations applied **only** if the data justify them; `./mvnw verify` stays green; p95 does not degrade. | Java profiling best practices; JFR on JDK 25 |
| **5.4** | **Cache-aside externalized + evidence** – ensure short-code lookup with fast cache hits and externalized config. | • `maximumSize(100)`/`expireAfterWrite(5s)` (hardcoded in `RedisUrlCache`) externalized to `@ConfigurationProperties` (`app.cache.l1.*`), env-overridable. <br>• IT validating the override and the L1+bloom behaviour. <br>• Evidence under load: hit-ratio and latency (frozen series `cache.hits.total`, `cache.misses.total`, `bloomfilter.rejections.total`, timer `url.retrieval.duration` + `redirect.latency`). | Cache-aside pattern (EP3); frozen metrics `slos.md` §2 |
| **5.5** | **Load and stress test (k6)** – validate stability under load above nominal (2x SLO peak). | • New `load-tests/stress.js` with ramping up to 2x nominal rps (redirect 400 / shorten 40 for 10min) and thresholds. <br>• Run documented: `5xx`, p95 degradation, rate-limiter/cache behaviour; expected degradation documented (not "fixing" thresholds). <br>• k6 report attached to `epic-5-dod.md`. | Load and stress tests (k6) |

---

**Quick traceability:**

| Story | Reference doc | Key aspect |
|-------|----------------|---------------|
| 5.1 | `slos.md` + `docs/load-test-baseline.md` | Latency p95 GET /{id}; like-for-like resolved |
| 5.2 | `slos.md` + `load-tests/shorten.js` | Latency p95 POST /api/v1/urls |
| 5.3 | JFR profile via `jcmd` | Bottlenecks identified and mitigated |
| 5.4 | `RedisUrlCache` + frozen metrics | Cache L1/bloom externalized and evidenced |
| 5.5 | `load-tests/stress.js` + `slos.md` | Stability under load (2x/ramping) |

---

*Run stories 5.1–5.5 in the order of `epic-5-technical-tasks.md`.*
