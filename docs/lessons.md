# Lessons learned

Practical findings from running and hardening this URL Shortener Service. Add to this file whenever a
non-obvious failure or design decision cost real debugging time.

> **How this file was seeded (policy).** The original content came from a different Java/Spring Boot
> project (an AWS-side music service). Most of its *technical specific* lessons (DynamoDB GSIs,
> LocalStack persistence, presigned S3 uploads, Argon2id/BouncyCastle, Tauri) do **not** apply here
> (we use MongoDB + Redis on-premises, no cloud, no S3). However, the **transferable engineering
> discipline** — how to attack failures, Testcontainers lifecycle, daemon-version mismatches,
> graceful-shutdown teardown — is stack-agnostic and worth keeping. So: **general principles were
> retained; project-specific ones were replaced with lessons relevant to this codebase.** Update in
> the same spirit: keep what's durable, drop what's specific to another stack.

---

## Debugging discipline (how to attack failures)

- **Research beats trial-and-error.** When a failure is not instantly explainable from local evidence,
  search the web FIRST (exact error text / symptom + component + "fixed version"). Recent examples:
  Testcontainers/docker-java daemon-API version mismatches, `jjwt` API version differences, and
  Resilience4j annotation configuration — all solved by primary-source lookup, none by guessing.
- **Triage many errors easiest-first.** With a wall of failures, fix the cheap independent ones first
  (missing annotation, wrong import, stale stub) to shrink noise, then re-run to surface what is
  actually deep. Build a full error inventory (`grep` the compiler/test output, `sort -u`) before
  touching code; fix one root cause per edit.
- **Spring context failure is not the same as a test assertion failure.** If the app does not boot,
  read the startup log first (missing config, bad bean, unmatched property) before blaming the test.

## Testcontainers / Docker

- **docker-java in Testcontainers 1.19.x cannot talk to Docker 29+** (daemon API 1.53, MinAPIVersion
  1.44). Containers fail to start with HTTP 400 / empty info. Fix: raise `testcontainers.version` to a
  compatible release. A version bump is not a new Maven coordinate, so it does not need rule-5
  approval.
- **Debug Testcontainers startup** by pointing logback at a temp file with DEBUG for
  `org.testcontainers` and `com.github.dockerjava`:
  `mvn test -Dlogback.configurationFile=/tmp/logback-testcontainers.xml -Dtest='<MyIT>'`.
- **MongoDB/Redis start empty.** `BaseIntegrationTest` provisions the schema and drops/flushes each
  test method — don't assume persisted state across tests.

## Spring context caching vs. Testcontainers lifecycle

- A **`static @Container` field in a shared base class** is stopped by Testcontainers after the first
  test class that uses it, while Spring's cached `@SpringBootTest` context still points at the now-dead
  port → `Connection refused` on every subsequent test class.
  Symptom: the *first* test passes, all *following* ones error on the first request.
  Fix: start the container manually (no `@Container`) as a `static final` field so it lives for the
  whole JVM and the cached context always targets a live endpoint. (`BaseIntegrationTest` already does
  this — keep it that way; note `@DirtiesContext` strategies can also restart the context, at the cost
  of speed.)

## Metrics / counters (double-counting, atomicity)

- **Recording the same metric in two layers double-counts.** `urls.shortened.total` was incremented
  both in `UrlShortenerService` (via `MetricsPort`) and in `UrlController` (via `MetricsService`) —
  Micrometer dedupes by name, so one shorten produced **+2**. Record a business metric in exactly one
  place (prefer the controller, which already measures latency, or the service — not both).
- **Counters must be atomic.** `QuotaService.incrementVanityUrlUsage` does `set(get()+1)`
  (read-modify-write) and **loses increments under concurrency**. Any counter update must use an atomic
  `$inc` on the storage side (see `data-model-decisions`).

## ID generation & codes (locked: random Base62)

- **A reversible encoder plus a sequential counter is the wrong identity model.** Hashids + Redis
  `INCRBY` (and a salt) made codes enumerable if the salt leaked and coupled generation to a central
  counter. The locked model is random Base62 from `SecureRandom`; collisions are **retried on the
  unique `_id`** (bounded). There is no `SHORTENER_SALT` in the product contract. See
  `data-model-decisions`.
- **A `UNIQUE` index that doesn't match the intended semantics causes misleading errors.** Unique-on-
  `originalUrl` forced dedup, so a duplicate URL was surfaced as `AliasAlreadyExistsException` / `409`.
  The locked model has **no URL dedup**; `409` means only “custom alias already exists”.
- **Dead metrics lie.** Timers registered but never recorded (`id.generation.duration`,
  `url.retrieval.duration`) look like coverage. Either wire them or delete them (story I7).

## Caching & the bloom filter

- **A "protection" layer that doesn't short-circuit the expensive call is just overhead.** The bloom
  filter correctly returned "probably not present" for unknown codes, but the service still queried
  MongoDB and threw 404 — so invalid codes still hit the database. Ensure a bloom-negative short-
  circuits the read path (or document that it only skips the Redis get, not the DB).
- **Cache stampede** is mitigated by TTL jitter; keep jitter on writes. Local (Caffeine) L1 and Redis
  L2 must be treated as **best-effort** — a Redis outage should degrade to DB lookup, never fail the
  redirect.

## Security / SSRF / validation

- **A regex like `^https?://.*` is not URL validation.** It accepts malformed hosts, allows `http://`,
  and does **not** block internal/link-local IPs (`169.254.169.254`, `127.0.0.1`, RFC1918) — an SSRF
  vector. Keep the value object's `http(s)` guard for the XSS/`javascript:` case, but add host
  validation, HTTPS-first policy and a private-IP blocklist.
- **Fail-open vs. fail-fast must be deliberate.** The rate limiter and the DB circuit breaker use
  different policies (`rateLimiterCb` fail-open, `databaseCb` fail-fast). Document the intent; don't
  copy one onto the other.

## Spring / configuration

- **A default secret that ships in source is a trap.** The JWT provider correctly validates the minimum
  length and warns on the default — keep that, and always override `APP_JWT_SECRET` via env in
  production. Never reintroduce a code-generation salt. Never commit real secrets.
- **`@ConfigurationProperties` over scattered `@Value`.** Typed properties keep config reviewable and
  validate at startup. Add `ProdConfigValidator`-style fail-fast startup checks for required prod env
  vars, so an incomplete contract fails loudly at boot, not mid-traffic.
- **GraalVM native `mainClass` must point at the real class.** The `native` profile referenced
  `ca.tyny.urlshortener.infra.Application` (a class that does not exist; the entry point is
  `ca.tyny.urlshortener.Application`). A wrong `mainClass` silently breaks `-Pnative`.
- **Framework-free core = explicit wiring, not annotation purgery.** Removing `@Component` from
  `core/` classes without registering replacements breaks the Spring context at startup. The pattern
  that works: explicit constructors in `core/`, `@Bean` methods in `infra/config/ServiceConfig`
  (with `@Order(1)` on the vanity strategy so the composite generator evaluates it first). Lombok
  stays available in `infra/` adapters — the boundary is what matters, not zero-Lombok everywhere.
- **A gate without a self-test can rot silently.** The CI boundary check grepped the wrong package
  path after a rename and reported PASS on a violating tree — a green check proved nothing. Any
  boolean gate (grep, regex, policy) needs a `--self-test` mode that plants a violation and asserts
  the gate catches it; run both modes in CI.
- **Quality gates need headroom or they get bypassed.** Wiring JaCoCo at 60% against an existing
  codebase sitting at ~41% line coverage invites "skip the gate" pressure. Land the gate *and* the
  tests that clear it in the same change set (unit suite went 87 → 125 tests), so the floor starts
  honest.

## Operations

- **The 500 log line should carry method + URI + exception class**, which turns infra failures into a
  quick diagnosis instead of a dump-scrape. When touching `GlobalExceptionHandler`, keep the request
  context in the error log.
- **Graceful shutdown teardown:** during drain, expect a mix of 200 (in-flight OK), 503/000
  (post-drain rejections) *and* 400 (keep-alive teardown mid-parse). Treat 400 during drain as expected
  teardown, not necessarily a defect.

## Architecture discipline

- **Exception hierarchy matters for retry semantics.** When `MongoUrlRepository` caught
  `DuplicateKeyException` and always threw `AliasAlreadyExistsException`, the retry logic in
  `UrlShortenerService` could not distinguish a collision (retryable) from a user-supplied alias
  conflict (not retryable). Creating `ShortCodeCollisionException` and narrowing the catch to that
  type made the retry bounded and correct. The same principle applies to any retry loop: the
  caught exception must encode exactly the retryable condition.
- **Language discipline in codebases.** Portuguese comments, log messages, and Javadoc accumulate
  silently when the team is bilingual. Enforcing English-only in code reviews prevents a class of
  "documentation debt" that is invisible to linters but slows onboarding. Translating comments
  in-place (not just deleting them) preserves the original design intent.
