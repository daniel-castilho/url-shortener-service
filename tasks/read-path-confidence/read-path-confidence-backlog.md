# Read-Path Confidence & Measured Baseline (Phase A) — Backlog

**Priority:** P1 — foundation of the "Bitly-like" turn. First epic of the next phase.
**All stories:** Must.
**Companions:** `read-path-confidence-spec.md` · `read-path-confidence-implementation-sequence.md`

**Execution status:** ready from `main` (`293bb9e`, `v0.10.0`).

---

## Epic outcome

The redirect path is **chatto, previsível, e medido**: a code the Bloom filter says is almost certainly
absent no longer triggers a MongoDB lookup (per a documented, explicit policy), the Bloom filter is
seeded from existing codes, the redirect baseline is published (p50/p95/p99), and **k6 is a blocking CI
gate** for the redirect path — including a workload with non-existent codes that proves the
short-circuit. The "Bloom avoids the DB" claim is now either true (Policy A) or honestly corrected
(Policy B).

---

## Story map

```text
READ PATH
R1  Cache "absent vs miss" distinction across the port (domain result type)
R2  Service short-circuits a bloom-rejected code (no unconditional findById)
R3  Explicit bloom false-positive policy + seed from existing codes
R4  Read-path integration test (bloom-negative does/does not hit DB per policy)

MEASUREMENT
M1  Publish redirect + mixed baseline (docs/load-test-baseline.md)
M2  k6 CI gate for redirect (thresholds-as-code) + bench with non-existent codes
M3  Seed the bloom filter from existing codes on startup

DELIVERY
V1  Docs sync (data-model-decisions, coding-standards, README, AGENTS, testing-playbook)
```

---

## R1 — Cache "absent vs miss" distinction

**Goal:** the read path can tell "not cached (miss)" from "bloom says absent".

### Work

- add a pure domain result (e.g. `CacheLookup(CachedUrlValue, Absence)` or a sealed type) in `core/model`;
- change `UrlCachePort.get`/`lookup` to return this; the adapter (`RedisUrlCache`) maps `null`→`MISS`,
  bloom-negative→`BLOOM_NEGATIVE`, hit→value.

### Acceptance

- [ ] The port returns an explicit absent/miss/notfound signal (no ambiguous `null`).
- [ ] `core/` stays framework-free.

---

## R2 — Short-circuit a bloom-rejected code

**Goal:** a code the bloom says is absent does not unconditionally hit the DB.

### Work

- in `UrlShortenerService.getOriginalUrl`, on `BLOOM_NEGATIVE`, follow the chosen policy (A or B):
  - A → throw `UrlNotFoundException` without `findById`;
  - B → treat as a lightweight miss and let `findById` resolve (documented as "bloom only skips the
    Redis get", matching scope) — but this does NOT satisfy "no DB hit"; only A does.

### Acceptance

- [ ] Per policy A, a bloom-negative produces a 404 with no `findById` call.
- [ ] Per policy B, the behaviour is explicitly documented and the claim corrected.

---

## R3 — Explicit false-positive policy + seed

**Goal:** the bloom is not a false claim; it is seeded and its trade-off is decided.

### Work

- seed the bloom from existing `short_urls` codes on startup (bounded, via a startup job or the migration
  runner);
- decide and record Policy A vs B; if A, measure the false-positive rate and keep a bounded fallback if
  needed.

### Acceptance

- [ ] The bloom is populated on startup (not empty).
- [ ] The chosen policy is documented and measured (if A); no ambiguous "bloom avoids the DB".

---

## R4 — Read-path integration test

**Goal:** prove the bloom behaviour in the read path.

### Work

- IT: a code present in the DB but not cached → `findById` resolves (correct);
- IT: a code the bloom rejects → either a 404 with **no** `findById` (Policy A) or a documented DB lookup
  (Policy B);
- IT: a real redirected link works through the cache after a `put`/seed.

### Acceptance

- [ ] The IT asserts the exact policy; a bloom-negative does or does not hit the DB as documented.

---

## M1 — Publish the redirect + mixed baseline

**Goal:** measured numbers are published, not implied.

### Work

- add `redirect` and `mixed` rows to `docs/load-test-baseline.md` with p50/p95/p99 + throughput and the
  run environment/limits.

### Acceptance

- [ ] `redirect` and `mixed` baselines are in the doc; not just `shorten`.

---

## M2 — Promote k6 to a blocking CI gate (+ non-existent codes bench)

**Goal:** a redirect-path regression fails CI.

### Work

- add a CI job running `scripts/performance-baseline.sh` (or `k6 run load-tests/redirect.js`) with the
  `p95 < 200 ms` / `error < 0.1%` thresholds;
- add a mixed workload with some **non-existent codes** and assert (via metrics/logs) that they do not
  flood the DB (bloom short-circuit under load).

### Acceptance

- [ ] The redirect k6 gate blocks CI on a regression.
- [ ] A bench with invalid codes shows the bloom short-circuit effective under load.

---

## M3 — Seed the bloom filter from existing codes

**Goal:** the bloom is a meaningful full-set filter, not empty.

### Work

- on startup, load the existing `short_urls` codes into the bloom (bounded, idempotent); document the
  trigger.

### Acceptance

- [ ] A fresh boot seeds the bloom so legit codes are not rejected.
- [ ] The seeding is bounded (does not OOM or block indefinitely).

---

## V1 — Documentation sync

**Goal:** docs match the implemented policy; claim corrected.

### Work

- `data-model-decisions.md` — "absent vs miss" design + chosen bloom policy;
- `coding-standards.md` — read-path rule (no unconditional DB lookup for a bloom-rejected code per policy);
- `README.md`/`AGENTS.md` — correct "Bloom filter short-circuits invalid IDs" to match reality (or true
  under Policy A); close P0-3; mark the k6 gate;
- `testing-playbook.md` — add read-path/bench ITs.

### Acceptance

- [ ] No doc claims bloom avoids the DB when it does not (unless Policy A makes it true).
- [ ] P0-3 marked closed per the chosen policy.

---

## Epic Definition of Done

- [ ] R1–R4 complete: absent-vs-miss across port, service short-circuits bloom-negative per an explicit
      documented policy, seeded bloom, read-path IT.
- [ ] M1–M3 complete: redirect/mixed baseline published; k6 is a CI gate with invalid-code bench; bloom
      seeded.
- [ ] V1 complete: docs and AGENTS reflect the real policy; P0-3 closed; no false "bloom avoids DB" claim.
- [ ] `mvn test`, `mvn verify`, `check-boundaries.sh` pass.
- [ ] The redirect path stays fast, non-blocking, and never slowed by analytics/observability.
