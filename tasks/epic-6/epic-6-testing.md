# Epic 6 – Testing Strategy [grounded]

## 6.1 ADRs
- **Objective:** Scale decisions recorded and reviewable.
- **Action:** 4 ADRs in `docs/adr/` (status/date/context/decision/consequences template); `git log --oneline -- docs/adr/` pasted.
- **Accepted criterion:** ≥2 ADRs (target 4) present; output pasted in `epic-6-dod.md`.

## 6.2 MongoDB indexes + explain
- **Objective:** Prove that the critical queries use the V1–V9 indexes (no blind creation).
- **Action:**
  - mongosh on the isolated infra with real data (stress pool).
  - `getIndexes()` + `explain("executionStats")`: `_id` lookup (redirect), cursor pagination (V7), analytics (V4), TTL (V5).
  - Confirm `ID_SCAN`/`IXSCAN` and minimal `totalDocsExamined`; **no** new migrations.
- **Accepted criterion:** Outputs pasted in `epic-6-dod.md`; `./mvnw verify` green.

## 6.3 Rate-limit + circuit breakers
- **Objective:** Provide evidence of the behavior of the already-implemented mechanisms.
- **Action:**
  - `./mvnw test -Dtest='RedirectRateLimitIT'` → green (429 after capacity, scopes, burst).
  - Under 2× load: `GET /actuator/circuitbreakers` → `CLOSED` states.
- **Accepted criterion:** Outputs pasted.

## 6.4 Multi-instance deploy (artifacts)
- **Objective:** Artifacts ready for N instances on bare metal.
- **Action:**
  - nginx multi-server upstream (weight-flip), systemd template `url-shortener@.service`, image build (size + sha tag), updated runbook.
- **Accepted criterion:** Configs in the repo; `docker images` and procedure pasted.

## 6.5 Horizontal scale under load (2 instances + LB)
- **Objective:** Validate that the stateless design actually scales horizontally.
- **Action:**
  - 2 instances sharing Mongo/Redis behind an nginx LB.
  - `stress.js` 2× via LB: p95 < 200ms, 0 5xx.
  - Shared rate-limit proof: real limits → burst via LB → 429 after the **global** capacity.
- **Accepted criterion:** Reports pasted in `epic-6-dod.md`.

## 6.6 Backward-compatible integration (gates)
- [x] `./mvnw verify` full suite → green (frozen metrics, boundaries, doc-sync, SpotBugs, OWASP).
- [x] `promtool` + `amtool` green.
- [x] Outputs pasted in `epic-6-dod.md`.

---

**Epic 6 completion checklist:**

- [x] 4 ADRs created (`docs/adr/`)
- [x] Clean explain audit
- [x] `RedirectRateLimitIT` green + circuit breakers CLOSED under load
- [x] Multi-instance artifacts ready (nginx, systemd template, sha image, runbook)
- [x] Stress 2× via LB (2 instances) with SLOs ok and shared rate-limit proven
- [x] `./mvnw verify` full suite green
- [x] Evidence pasted in `epic-6-dod.md`

*Once all the items above are checked, Epic 6 is **complete**.*