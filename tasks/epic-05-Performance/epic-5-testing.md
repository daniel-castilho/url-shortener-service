# Epic 5 – Testing Strategy [grounded]

## 5.1/5.2 Performance tests with k6 (follow the real baseline)
- **Goal:** Validate the latency SLOs (p99 < 200ms via k6 `p95 < 200ms`) and resolve the like-for-like pending item.
- **Action:**
  - `bash scripts/performance-baseline.sh 1m 200 20` (or a longer duration) in isolated mode (Redis 8.10.1 / Mongo 6.0.28).
  - Verify p95 `GET /{id}` ≤ 200ms and p95 `POST /api/v1/urls` ≤ 200ms in the summary export.
  - Confirm `http_req_failed < 0.1%`; latency stable at the peak.
  - Compare with previous baselines and document the verdict.
- **Acceptance criterion:** k6 report green; p95 within the SLOs; output pasted in `epic-5-dod.md`.

## 5.3 JVM profile (JFR) and mitigation validation
- **Goal:** Confirm that the profile mitigations reduced bottlenecks and did not introduce regressions.
- **Action:**
  - 30s JFR profile via `jcmd` during k6 load on the hot path (`GET /{id}`, `POST /api/v1/urls`).
  - `./mvnw verify` after the applied mitigations.
  - Compare p95 before/after (recorded in `docs/performance-profiling.md`).
- **Acceptance criterion:** `./mvnw verify` green; p95 does not deteriorate; findings documented.

## 5.4 L1 cache externalized + behaviour
- **Goal:** Ensure the cache-aside (Caffeine L1 + bloom + Redis L2) is configurable and validated.
- **Action:**
  - IT for the `app.cache.l1.*` override (size/TTL) and the L1+bloom behaviour (hit/miss/bloom-negative).
  - Under load: collect `cache.hits.total`, `cache.misses.total`, `bloomfilter.rejections.total`, timers `url.retrieval.duration`/`redirect.latency`.
- **Acceptance criterion:** IT green; frozen metrics unchanged; hit-ratio/latency evidence pasted.

## 5.5 Stress 2x (ramping)
- **Goal:** Validate stability under load above nominal.
- **Action:**
  - `k6 run load-tests/stress.js` (ramping up to 2x nominal rps for 10min, thresholds p95 < 200ms / err < 0.1%).
  - Document `5xx`, p95 degradation, rate-limiter/cache behaviour; expected degradation is documented, not blindly "fixed".
- **Acceptance criterion:** Report pasted; degradation/5xx documented in `epic-5-dod.md`.

## Regression gates (per story and at the end)
- [ ] `./mvnw verify` → `metrics-frozen-check` PASS + `promtool test rules` green + `amtool check-config` green.
- [ ] `scripts/check-boundaries.sh` (+ `--self-test`) → PASS.
- [ ] `scripts/check-doc-sync.sh` → PASS (doc sync is part of *done*).
- [ ] Command outputs pasted in `epic-5-dod.md`.

---

**Epic 5 completion checklist:**

- [x] k6 scripts generated/validated (p95 within limits; like-for-like resolved)
- [x] JFR profile completed and mitigations evaluated (none justified by the data)
- [x] L1 cache externalized and evidenced
- [x] `metrics-frozen-check` PASS + `promtool test rules` green + `amtool check-config` green
- [x] `./mvnw verify` overall green
- [x] Evidence pasted in `epic-5-dod.md`

*Once all items above are checked, Epic 5 is **complete**.*
