# Epic 6 – Technical Tasks [grounded]

Milestones `[x]` are marked during execution; evidence is pasted into `epic-6-dod.md`.

## 6.1 Decision records (ADRs)
- [x] Create `docs/adr/` with 4 ADRs (0001–0004), template: status/date/context/decision/consequences.
- [x] 0001: stateless horizontal scaling + shared resources (vs vertical) — anchor `docs/twelve-factor.md` §6/§8.
- [x] 0002: per-IP **global** rate-limit via Redis (atomic Lua bucket shared between instances).
- [x] 0003: per-instance Caffeine L1 (TTL 5s = bounded staleness; bloom/L2 shared).
- [x] 0004: resilience4j circuit breakers (`databaseCb`) on the Mongo adapters.
- [x] Paste `git log --oneline -- docs/adr/` into `epic-6-dod.md`.

## 6.2 Index explain audit (no blind creation)
- [x] Isolated infra (Mongo 27018) with real data (stress pool).
- [x] mongosh: `db.short_urls.find({_id: "<code>"}).explain("executionStats")` → ID_SCAN, minimal docs examined.
- [x] mongosh: explain of cursor pagination (`userId` + `createdAt DESC` + cursor) → IXSCAN on `userId_1_createdAt_-1` (V7).
- [x] mongosh: explain of the rollup/aggregation (`shortCode`+`day`) and `click_events` (V4).
- [x] mongosh: `getIndexes()` of `short_urls`/`click_events` pasted (proof of the V1–V9 set).
- [x] `./mvnw verify` → green.
- [x] Paste outputs into `epic-6-dod.md`.

## 6.3 Rate-limit + circuit breakers (evidence of what exists)
- [x] `./mvnw test -Dtest='RedirectRateLimitIT'` → green (5 tests), output pasted.
- [x] Under 2× load (story 6.5): `GET /actuator/circuitbreakers` → `databaseCb`/`rateLimiterCb` `CLOSED`, output pasted.
- [x] Document the real configs in the DoD: `rate-limiter.*` (60/PT1M, 120/PT1M, scopes) and `resilience4j.circuitbreaker.*` (window 10, min 5, 50%/20s).

## 6.4 Multi-instance release artifacts
- [x] `deploy/proxy/nginx.conf`: `url_shortener_backend` upstream with N servers + commented weights (flip 10→30→100).
- [x] `deploy/url-shortener@.service`: systemd template (`url-shortener@1.service`, `@2.service`, distinct ports).
- [x] `docker build -t url-shortener:sha-<short> .` → `docker images` with size + sha tag pasted.
- [x] Update `docs/release-runbook.md` with the multi-instance procedure + weight-flip.
- [x] Paste outputs into `epic-6-dod.md`.

## 6.5 Horizontal scale validation (2 instances + LB)
- [x] Boot 2 instances (ports 18080/18081) sharing Mongo 27018 + Redis 6380 (rate limits relaxed for the stress).
- [x] nginx container LB in front of the 2 instances.
- [x] `stress.js` 2× (ramping 400/40, hold 4m) via LB → p95 < 200ms, 0 5xx; summary pasted.
- [x] **Shared rate-limit proof:** with real limits (redirect 120/min), burst via LB → 429 after the **global** capacity (not per instance); output pasted.
- [x] Evidence in `epic-6-dod.md`.

## 6.6 Final epic gates
- [x] `./scripts/check-metrics-frozen.sh` (+ `--self-test`) → PASS.
- [x] `./scripts/check-boundaries.sh` (+ `--self-test`) → PASS.
- [x] `./scripts/check-doc-sync.sh` (+ `--self-test`) → PASS.
- [x] `promtool check rules` + `promtool test rules` + `amtool check-config` → green.
- [x] `./mvnw verify` full suite → BUILD SUCCESS.
- [x] Evidence pasted in `epic-6-dod.md`; self-audit run.

---

**Epic 6 completion checklist:**

- [x] 4 ADRs created (`docs/adr/`)
- [x] Clean explain audit (IXSCAN/ID_SCAN evidenced)
- [x] `RedirectRateLimitIT` green + circuit breakers CLOSED under load
- [x] Multi-instance artifacts (nginx, systemd template, sha image, runbook)
- [x] Stress 2× via LB (2 instances): SLOs ok, 0 5xx, shared rate-limit proven
- [x] `./mvnw verify` green (all gates)
- [x] Evidence pasted in `epic-6-dod.md`

*Once all the items above are checked, Epic 6 is **complete** with horizontal scale validated.*