# AI Software Engineer Prompt — Read-Path Confidence & Measured Baseline (Phase A)

**Status:** ready for implementation from current `main` (`293bb9e`, `v0.10.0`).
**Priority:** P1 — foundation of the "Bitly-like" turn. First epic of the next phase.
**Target:** make the redirect path **chatto, previsível e medido** — close the read-path residue (the
Bloom filter does not actually avoid MongoDB), publish the measured redirect baseline, and gate the
redirect path with k6 in CI. This is the foundation the product phases (PATCH, branded domain, analytics)
build on.

You implement the complete **Read-Path Confidence** epic. Do **not** build product features (PATCH,
domain, analytics rollup, API keys, webhooks) here — those are later phases.

---

## Sources of truth — read in this order

1. `AGENTS.md` (rules 5, 10; the P0-3 residue, the "no blocked redirect" principle)
2. `docs/data-model-decisions.md` (cache/bloom read-path)
3. `docs/coding-standards.md` (read path, no blocking in redirect)
4. `docs/load-test-baseline.md` (currently only `shorten`)
5. `tasks/read-path-confidence-spec.md`
6. `tasks/read-path-confidence-backlog.md`
7. `tasks/read-path-confidence-implementation-sequence.md`
8. `core/service/UrlShortenerService` (`getOriginalUrl`), `core/ports/outgoing/UrlCachePort`,
   `core/model/CachedUrlValue`, `infra/adapter/output/redis/RedisUrlCache`,
   `infra/.../migration/*`, `load-tests/*.js`, `scripts/performance-baseline.sh`,
   `.github/workflows/ci.yml`, `load-test.yml`

If documentation disagrees with executable configuration, stop, report and resolve in the same change.

---

## Goal

Currently `UrlShortenerService.getOriginalUrl` calls `urlRepository.findById(id)` **unconditionally** on a
cache miss — even when the Redisson Bloom filter says the code almost certainly does not exist. So
non-existent codes still hit MongoDB. The published load baseline only records `shorten`. And k6 is not a
blocking CI gate for the redirect path. This epic fixes the read path and makes it measured and gated.

---

## Locked technical decisions

1. **The Bloom filter must be treated honestly — POLICY B IS LOCKED** (chosen by the human). The Bloom
   filter is populated only on `put` (write) and the cache is not a complete mirror of the DB, so a
   bloom-negative can be a false 404 for a legit code. Under the locked **Policy B**, a bloom-negative is
   treated as a **lightweight cache-miss** and is resolved by `findById` — so the Bloom filter
   short-circuits only the Redis `get` (not the MongoDB lookup). This is the current behaviour and it is
   **the intended behaviour**. Document it explicitly and **correct the "bloom avoids the DB" claim.**
   Do **not** implement Policy A (which would skip `findById` on a bloom-negative) unless a later
   measured-load decision explicitly asks for it. Policy B is the default and is not open for
   reinterpretation during implementation.
2. **Absent-vs-miss signal.** The cache port must return an explicit signal (not an ambiguous `null`):
   a pure domain result in `core/model` (e.g. `CacheLookup(value, Absence)` with `NONE | MISS |
   BLOOM_NEGATIVE`). `core/` stays framework-free.
3. **Seeding is NOT required** under Policy B (the bloom only short-cuts the Redis `get`; correctness is
   preserved by `findById`). If/only if a future decision moves to Policy A, the bloom must be seeded from
   existing `short_urls` codes on startup (bounded, idempotent).
4. **Measured baseline.** Add `redirect` + `mixed` rows to `docs/load-test-baseline.md` (p50/p95/p99 +
   throughput + environment/limits).
5. **k6 as a CI gate** for the redirect path (thresholds-as-code: `p95 < 200 ms`, `error < 0.1%`), with a
   mixed workload that includes **non-existent codes** to prove the bloom short-circuit + rate limit
   under load.
6. **No public contract change** (still `302`/`404`/`410`/`429`). **No new Maven coordinate**.
7. **(Optional) Add `./mvnw`** and remove any residual `com.example` reference in docs.

---

## Non-negotiable engineering rules

- Keep `core/` framework-free; the absent-vs-miss result is a pure domain type.
- The redirect hot path **never** blocks or slows because of analytics/observability; it stays fast and
  non-blocking.
- The reducer/read path must not make an unconditional DB lookup for a bloom-rejected code **unless** the
  documented policy is B — in which case that behaviour is explicit, not a silent bug.
- English only in code, comments, logs, tests and docs.
- Do not push unless the human explicitly asks.
- Do not expand into: CRUD/PATCH of links, branded domain, analytics rollup, API keys, webhooks, the
  write/read split, or changing the HTTP contract.

---

## Required behaviour summary

### Read path
- A real link redirects through the cache.
- A **bloom-rejected** code is treated as a cache-miss and resolved by `findById` (Policy B, locked); the
  doc states the bloom short-circuits only the Redis `get`, not MongoDB.
- The choice is explicit and measured; the "bloom avoids the DB" claim is corrected to match reality.

### Measurement & gate
- `docs/load-test-baseline.md` lists `redirect` and `mixed` with p50/p95/p99 + throughput + environment.
- CI gate on the redirect path (thresholds-as-code), plus a mixed workload with non-existent codes that
  proves the bloom + rate limit under load.

### Optional polish
- `./mvnw` added; no `com.example` residue in docs.

---

## Scope exclusions

Do not implement: product features (PATCH, list, branded domain, analytics rollup, API keys, webhooks),
the read/write service split, multi-region, billing/orgs, or changing the public HTTP contract. No new
Maven coordinate.

---

## Definition of Done

- [ ] Absent-vs-miss signal across the cache port (pure domain type); `core/` framework-free.
- [ ] A bloom-rejected code is resolved by `findById` (Policy B), and the doc states the bloom
      short-circuits only the Redis `get` — not MongoDB.
- [ ] No false "bloom avoids DB" claim remains.
- [ ] Read-path integration test asserts the exact policy.
- [ ] `redirect` + `mixed` baselines published in `docs/load-test-baseline.md`.
- [ ] k6 redirect gate blocks CI on regression; invalid-code bench proves bloom/rate-limit effectiveness.
- [ ] `mvn test`, `mvn verify`, `check-boundaries.sh` pass.
- [ ] No doc claims "bloom avoids the DB" when it does not; P0-3 closed (per Policy B: the bloom
      short-circuits only the Redis `get`).
- [ ] (Optional) `./mvnw` works; no `com.example` residue.

Start at **Step 0** of `read-path-confidence-implementation-sequence.md`. Stop immediately if the
baseline is red, or repository state contradicts the spec. (The bloom policy is already locked as B — do
not reinterpret it.)
