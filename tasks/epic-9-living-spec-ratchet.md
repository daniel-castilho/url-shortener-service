# Epic 9 — Living-Spec Ratchet: every component spec-complete

**Regra zero — zero-from-memory:** every number, sha or count in this document is pasted from a
command output included in this document or in the story commits.

Opens 2026-09-13 against `main` at `a5d1f53` (+ `38b56d0` debt 32a, `85865bc` ADR 0009).
Closes when S5 merges: the living-spec debt registry has **zero** entries for the five components
and debt 32(b) resolves.

## Why this epic exists

The living-spec machinery shipped with the RateLimiting pilot (debt 32, Phase 0+1):
`package-info.java` EARS declarations, `@TracesRequirement` as the single traceability source,
`scripts/check-living-spec.sh` (+ `--self-test`) as a hard CI gate with the 90% threshold, the
dangling-ref check and the untraced-test-class debt registry (AGENTS.md). Only one component is
gated. This epic walks the ratchet across the remaining five Business Components, one story per
component, each independently mergeable — the gate turns on per component at story merge.

Owner decisions (2026-09-13, recorded in the tasking session):

- **D3 — packaging:** ONE epic, FIVE stories, in this order: S1 Cache → S2 Auth → S3 Analytics →
  S4 Persistence → S5 UrlShortener. Cache first because it is smallest, it retires the
  `RedisUrlCacheTest` registry entry, and it carries the cache versioning fix (S1a). Registry
  entries expire "at the next epic" — **this epic IS the deadline**.
- **D4 — EARS granularity:** the 90% threshold is contract; granularity is the only dial. Write
  each requirement at the granularity where it maps to ≥1 **existing** test; if a candidate
  requirement has no existing test observing that behavior, split or merge it before writing any
  new test. New tests only where no existing test observes the behavior. The RateLimiting pilot
  (`infra/adapter/output/redis/package-info.java`, REQ-RATE-001..005) is the calibration
  reference: observable behavior, port-level language, one When/shall pair per requirement.

## Shared Definition of Done (every story)

1. `package-info.java` living spec for the component (EARS calibrated per D4; `@spec-complete`
   starts `false` and flips `true` in the same story commit).
2. Existing tests annotated with `@TracesRequirement("REQ-<COMP>-<NNN>")` — new tests only per D4.
3. Any untraceable test class inside the component's gated package is registered in the AGENTS.md
   living-spec debt registry (class, reason, owner, deadline) — or annotated.
4. `bash scripts/check-living-spec.sh` **green with that component gated** (it must appear in the
   output with ≥ 90% coverage) and `--self-test` green.
5. `./mvnw verify` green (unit + IT + JaCoCo + SpotBugs + Spotless).
6. CHANGELOG entry for the story; the matrix/registry entries the story settles are closed in the
   same commit.
7. Evidence pasted in the story's evidence block below (gate output naming the component).

## Grounded inventory (what each story works with — verified 2026-09-13)

```
$ grep -c '@Test' <key test classes>
src/test/java/ca/tyny/urlshortener/infra/adapter/input/rest/AuthControllerTest.java: 4
src/test/java/ca/tyny/urlshortener/core/service/UserServiceTest.java: 5
src/test/java/ca/tyny/urlshortener/infra/adapter/output/analytics/RedisClickEventQueueTest.java: 2
src/test/java/ca/tyny/urlshortener/infra/adapter/output/analytics/RedisClickEventQueueFailOpenTest.java: 2
src/test/java/ca/tyny/urlshortener/infra/adapter/output/analytics/ClickBatchWorkerMappingTest.java: 5
src/test/java/ca/tyny/urlshortener/infra/adapter/output/persistence/migration/MongoSchemaMigratorTest.java: 6
src/test/java/ca/tyny/urlshortener/infra/adapter/output/redis/RedisUrlCacheTest.java: 7
src/test/java/ca/tyny/urlshortener/core/service/UrlShortenerServiceTest.java: 30
```

## Stories

| # | Story | Acceptance criteria (grounded) | Real reference |
|---|-------|---------------------------------|----------------|
| **9.0** | **Gate multi-component support** — the gate today takes the component name from a single `# Component:` line per file; the redis package must host TWO components (RateLimiting + Cache). | • `check-living-spec.sh` (and `extract-requirements.sh`) parse **multiple** `# Component:` blocks per `package-info.java`: each `### REQ-*` heading belongs to the component declared in the nearest preceding `# Component:` line. Backward compatible: a file with one component behaves exactly as today.<br>• `--self-test` gains a case: one file, two components, mixed coverage → per-component coverage reported, correct FAIL.<br>• `release.yml` gates job runs `check-living-spec.sh` + `--self-test` (parity with ci.yml — without it the tag pipeline does not re-verify traceability). | `scripts/check-living-spec.sh`, discovery during planning (2026-09-13) |
| **9.1 (S1)** | **Cache** — version the cache key prefix, then the living spec. **S1a first commit (the versioning fix, spec-first):** `RedisUrlCache` keys are `"url:" + id` (verified `:99/:141/:223`) with no shape version — a changed `encode(value)` shape breaks new readers over stale entries until TTL. | **S1a:** • EARS: "the component shall include a shape version in the cache key prefix and bump it whenever the serialized shape changes" → key becomes `url:v1:<id>` in get/set/delete. • Test: an old-shape entry under the old key (`url:<id>`) is **never** read; a new-key miss rebuilds from the source (verify `opsForValue().get("url:" + id)` is never called; `url:v1:<id>` miss → fetch + `set(url:v1:...)`). • `RedisUrlCacheTest` updated (8 `"url:"` expectation sites → `"url:v1:"`). • **Retires** the `RedisUrlCacheTest` entry from the living-spec debt registry. **S1b (second commit):** • Cache EARS calibrated to the remaining existing tests (lookup hit / miss / bloom-negative, evict, TTL+jitter semantics; ~3–5 requirements) → `@spec-complete true` → gate green with Cache gated → registry entry gone (S1a already removed it) → CHANGELOG. | `infra/adapter/output/redis/RedisUrlCache.java`, `RedisUrlCacheTest.java` (7 tests), `UrlCachePort`/`CacheLookup` |
| **9.2 (S2)** | **Auth** — register/login/refresh + authz boundaries. Spec lives in `infra/security` (the component's authentic package: `JwtTokenProvider`, `CustomUserDetailsService`, JWT filter; **no test classes there → zero stray-class burden**). | • ~4–5 requirements mapped to the existing tests: `AuthControllerTest` 4 (`shouldRegisterUser`, `shouldLoginUser`, `shouldValidateRegisterRequest`, `shouldRefreshToken`) + `UserServiceTest` 5 (`shouldRegisterUser`, `shouldThrowWhenEmailExists`, `shouldLoginUser`, `shouldRefreshToken`, `shouldThrowWhenRefreshTokenInvalid`). • Granularity per D4 — e.g. REQ-AUTH-001 register (happy + duplicate → 409), REQ-AUTH-002 login (happy + bad credentials → 401), REQ-AUTH-003 refresh (valid rotates tokens; invalid/missing → 401), REQ-AUTH-004 registration input validation (malformed → 400). Exact split decided in-story against the tests, threshold 90% respected. | `infra/security/*`, `AuthControllerTest`, `UserServiceTest` |
| **9.3 (S3)** | **Analytics** — track/aggregate/rollup/retention **plus the blue/green payload-compat requirement**. Spec lives in `infra/adapter/output/analytics`. | • ~3–4 requirements from: `RedisClickEventQueueTest` 2 + `RedisClickEventQueueFailOpenTest` 2 (queue bounded + durable, fail-open), `ClickBatchWorkerMappingTest` 5 (entity mapping + bulk insert + `$inc`), `ClickAnalyticsIT`/`ClickDailyRollupIT`/`ClickPipelineIT`/`ClickPipelineRedeliveryIT` (track, rollup, at-least-once redelivery), `ClickEventsRetentionPurge` (retention). • **REQ-ANALYTICS payload compatibility (project rule as EARS):** "**When** the ClickEvent payload shape changes, **the Business Component shall** keep payloads readable by the previous release's consumer for one release cycle (additive change, then drop)" — the blue/green cutover window runs old-consumer/new-producer concurrently on the same Redis Stream. • `GeoIpCountryResolverTest`, `UserAgentParserTest` — annotate or register per D4. | `infra/adapter/output/analytics/*`, ADR 0006 (at-least-once), ADR 0007 (blue-green window) |
| **9.4 (S4)** | **Persistence** — Mongo adapters + `MongoSchemaMigrator`. Spec lives in `infra/adapter/output/persistence`. | • Requirements from: `MongoSchemaMigratorTest` 6 (`appliesAllOnFreshDatabase`, `skipsAlreadyAppliedWithMatchingChecksums`, `reappliesIdempotentMigrationOnChecksumDrift`, `toleratesMissingOriginalUrlIndex`, `failsFastWhenMigrationThrows`, `rejectsDuplicateVersions`) + `SchemaMigrationIT` + `MongoUrlRepositoryIT`/`MongoUserRepositoryIT`. • **Expand-only as a real EARS requirement** (checklist rule → spec): migrations are additive-only within a release; destructive changes ship one release earlier or behind a flag. • Fail-fast at boot, migration counters (`schema.migrations.*`), idempotent re-apply on checksum drift. | `infra/adapter/output/persistence/migration/*`, runbook §7 expand-only line |
| **9.5 (S5)** | **UrlShortener** — the core use cases; the biggest and last (crosses the most packages). Spec lives in `core/service`. | • ~6–8 requirements from `UrlShortenerServiceTest` 30 tests: shorten (auto + vanity), collision retry (bounded, exhaustion), cache-aside lookup (hit/miss + metrics), expiry (eager check, cached-expired), custom-domain host binding (bound/unbound/cross-domain/unknown/null/normalize-port/ownership), expiresAt propagation. `LinkUseCasesTest` (12) + `CustomDomainServiceTest` + `QuotaServiceTest` trace to UrlShortener-family requirements where granularity fits (D4) or get registered with a reason. • `GetClickAnalyticsUseCaseTest` → traces to ANALYTICS/AUTH requirements already declared by S2/S3. • At merge: registry zero for all five components → **debt 32(b) resolves**, debt 32 flips to `resolved`. | `core/service/*`, `core/idgeneration/*`, `core/validation/*` |

## Evidence (filled at story merge; paste the gate output naming the component)

### 9.0 — Gate multi-component + release.yml

```
$ bash scripts/check-living-spec.sh --self-test
PASS: self-test verified — gate detects missing traces, stray classes, dangling refs, respects
the ratchet, and parses multiple components per file.   (8 cases)

$ bash scripts/check-living-spec.sh
=== Living Specification Gate ===
Threshold: 90%
  OK      REQ-RATE-005 (RateLimiting) — traced
  ... (single-component output unchanged)
Coverage: 5 / 5 requirements traced (100%)
PASS: living specification gate.
```

`release.yml` gates job: `bash scripts/check-living-spec.sh && bash scripts/check-living-spec.sh --self-test`
added (parity with ci.yml). Commit `5ce213f`.

### 9.1 — Cache (S1a versioning + S1b spec-complete)

```
$ bash scripts/check-living-spec.sh        (after S1b merge)
=== Living Specification Gate ===
Threshold: 90%
  OK      REQ-RATE-001..005 (RateLimiting) — traced (5 lines)
  OK      REQ-CACHE-001 (Cache) — traced
  OK      REQ-CACHE-002 (Cache) — traced
  OK      REQ-CACHE-003 (Cache) — traced
  OK      REQ-CACHE-004 (Cache) — traced
  OK      REQ-CACHE-005 (Cache) — traced
Coverage: 10 / 10 requirements traced (100%)
PASS: living specification gate.

$ ./mvnw test -Dtest=RedisUrlCacheTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0    (7 existing + REQ-CACHE-001 x2 + REQ-CACHE-004 evict)
```

S1a commit `7771d56` (key versioning `url:v1:<id>`, REQ-CACHE-001 declared, registry entry for
`RedisUrlCacheTest` retired). S1b commit: REQ-CACHE-002..005 calibrated to existing tests,
evict test added (no existing test observed the adapter's evict — D4 new-test case),
`@spec-complete true`.

### 9.2 — Auth

```
$ bash scripts/check-living-spec.sh        (after S2 merge)
  OK      REQ-AUTH-001..004 (Auth) — traced (4 lines)
Coverage: 14 / 14 requirements traced (100%)   (RateLimiting 5 + Cache 5 + Auth 4)

$ ./mvnw test -Dtest='AuthControllerTest,UserServiceTest'
Tests run: 4, Failures: 0 ... in AuthController Tests
Tests run: 5, Failures: 0 ... in ca.tyny.urlshortener.core.service.UserServiceTest
```

Spec lives in `infra/security` (the component's authentic package; no test classes there → zero
stray-class burden). REQ-AUTH-001..004 calibrated to the 9 existing tests (AuthControllerTest 4 +
UserServiceTest 5): register happy+duplicate, login, refresh happy+invalid, registration input
validation. No new tests needed. `@spec-complete true`.

### 9.3 — Analytics

```
$ bash scripts/check-living-spec.sh        (after S3 merge)
  OK      REQ-AUTH-001..004 (Auth) — traced (4 lines)
  OK      REQ-ANALYTICS-001..006 (Analytics) — traced (6 lines)
  OK      REQ-RATE-001..005 (RateLimiting) — traced (5 lines)
  OK      REQ-CACHE-001..005 (Cache) — traced (5 lines)
Coverage: 20 / 20 requirements traced (100%)

$ ./mvnw test -Dtest='ClickEventsRetentionPurgeTest'
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

Spec in `infra/adapter/output/analytics/package-info.java`: REQ-ANALYTICS-001 (fire-and-forget +
fail-open queue), 002 (worker-side enrichment + bulk insert + atomic `$inc` + blank-code skip),
003 (PEL redelivery + poison finalize, at-least-once), 004 (idempotent daily rollup + HLL uniques +
owner-guarded series read), 005 (bounded-batch retention purge, fail-open), **006 (payload shape
compatibility for one release cycle — the blue/green cutover window, project rule promoted to
EARS)**. 26 existing tests annotated (pipeline, queue, fail-open, redelivery, worker mapping,
rollup, GeoIP, UA parser, analytics read IT). New test only per D4: `ClickEventsRetentionPurgeTest`
(4 cases — no existing test observed the purge). `@spec-complete true`.

### 9.4 — Persistence

```
$ bash scripts/check-living-spec.sh        (after S4 merge)
  OK      REQ-PERSIST-001..008 (Persistence) — traced (7 lines)
  MISSING REQ-PERSIST-003 (Persistence)
Coverage: 27 / 28 requirements traced (96%)
PASS: living specification gate.
```

Spec in `infra/adapter/output/persistence/package-info.java`: REQ-PERSIST-001 (ordered,
recorded, idempotent migrations), 002 (fail-fast on checksum drift / duplicate version /
throwing migration), **003 (expand-only migrations — the runbook §7 release rule promoted to
EARS; forward-looking discipline, enforced at review + checklist, not testable retroactively —
first component to exercise the 90% threshold as designed)**, 004 (atomic `$inc`, no-op on
missing), 005 (storage-level email uniqueness), 006 (owner-scoped cursor pagination, malformed
cursor rejection), 007 (archive soft-delete), 008 (update keeps identity, persists supplied
fields). 27 existing tests annotated (migrator 6, SchemaMigrationIT 2, MongoUrlRepositoryIT 13,
MongoUserRepositoryIT 6). No new tests needed. `UserEntityTest` (structural POJO test) registered
in the debt registry per decision 4. `@spec-complete true`.

### 9.5 — UrlShortener + debt 32(b) closure

_(pending)_

---

*Out of scope (standing decisions): no AST/doclet extractor for the gate (ADR 0009 revisit trigger
governs), no new thresholds, no log aggregation, no feature-flag framework, no DORA dashboards.
Standing accepted debts unchanged: Mongo/Redis SPOF, off-host backup, JWT rotation.*
