# Read-Path Confidence & Measured Baseline (Phase A) — Technical Specification

**Status:** ready for implementation from current `main` (`293bb9e`, `v0.10.0`).
**Priority:** P1 — foundation of the "Bitly-like" turn. First epic of the next phase.
**Companions:** `read-path-confidence-backlog.md` · `read-path-confidence-implementation-sequence.md`

---

## 1. Purpose

The product turn ("closer to a Bitly") is built on a foundation we can actually **trust and measure**.
The core is strong (Base62, cache L1→L2→Mongo, analytics async, rate-limit on both paths, TTL + 410,
migrations, OTel, k6), but two things undercut it:

1. **The read path has a real residue** (the P0-3 from the original audit that was never fully closed):
   on a **cache miss**, the service calls `urlRepository.findById(id)` **unconditionally** — even when
   the Bloom filter says the code almost certainly does not exist. So a non-existent code still hits
   MongoDB. The Bloom filter does **not** actually short-circuit the DB as the docs / README imply.
2. **There is no measured, CI-gated baseline for the redirect path.** k6 scenarios exist, but the
   published baseline only records `shorten`; the redirect numbers are not published, and k6 is not a
   blocking CI gate.

This epic makes the redirect path **chatto, previsível, e medido** — the exact foundation the product
phases (PATCH, branded domain, analytics) build on, and the thing the external review correctly said was
missing ("publique o estágio 1 medido").

---

## 2. Scope

### In scope

- **Fix the read path so a definitely-absent code does NOT hit MongoDB.** Introduce a way for the
  cache to distinguish "**not present (miss)**" from "**bloom says it almost certainly does not exist**",
  and have the service short-circuit a bloom-rejected code by resolving directly to a 404 **without**
  calling `findById`.
- **Handle the false-positive trade-off correctly.** Because the Bloom filter is populated only on
  `put` (write), and the cache is not a complete mirror of the DB, a legitimate code can be rejected by
  the bloom. So a bloom-rejection must be treated as "**probably absent**" — either (a) accept a small
  ~1% false-404 risk and document/measure it, or (b) keep a bounded fallback to `findById` but make the
  bloom a fast-path rejection. **Decide this explicitly; do not leave "bloom avoids the DB" as a false
  claim.**
- **Measure and publish the redirect baseline** (add `redirect` + `mixed` rows to
  `docs/load-test-baseline.md`).
- **Promote k6 to a CI gate** for the redirect path (thresholds-as-code), so a regression fails CI.
- Ensure the Bloom filter is **seeded** with existing codes on startup (or document the compromise) so
  it is not empty on a fresh boot.
- **(Optional) Add `./mvnw`** and remove any residual `com.example` path/name in docs.

### Out of scope

- CRUD/PATCH of links (Phase B), branded domain, analytics rollup, API keys, webhooks — later phases.
- Splitting redirect-api / write-api (Phase E).
- Changing the public HTTP contract (still `302`/`404`/`410`/`429`).

---

## 3. Architectural constraints

- `core/` stays framework-free. The "absent vs miss" distinction is expressed through a domain-level
  result type (no framework types in `core/`).
- The redirect hot path must remain fast, non-blocking, and **never** slow down because of analytics or
  observability.
- The Bloom filter and the cache stay in `infra`; only the *intent* (absent vs miss) crosses the port.

---

## 4. Read-path fix — absent vs miss

### 4.1 Problem

Today `UrlCachePort.get(id)` returns `CachedUrlValue` or `null`. `null` is ambiguous:
- **miss** = not in L1/L2 (but might exist in the DB), or
- **bloom-negative** = the bloom filter says it almost certainly does not exist.

On a `null`, `UrlShortenerService.getOriginalUrl` always falls through to `findById`. So a code rejected
by the bloom still triggers a DB lookup.

### 4.2 Design

Introduce an **absent/miss/notfound** signal that crosses the port as a pure domain value, e.g.:

```java
// core/model — framework-free
public record CacheLookup(CachedUrlValue value, Absence absense) {
    public enum Absence { NONE, MISS, BLOOM_NEGATIVE }
}
```

or a richer result sealed type. The service:

```java
CacheLookup lookup = urlCache.lookup(id);
if (lookup.value() != null) { /* hit -> check expiry -> redirect */ }
if (lookup.absense() == CacheLookup.Absence.BLOOM_NEGATIVE) {
    // Bloom says almost certainly absent. Decide: false-404 OR bounded fallback (see §4.3).
    return handleBloomNegative(id);
}
// MISS (not in cache, may exist) -> go to MongoDB as today
```

### 4.3 The false-positive trade-off (must be explicit)

The Bloom filter can say "not present" for a code that **does** exist (false positive ~1%). The cache is
populated on `put` only, so on a fresh boot the bloom is empty and a legitimate code could be rejected.

**Two viable policies — pick one and document it:**
`POLICY B IS LOCKED` (chosen by the human). See §4.3.

- **Policy A (strict, simplest, lowest DB load):** bloom-negative → return `UrlNotFoundException` (404)
  immediately, no `findById`. Risk: up to ~1% of *actually existent* codes return a false 404. For a
  public link that already failed once (e.g. a cached 404), the bloom is said to be wrong but the real
  link exists. This is only acceptable if reachable codes are reliably in the bloom.
- **Policy B (safe, recommended — LOCKED):** bloom-negative → treat as a lightweight **cache-miss**, and
  let `findById` resolve it. The bloom only short-circuits the Redis `get`, not the MongoDB lookup. The
  behaviour is **explicit and documented** — do **not** claim the bloom avoids the DB. This is the
  current behaviour; it keeps correctness and the small `findById` by `_id` cost is acceptable on a
  single on-prem box. Revisit → promote to Policy A only once measured load shows the bloom is a real
  bottleneck.

> ### DECISION: Policy B (LOCKED, 2026-08-28)
> A bloom-rejected code is treated as a **cache-miss** and resolved by `findById`. The Bloom filter
> short-circuits only the Redis `get` — **it does not avoid MongoDB**. Documentation must say this
> explicitly; it must NOT claim the bloom avoids DB hits. The redirect baseline is published and the
> path is measured; per Policy B the DB cost of a bloom-negative is accepted and measured. This is the
> default and is not open for reinterpretation during implementation.

### 4.4 Seeding (not required under Policy B)

Under the locked **Policy B**, the Bloom filter short-circuits only the Redis `get` and correctness is
preserved by `findById`, so **seeding the bloom is not required**. If a future decision moves to Policy
A, seed the Bloom filter from the existing `short_urls` codes on startup (bounded, e.g. via the migration
runner or a startup job) so it is not empty on a fresh boot.

---

## 5. Measured baseline + k6 gate

### 5.1 Publish the redirect baseline

Add rows to `docs/load-test-baseline.md` for `redirect` and `mixed` (currently only `shorten` is
recorded). Record p50/p95/p99 and throughput for a real run, and note the environment/Limits.

### 5.2 Promote k6 to a blocking CI gate

- Add a CI job that runs `scripts/performance-baseline.sh` (or `k6 run load-tests/redirect.js`) against
  the app + Mongo/Redis, with the existing `p95 < 200 ms` / `error rate < 0.1%` thresholds, and a
  **mixed workload that includes some non-existent codes** (to prove the bloom short-circuit and rate
  limit).
- Treat it as a **hard gate** once the numbers are calibrated; keep it `continue-on-error` only until 2–3
  runs establish a realistic floor.

### 5.3 Bench with non-existent codes

Add a k6 scenario/tag that requests a mix of valid + invalid codes, and assert (via metrics) that invalid
codes are served without a large DB-hit flood (i.e. the bloom short-circuit is effective under load).

---

## 6. Verification commands

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false      # incl. cache/bloom/repository ITs
mvn verify
bash scripts/check-boundaries.sh                 # unchanged (core stays clean)
scripts/performance-baseline.sh                  # with app up, relaxed rate limits
```

---

## 7. Documentation deliverables

- `docs/data-model-decisions.md` — note the read-path "absent vs miss" design and the **locked Policy B**
  (bloom-negative → cache-miss → `findById`), removing the ambiguous "bloom avoids the DB" claim.
- `docs/coding-standards.md` — read-path rule: a **cache-miss** may go to the DB; a **bloom-rejection** is
  treated as a cache-miss (Policy B) and may also go to the DB — but document that the bloom only
  short-circuits the Redis `get`, not MongoDB. No unconditional claim that bloom avoids the DB.
- `docs/load-test-baseline.md` — add `redirect` + `mixed` rows; note the bloom/rate-limit effectiveness.
- `README.md` / `AGENTS.md` — correct the "Bloom filter short-circuits invalid IDs" claim to match the
  implemented policy; close the P0-3 residue; mark the k6 gate.
- `docs/testing-playbook.md` — add the cache/bloom read-path ITs and the k6 bench scenario to the suite
  map / gaps.

The epic is **not** Done while a bloom-rejected code still triggers a DB lookup **and** that behaviour is
not the documented policy, or while the redirect baseline is not published / k6 is not a gate.
