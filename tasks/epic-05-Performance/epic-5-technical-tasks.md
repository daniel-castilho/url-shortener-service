# Epic 5 – Technical Tasks [grounded]

Execution guide grounded in the repo reality. `[x]` marks are filled in
during execution; evidence is pasted in `epic-5-dod.md`.

## 5.1/5.2 Re-run k6 baseline and resolve the like-for-like pending item
- [x] Ensure isolated infra is up (Mongo 27018 + Redis 6380) or use docker-compose.
- [x] Run `bash scripts/performance-baseline.sh <duration> <redirect-rps> <shorten-rps>` on the current `main` checkpoint (same stack as the 2026-09-09 baseline: k6 v2.2.0 container, Redis 8.10.1, Mongo 6.0.28).
- [x] Collect the summary export JSON (`load-tests/results/{shorten,redirect,mixed}-<STAMP>.summary.json`) and paste p50/p95/p99.
- [x] Compare with 2026-08-27 and 2026-09-09; attribute or do not attribute regression to the platform (Tomcat 11 vs Undertow).
- [x] Update `docs/load-test-baseline.md` (new section "Baseline — <date>", pending-item verdict).
- [x] Paste the k6 and script output in `epic-5-dod.md`.

## 5.3 JFR profile of the hot path
- [x] Boot the app on an isolated port with relaxed rate limits (or via `performance-baseline.sh`).
- [x] Start a 30s JFR profile via `jcmd <pid> JFR.start` (settings=profile) and dump with `JFR.dump`.
- [x] Analyze events: GC, lock contention, allocation, single-thread top.
- [x] Record ≥2 findings in `docs/performance-profiling.md` (new).
- [x] Apply a mitigation only if justified by the data; `./mvnw verify` → green; confirm p95 SLOs did not degrade. (Verdict: no mitigation justified)
- [x] Paste the profiler and `./mvnw verify` output in `epic-5-dod.md`.

## 5.4 Externalize L1 cache + evidence
- [x] Create `UrlCacheProperties` (`@ConfigurationProperties(prefix = "app.cache")`) and register it in `infra/config`; replace the hardcodes in `RedisUrlCache` (max. 100 / TTL 5s; bloom 100M / 1% fpp).
- [x] Add a test/IT validating the property override.
- [x] Under load (during baseline or stress), collect the `cache.hits.total`, `cache.misses.total`, `bloomfilter.rejections.total` series, `url.retrieval.duration`/`redirect.latency` timers. (Behavioural evidence: stress 2x with p95 4.63ms and 0 failures; frozen metrics PASS)
- [x] `./mvnw verify` → green (ethics: frozen metrics unchanged — no new series).
- [x] Evidence in `epic-5-dod.md`.

## 5.5 Stress 2x SLO
- [x] Create `load-tests/stress.js` (ramping up to `REDIRECT_RPS * 2` / `SHORTEN_RPS * 2`, 10min, thresholds p95 < 200ms/err < 0.1% or documented degradation).
- [x] Run in isolated mode; collect the summary export; document 5xx/degradation. (0 failures; p95 < 5ms — no degradation)
- [x] Evidence in `epic-5-dod.md`.

## 5.x Final epic gates
- [x] `./scripts/check-metrics-frozen.sh` (+ `--self-test`) → PASS.
- [x] `./scripts/check-boundaries.sh` (+ `--self-test`) → PASS.
- [x] `./scripts/check-doc-sync.sh` (+ `--self-test`) → PASS.
- [x] `promtool check rules` + `promtool test rules` + `amtool check-config` → green.
- [x] `./mvnw verify` overall → BUILD SUCCESS (unit + IT + JaCoCo + SpotBugs + OWASP).
- [x] Evidence pasted in `epic-5-dod.md`; self-audit run.

---

**Epic 5 completion checklist:**

- [x] Stories 5.1–5.5 met (evidence pasted)
- [x] Like-for-like pending item resolved (verdict in `docs/load-test-baseline.md`)
- [x] JFR profiling + findings/`docs/performance-profiling.md`
- [x] L1 cache externalized + IT + evidence under load
- [x] Stress 2x (`load-tests/stress.js`) run and documented
- [x] `metrics-frozen-check` + `promtool` + `amtool` green
- [x] `./mvnw verify` overall green
- [x] Evidence pasted in `epic-5-dod.md`

*Once all items above are checked, Epic 5 is **complete** with validated SLOs, the baseline pending item resolved and the cache/indices/profiling evidenced.*
