# Read-Path Confidence & Measured Baseline (Phase A) — Implementation Sequence

**Companions:** `read-path-confidence-spec.md` · `read-path-confidence-backlog.md`
**Rule:** complete each step's acceptance and verification before starting the next. Do not invent
out-of-scope work.

---

## Global execution rules

1. Work in small, reviewable vertical commits.
2. Read the referenced story acceptance before coding.
3. **Decide the bloom false-positive policy FIRST** (A: no-DB-lookup on bloom-negative; B: documented
   DB lookup). This determines the whole read-path change.
4. Add tests with the production change, not at the end.
5. `core/` stays framework-free; the absent-vs-miss result is a pure domain type.
6. The redirect hot path never blocks or slows because of analytics/observability.
7. No new feature beyond this epic; no public contract change.
8. After each step, update task status and docs; do not silently alter the spec.

### Fast verification (throughout)

```bash
mvn test
```

### Integration verification

```bash
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

### Full gate

```bash
mvn verify
```

---

## Step 0 — Baseline, decide policy, confirm artifacts
### Stories: (context)

### Actions

1. Confirm HEAD (`293bb9e`) and fast tests green.
2. Confirm the residue: `UrlShortenerService.getOriginalUrl` calls `findById` unconditionally on cache
   miss, even when the bloom rejects the code; the bloom is populated only on `put`; the published
   baseline only lists `shorten`; k6 is not a CI gate.
3. **Decide Policy A vs B** for a bloom-rejected code (record it in `docs/data-model-decisions.md`).
4. Confirm `mvnw` is absent (optional add) and any residual `com.example` in docs.

### Done when

- residue understood; bloom policy decided and recorded;
- no unresolved choice remains.

### Verify

```bash
mvn test
```

---

## Step 1 — Cache "absent vs miss" across the port
### Stories: R1

### Actions

1. Add a pure domain result (`CacheLookup` or sealed type) in `core/model`.
2. Change `UrlCachePort` to return it; update `RedisUrlCache` to map `null`→`MISS`, bloom-negative→
   `BLOOM_NEGATIVE`, hit→value.
3. Update the existing `RedisUrlCacheTest`.

### Done when

- the port returns an explicit absent/miss/notfound signal; core is framework-free;
- current tests compile/pass.

### Verify

```bash
mvn test
mvn test -Dtest='RedisUrlCacheTest' -DfailIfNoTests=false
```

---

## Step 2 — Service short-circuits a bloom-rejected code
### Stories: R2

### Actions

1. In `UrlShortenerService.getOriginalUrl`, branch on the absence signal:
   - hit → check expiry → redirect;
   - `BLOOM_NEGATIVE` → per the chosen policy (A: throw `UrlNotFoundException` without `findById`; or
     B: treat as a miss / document).
   - `MISS` → `findById` as today.

### Done when

- (Policy A) a bloom-negative yields a 404 with no `findById`;
- (Policy B) behaviour documented and the claim corrected;
- the read path is fast and correct.

### Verify

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 3 — Seed the bloom from existing codes + policy measurement
### Stories: R3, M3

### Actions

1. Add a startup job (or extend the migration runner) that seeds the bloom with existing `short_urls`
   codes, bounded and idempotent.
2. If Policy A, measure the false-positive rate (or keep a bounded fallback if needed).

### Done when

- bloom is populated on startup (not empty);
- the chosen policy is documented and measured (if A).

### Verify

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 4 — Read-path integration test
### Stories: R4

### Actions

1. IT: DB-present-but-not-cached code resolves via `findById` (correct).
2. IT: a bloom-rejected code → either 404 with no `findById` (A) or documented DB lookup (B).
3. IT: a real redirected link works through the cache after `put`/seed.

### Done when

- the IT asserts the exact policy.

### Verify

```bash
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 5 — Publish redirect + mixed baseline
### Stories: M1

### Actions

1. Capture a real run of `redirect` and `mixed` (with the app up + relaxed rate limits) and record
   p50/p95/p99 + throughput + the environment in `docs/load-test-baseline.md`.

### Done when

- the baseline has `redirect` and `mixed` rows, not just `shorten`.

### Verify

```bash
scripts/performance-baseline.sh   # with a running app and relaxed rate limits
```

> Load-testing requires Docker + a running app; this step is manual/CI, not `mvn test`.

---

## Step 6 — Promote k6 to a blocking CI gate
### Stories: M2

### Actions

1. Add a CI job that runs the redirect k6 with the `p95 < 200 ms` / `error < 0.1%` thresholds against
   the app + Mongo/Redis.
2. Add a mixed workload with **non-existent codes** and assert (via metrics/logs) they do not flood the
   DB.

### Done when

- a redirect regression fails CI;
- the invalid-code bench shows the bloom short-circuit effective under load.

### Verify

```bash
# CI / local: k6 run load-tests/redirect.js
```

---

## Step 7 — Optional: add `./mvnw`, clean residual `com.example` in docs
### Stories: (polish)

### Actions

1. Add the Maven wrapper (`mvn -N wrapper:wrapper` or the standard wrapper files).
2. Remove any `com.example` reference in docs/AGENTS/README.

### Done when

- `./mvnw` works; no `com.example` residue.

### Verify

```bash
./mvnw test
```

---

## Step 8 — Documentation sync + full gate
### Stories: V1

### Actions

1. `data-model-decisions.md` — "absent vs miss" design + chosen bloom policy.
2. `coding-standards.md` — read-path rule (no unconditional DB lookup for a bloom-rejected code per policy).
3. `README.md`/`AGENTS.md` — correct the "Bloom filter short-circuits invalid IDs" claim to match reality
   (or true under Policy A); close P0-3; mark the k6 gate.
4. `testing-playbook.md` — add read-path/bench ITs.

### Done when

- no doc claims bloom avoids the DB when it does not (unless Policy A);
- P0-3 closed per the chosen policy.

### Verify

```bash
mvn verify
bash scripts/check-boundaries.sh
```

---

## Final smoke / acceptance path

1. A real link redirects through the cache (not served from DB every time).
2. A code the bloom rejects → per Policy A a 404 with no DB hit; per Policy B a documented lookup.
3. Restart the app → the bloom is seeded (legit codes not rejected).
4. `docs/load-test-baseline.md` lists `redirect` and `mixed`.
5. CI gate fails if redirect p95/error crosses the threshold; invalid-code bench shows no DB flood.
6. `mvn verify` + `check-boundaries.sh` pass; no false "bloom avoids DB" claim.

---

_Pre-implementation sequence. Preserve deviations and final evidence as an as-built record after delivery._
