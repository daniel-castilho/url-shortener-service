# Changelog

All notable changes to URL Shortener Service will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project
intends to follow [Semantic Versioning](https://semver.org/) starting from its first tag.

## [Unreleased]

### Added

- **Living specifications (spec-driven development, Phase 0+1)** — EARS requirements now live in
  `package-info.java` living specs per Business Component (pilot: RateLimiting, 5 requirements:
  429+headers on limit excess, SHORTEN/REDIRECT scope isolation, trusted-proxy CIDR for
  `X-Forwarded-For`, fail-open on Redis failure, bypass when disabled). `@TracesRequirement`
  (`core/annotation`) is the single source of truth linking tests to requirements; the new
  `scripts/check-living-spec.sh` hard gate (+ `--self-test`) enforces ≥ 90% traceability for
  `@spec-complete` components, rejects dangling traces and unregistered untraced test classes
  (debt registry in `AGENTS.md`), and runs in CI. Supporting tooling:
  `scripts/extract-requirements.sh` (JSON export) and `scripts/generate-package-info.sh`
  (scaffolding). Spotless excludes `package-info.java` (google-java-format would reflow the EARS
  structure the gate parses).

- **Alert rules for the frozen meters** — `RedirectLatencyP99AboveSLO` (warning, `redirect_latency_seconds{quantile="0.99"} > 0.2` with a 5m traffic guard, so idle windows never alert) and `RateLimitExcessiveTrafficRejected` (warning, share of all traffic rejected by the rate limiter > 5% — the enumeration/abuse signal), both in `deploy/monitoring/alerts.yml` with on-call runbook annotations, plus four `promtool` rule tests in `deploy/monitoring/rules_tests.yml` (fire/no-fire pairs). `docs/slos.md` now names the real rules instead of "threshold alerts from p99 panel".

### Fixed

- **`rate.limit.exceeded.total` meter never incremented (debt 32a)** — the counter was registered
  and frozen but had no production caller, so the `RateLimitExcessiveTrafficRejected` alert could
  never fire. The `UrlController.tooManyRequests` helper (the single 429 egress) now records it —
  every rejected request counts exactly once; behavior (status, headers, anti-enumeration ordering)
  unchanged. Verified live via `/actuator/prometheus` with `RATE_LIMITER_LIMIT=1`: two requests →
  200 then 429 → `rate_limit_exceeded_total` > 0.

- **Redis connection leak in `UrlShortenerHealthIndicator`** — `checkRedis()` borrowed a
  connection via `getConnectionFactory().getConnection()` and never closed it: one leaked
  `RedisConnection` per `/actuator/health` probe, exhausting the pool under continuous
  probing. Now acquired in try-with-resources; behavior unchanged. Also adds the missing
  test coverage (`UrlShortenerHealthIndicatorTest`, 5 cases incl. the no-leak regression,
  and `HealthIndicatorDetailsIT` proving the indicator is wired into the actuator).

## [0.14.0] - 2026-09-13

### Added

- **Epic 8 (Deployable / Release Engineering)** — the product is now a first-class **released
  artifact**. Release identity is carried end-to-end (`<revision>` + flatten-maven-plugin at build,
  `org.opencontainers.image.version` label at runtime); `scripts/check-changelog.sh` gates a tag
  against a clean `[Unreleased]` block, and the annotated tag `v0.14.0` was pushed through a real
  **`release.yml`** pipeline: Gates (boundary/doc-sync/security/metrics-frozen gates, promtool/amtool,
  `./mvnw verify`) → k6 Gate (mixed load on a live instance) → Runtime Smoke (8-leg smoke +
  graceful-shutdown) → Restore Drill (RTO ≤ `RTO_BUDGET_S`) → Release (Docker image non-root gate,
  **Trivy HIGH/CRITICAL gate**, CycloneDX SBOM, GitHub Release with jar + `SHA256SUMS` + SBOM).
- **Blue-green bare-metal deploy (ADR 0007)** — `scripts/deploy.sh` stages the idle color, renders
  canary weights 10→30→100 with 30s dwell, aborts fail-closed to the old color naming the offending
  step; `scripts/rollback.sh` reverts from `deploy/runtime/last-deploy.txt`; systemd units
  `url-shortener-blue.service` / `url-shortener-green.service`. Both ship `--self-test` modes.
- **Artifact promotion contract (ADR 0008)** — the jar is downloaded **from the GitHub Release** and
  its SHA-256 is verified against `SHA256SUMS` before staging; no SSH deploy from CI (human gate on
  bare metal).
- **8-leg runtime smoke** — `scripts/smoke.sh` exercises liveness/readiness/health, shorten+redirect,
  auth, vanity alias, quota, and the redirect path against a running instance; used by deploy, rollback
  and CI `runtime-smoke`.
- **Scheduled, verified backups** — `scripts/backup-mongodb.sh` produces a manifest with per-collection
  counts, `scripts/restore-mongodb.sh --verify` compares counts after restore (negative test included),
  systemd `url-shortener-backup.{service,timer}` runs it daily, and `scripts/ci-restore-drill.sh`
  proves wall-clock RTO in CI.
- **Security hardening of the release base image** — the runtime stage now runs `apk upgrade --no-cache`
  so base-OS CVEs (openssl/libexpat) reported by the release Trivy gate are closed; the release image
  is verified to run as a **non-root** user (`uid != 0`).

### Changed

- **Versioning** — `pom.xml` now uses `<revision>` + `flatten-maven-plugin` (`.flattened-pom.xml`
  gitignored); the jar name carries the semantic version (e.g. `url-shortener-service-0.14.0.jar`).
- **Docker image** — multi-arch build support (`linux/amd64`, `linux/arm64`) and OCI labels
  (`org.opencontainers.image.*`); `VERSION` is injectable via `--build-arg`.

## [0.13.0] - 2026-09-11

### Added

- **Branded domains (Phase C)** — authenticated users can claim a custom domain, prove ownership via a
  DNS TXT record, and have it activated; shortening under a claimed domain is owner-only; the redirect
  path enforces a **strict Host mirror** (a domain-bound link resolves only under its own host).
  Migration `V8`; `DomainOwnershipHealthCheck` monitors TXT validity.
- **Rich click analytics (Phase C)** — click events capture `referrer`, `device` (mobile/desktop/tablet/bot)
  and `country` (GeoIP2, opt-in); a `click_daily` rollup (migration `V9`) pre-aggregates per
  `(shortCode, UTC day)`; `GET /api/v1/urls/{id}/clicks` returns a time series + breakdown (owner-guarded);
  **unique visitors** via Redis HyperLogLog. All enrichment is worker-side; the redirect path is untouched.
- **Epic 1 (Maintainable)** — Spotless (google-java-format) check gate at `validate`; ArchUnit boundary
  tests (`BoundaryRulesTest` + self-test); `check-doc-sync.sh` gate (debt matrix + lessons ↔
  coding-standards) in CI; core coverage floor raised to 70% line + branch.
- **Epic 2 (Secure by Design)** — CWE-117 sanitizer at every client-controlled log sink; SSRF gate blocks
  private/internal IP literals incl. IPv6 + IPv4-mapped-IPv6 + metadata IPs with a
  `security.ssrf.blocked.total` metric; HTTP security headers (`X-Content-Type-Options`, `X-Frame-Options`,
  `Referrer-Policy`); **OWASP Dependency-Check gate** (fail CVSS ≥ 7) + `check-security.sh` (+ self-test) in
  CI, NVD primed from the ODC nightly mirror (`nvdDatafeedUrl`) so cold syncs take ~2 min.
- **Epic 3 (Observability)** — correlation-id header + `request_id` MDC on every request; the 7 duplicate
  metrics folded into `MetricsPort` with a **metrics-freeze gate** (`check-metrics-frozen.sh`, 24 business
  series) in CI; anonymous `/actuator` index blocked (tiered access, `ProductionLockdownIT`); alert rules
  validated with `promtool` in CI; Prometheus registry restored after the Boot 4 upgrade.
- **Operator role over BasicAuth for actuator tiers (AGENTS debt 26)** — a dedicated operator identity
  (`app.security.operator.*`, env `OPERATOR_USERNAME`/`OPERATOR_PASSWORD`, constant-time compare) unlocks
  health-with-components, metrics, prometheus and circuit-breaker endpoints; env/beans/index stay ADMIN-only;
  fail-fast in prod when the operator is unset or its password is weak. `OperatorAccessIT` (9 tests).
- **Epic 5 (Performance)** — like-for-like k6 baseline resolved (same k6 v2.2.0 container + Redis 8.10.1:
  redirect p99 8.75 ms, shorten p95 11.97 ms — noise, not a regression); JFR profile under 2× load healthy
  (0.21% GC wall, 37× headroom, no mitigation warranted); L1 Caffeine + Bloom filter config externalized via
  `app.cache.*` properties; stress scenario at 2× nominal (ramping 400/40 rps): 165,498 requests, **0 failures**,
  p95 < 5 ms.
- **Epic 6 (Scalable)** — ADRs 0001–0004 (horizontal stateless scale, Redis-global rate limit, per-instance
  L1 with ≤5 s staleness, circuit breakers); `explain()` audit on real data: IDHACK/IXSCAN everywhere, **zero
  COLLSCAN**; multi-instance artifacts (weighted nginx upstream, systemd `url-shortener@.service` template,
  runbook §12); horizontal validation with 2 instances behind an LB: 165,499 requests, **0 5xx**, p95 7.28 ms,
  **global rate-limit proven** (300-request burst → exactly 120×302 + 180×429).
- **Epic 7 (Reliable)** — failure-mode contract: `docs/reliability.md` matrix 7.1 + **ADR 0005** (fail-open
  Redis/analytics) + **ADR 0006** (at-least-once, exactly-once rejected); inventory of CB/timeout/retry + ITs
  for Mongo/Redis down; shutdown script + liveness ≠ readiness; **real PEL redelivery** in `ClickBatchWorker`
  (crash-recovery drains the PEL before `lastConsumed()`; `ClickPipelineRedeliveryIT` proves reassign+reclaim
  and poison tolerance); **DR drill** with numbers (containerized dump/restore, 7,640 short_urls, RPO proven;
  Redis-down mid-run = 5,730/5,730 checks, p95 744 ms fail-open; Mongo-down cold-cache = CB fast-fail with
  auto-recovery); dependency-outage playbooks in `docs/release-runbook.md` §5b.
- **DD/QA hardening** — `HEAD /{id}` now mirrors `GET` (was 401) with `ReadPathIT` coverage; promtool analysis
  fixed invalid annotation names (`runbook-§X` → `runbook_<phase>`) in `alerts.yml`; `scripts/check-security.sh`
  is self-executable.

### Changed

- **Platform upgrade: Java 21 + Spring Boot 3.5.7 + Undertow → Java 25 + Spring Boot 4.1.1 + Tomcat.**
  Boot 4 removed Undertow support (the app now runs on **Tomcat 11** with virtual threads, Jakarta EE 11) and
  renamed `spring.data.mongodb.*` → `spring.mongodb.*`. Stack updates: Jackson 3 (`tools.jackson`),
  `spring-boot-starter-aspectj`, `spring-boot-starter-webmvc-test`, Spring Security 7 (DAO authentication
  provider constructor), Redis:4, Testcontainers **2.0.5** (explicit `withReplicaSet()`), Redisson 4.7.0,
  jjwt 0.12.7, springdoc 3.1.1, spotbugs 4.10.4.1, lombok 1.18.46, logstash-encoder 9.0, REST Assured 6.0.1.
- **Maven wrapper 3.9.16** — the repo bundles `./mvnw` (was: local `mvn` only); updated README commands, CI
  (Java 25, temurin), Dockerfile (temurin-25), systemd unit and scripts.
- **Metrics folded into the port** — `MetricsService` removed; its 7 meters now live in
  `MicrometerMetricsAdapter` behind `MetricsPort` (`recordRedirect`, `recordShortenLatency`,
  `recordRedirectLatency`); Prometheus series byte-for-byte identical; a freeze gate pins the 24 business series.
- **Cache config externalized** — L1 Caffeine size/TTL and Bloom filter bounds are now
  `app.cache.*` properties (historical defaults preserved).
- **Core coverage floor** raised from 60% to **70%** line + branch (Epic 1).

### Fixed

- **IT suite: mongod dying mid-suite (exit 14, "Prematurely reached end of stream")** — root cause:
  `@DirtiesContext(AFTER_EACH_TEST_METHOD)` re-created the whole ApplicationContext per test method (114
  contexts for 114 tests), each opening its own Mongo connection pool; the shared mongod hit the EMFILE
  ("Too many open files") limit during a schema-migration index build. Fix (singleton-container pattern,
  `docs/lessons.md` #7): drop `@DirtiesContext` (isolation via `@BeforeEach/@AfterEach` cleanup + L1 Caffeine
  `invalidateAll`), cap the test mongod `wiredTigerCacheSizeGB=0.25`, raise the container `nofile` to 65536.
  Suite now deterministic: **271 unit + 165 IT**, 0 errors.
- **TTL validation moved to the application layer** — the controller no longer resolves `ttlSeconds`;
  `ShortenUrlUseCase` gains a `Long ttlSeconds` overload and `UrlShortenerService` resolves it via
  `ExpiryResolver` with the cap as a primitive (fixes a latent `core → infra` boundary violation and converts
  a missing-cap NPE into `InvalidExpiryException`).
- **`/actuator` index reachable anonymously** via the `GET /{id}` catch-all — actuator matcher moved ahead of
  the redirect path; `ProductionLockdownIT` (7 tests) locks the tiers down.
- **`POST /api/v1/urls` matching the `/api/v1/urls/**` matcher** — public endpoints re-ordered ahead of the
  authenticated ones in `SecurityConfig` (regression from the tier rework; anonymous shorten → 401 is gone).
- **Prometheus registry absent after the Boot 4 upgrade** — `micrometer-registry-prometheus` restored
  (managed by the BOM); `/actuator/prometheus` works again and is asserted by `MetricsIT` playback.

## [0.12.0] - 2026-08-28

### Added

- **Links as Resource (Phase B)** — authenticated, owner-scoped link management under `/api/v1/urls`:
  cursor-paginated **list** of the caller's links (archived included via `deletedAt`; Base64url
  cursor `<epochMillis>:<id>`, `createdAt DESC` + `_id` DESC, `limit` capped at 100, malformed → 400),
  **get** a link's details (owner only, 403 otherwise), **PATCH** partial update applying **only
  supplied fields** (`@JsonAnySetter` presence capture on `UpdateLinkRequest`; `expiresAt`/`utm`
  present-and-`null` clears via `*Supplied` flags on `UpdateLinkCommand`; archived links immutable),
  and **DELETE = soft delete** (`deletedAt`, idempotent). Mutations evict Redis/L1 cache entries
  (`UrlCachePort.evict`); the `GET /{id}` redirect returns `404` for archived codes. Unauthenticated →
  `401` (explicit authentication entry point), non-owner → 403 at the application layer. Ports split
  by ISP: `LinkQueryPort` + `LinkMutationPort` (shortening/redirect stay on `UrlRepositoryPort`).
  Tests: `LinkResourceIT` (25), `LinkUseCasesTest` (12), extended `MongoUrlRepositoryIT` (13).

### Changed

- **Security** — unauthenticated requests to protected endpoints now return `401` (explicit
  `authenticationEntryPoint`) instead of the framework default `403`.

## [0.11.0] - 2026-08-27

### Added

- **Read Path Confidence (Phase A)** — the redirect read path now distinguishes cache hit, cache miss
  and Bloom-negative via the `CacheLookup` domain type returned by `UrlCachePort.lookup()` (Policy B:
  a bloom-negative is a lightweight cache-miss resolved by `findById`). `ReadPathIT` (5 tests) proves
  the behaviour; corrected the "Bloom avoids the DB" claim in docs.

## [0.10.0] - 2026-08-27

### Changed

- **Final polish & data integrity** — closed `AGENTS.md` debt item 10 (schema migrations fully
  versioned/checksummed, `users.email` unique index live); the `410 Gone` OpenAPI for the redirect is
  accurate; TTL/link-expiry logic lives in the application layer; stale "target/not yet applied"
  phrasing cleaned from docs.

## [0.9.0] - 2026-08-27

### Added

- **Operational excellence** — OpenTelemetry tracing (fail-open, tracked by `TracingFailOpenIT`),
  structured JSON logging (`json` profile), SLOs + burn-rate alerts, k6 load baseline with real
  numbers, TLS termination via reverse proxy (NGINX/Caddy), systemd deployment unit + graceful
  shutdown, MongoDB backup/restore scripts, `click_events` retention purge.

## [0.8.0] - 2026-08-27

### Added

- **Link expiry (TTL)** — optional `ttlSeconds` on `POST /api/v1/urls` (`@Positive`, server-capped by
  `app.shortener.max-ttl-seconds`, default 1 year; `null` = never expires) mapped to an `expiresAt`
  `Instant` on the short URL. Redirect path checks expiry eagerly: **`410 Gone`** for expired, `404`
  for unknown, `302` for valid. A MongoDB TTL index on `expiresAt` (migration `V5`) purges expired rows.
- **Expiry-aware cache** — `CachedUrlValue(originalUrl, expiresAt)`; Redis TTL capped at the remaining
  time for expiring links (evicted at/before expiry, never served past it); service re-checks expiry on
  cache hits as defense-in-depth.
- **Versioned in-code schema migrations** — `MongoSchemaMigrator` + `SchemaMigration` (checksummed,
  idempotent, fail-fast, history in `schema_migrations`); migrations `V1`–`V5` (baseline, drop
  `originalUrl` unique, `userId`, `click_events` indexes, `expiresAt` TTL). `IndexMigration` retired.
- **Expiry/migration metrics** — `urls.expired.total`, `schema.migrations.applied.total`,
  `schema.migrations.failed.total`.

### Changed

- **Schema management** — Flyway-for-MongoDB was attempted and rejected (JDBC driver not on Maven
  Central; native connectors are CLI-only); the spec's default in-code runner was adopted after human
  approval. No `pom.xml` changes.

## [0.7.0] - 2026-08-27

### Added

- **Timer metrics** — `id.generation.duration` and `url.retrieval.duration` with p50/p95/p99 publishing, recorded behind `MetricsPort` (`recordIdGeneration`, `recordUrlRetrieval`) for the shorten ID-generation and the redirect cache+DB lookup paths.
- **OpenTelemetry tracing** — `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` + `opentelemetry-sdk-extension-autoconfigure`; OTLP/HTTP exporter (`management.otlp.tracing.endpoint`, default `localhost:4318`); 10% head sampling. HTTP spans auto-instrumented by Spring Boot; **no tracing code in `core/`** (boundary preserved).
- **Log correlation** — `traceId`/`spanId` in console and file log patterns via logback MDC.
- **Tracing fail-open** — proven by `TracingFailOpenIT` (collector unreachable → requests still succeed).
- **SLOs** — `docs/slos.md` (availability 99.9%, latency p99 < 200 ms, error rate < 0.1%, 30d window), Prometheus recording rules (`deploy/monitoring/recording-rules.yml`) and burn-rate alerts (`deploy/monitoring/alerts.yml`: fast 14.4x → critical, slow 6x → warning).
- **OTel collector config** — `deploy/otel/otel-collector-config.yml` with tail sampling that always keeps ERROR traces.
- **Grafana dashboards** — `dashboards/url-shortener-overview.json`, `dashboards/url-shortener-tracing.json`, `dashboards/url-shortener-slo.json`.
- **k6 load tests** — `load-tests/shorten.js`, `load-tests/redirect.js`, `load-tests/mixed.js` with SLO thresholds (p95 < 200 ms, error rate < 0.1%); manual-dispatch workflow `.github/workflows/load-test.yml`; baseline template `docs/load-test-baseline.md`.

### Fixed

- **AGENTS.md debt item 12 resolved** — observability gaps closed: timers recorded, tracing, SLOs and k6 load harness added.
- **Compilation blocker** — `UrlShortenerService.shorten` had been left with an unbalanced closing brace by a previous refactor; restored the committed structure while wiring the new timers.

### Added

- **Actuator tiered access** — liveness/readiness/info endpoints public; health detail requires ADMIN; metrics/prometheus require ADMIN or METRICS_VIEWER; other actuator endpoints require ADMIN.
- **Conditional Swagger** — Swagger UI/OpenAPI enabled only when `app.security.swagger.enabled=true` (default false); disabled by default in production.
- **Health detail configurable** — `management.endpoint.health.show-details` defaults to `when-authorized`; `always` available for development.
- **SecurityProperties** — typed configuration for actuator/swagger security (`app.security.*`).
- **OpenApiConfig** — conditionally loaded via `@ConditionalOnProperty(name="app.security.swagger.enabled")`.

### Changed

- **Actuator endpoints** — moved from single `permitAll()` to tiered access: liveness/readiness/info public; health detail ADMIN only; metrics/prometheus ADMIN or METRICS_VIEWER; other actuator endpoints ADMIN only.
- **Swagger** — now conditionally loaded via `@ConditionalOnProperty(name="app.security.swagger.enabled")`; disabled by default.
- **SecurityProperties** — new typed configuration record (`app.security.actuator.*`, `app.security.swagger.*`, `app.security.trusted-proxy-cidrs`).

### Fixed

- **AGENTS.md debt item 9 resolved** — Actuator & Swagger no longer publicly exposed; tiered access implemented.
- **Swagger exposure** — now disabled by default; enabled via `app.security.swagger.enabled=true`.

## [0.5.0] - 2026-08-26

### Added

- **SSRF protection** — `UrlValidator` with HTTPS enforcement (configurable), host syntax
  validation, DNS resolution with caching, private/internal/metadata IP blocking (RFC1918,
  loopback, link-local, cloud metadata IPs), userinfo rejection, and extensibility hook via
  `DestinationValidatorPort` for reputation checks.
- **InvalidDestinationException** — domain exception for SSRF/format violations (HTTP 400).
- **`UrlValidator` port** — `UrlValidator.ValidationResult` verdict with allowed/blocked,
  remaining tokens, reset seconds.

### Changed

- **URL validation** — moved from `Url` record to `DefaultUrlValidator` (infra adapter) with
  configurable policies (`app.url.allow-http`, `app.url.block-private-ips`, `dns-timeout-ms`,
  `dns-cache-ttl-seconds`, trusted-proxy CIDRs).

### Tests

- Added `DefaultUrlValidatorTest` (11 unit tests) covering HTTPS enforcement, scheme validation,
  userinfo rejection, host format validation, private IP blocking.
- Added `SsrfProtectionIT` (7 integration tests) proving: HTTP rejection, userinfo rejection,
  invalid scheme/host/scheme rejection, valid HTTPS acceptance.

### Fixed

- **AGENTS.md debt item 8 resolved** — SSRF protection implemented.

## [0.4.0] - 2026-08-26

### Added

- **Redirect rate limiting** — `GET /{id}` now has per-IP token-bucket over Redis (Rule 5):
  independent REDIRECT scope with configurable capacity/window, Redis TIME-driven atomic Lua script,
  trusted-proxy CIDR IP resolution, fail-open policy. 429 responses include `Retry-After`,
  `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` headers. Scope isolation: exhausting
  redirect budget never affects shorten.
- **Trusted-proxy CIDR IP resolution** — `X-Forwarded-For` (left-most) is trusted only when the
  direct peer matches configured CIDRs (default `127.0.0.0/8`, `::1/128`); untrusted peers fall back
  to `getRemoteAddr()`.

### Changed

- **Rate limiter core** — upgraded from fixed-window counter to Redis TIME-driven token bucket
  (Lua script) with continuous refill and TTL-based key reclamation. Now scoped (SHORTEN/REDIRECT)
  with independent budgets.

### Tests

- Added `RedirectRateLimitIT`: capacity enforcement with `Retry-After` + `RateLimit-*` headers,
  anti-enumeration (unknown-code probes throttled), scope isolation (shorten untouched),
  concurrent burst admits exactly configured capacity.

## [0.3.0] - 2026-08-26

### Added

- **Real click analytics** — redirects enqueue events onto a durable, bounded **Redis Stream**
  (`RedisClickEventQueue`, `XADD MAXLEN ~`, fire-and-forget + fail-open); `ClickBatchWorker`
  consumes via a self-healing consumer group, bulk-inserts to the new **`click_events`** collection
  (UTC instants, provenance `consumedAt`) and increments `clickCount` with one atomic `$inc` per
  unique code per batch. Delivery is at-least-once without an idempotency key (locked decision).
- **Pipeline metrics** — `analytics.events.{enqueued,persisted,failed,dropped}.total` (fixed
  low-cardinality tags).
- **`click_events` indexes** — `(shortCode, timestamp)` and `(timestamp)`, idempotent via
  `IndexMigration`.

### Changed

- **Atomic counters everywhere** — quota increments (`QuotaService`) now use `$inc` on both monthly
  and total counters instead of read-modify-write (`AGENTS.md` debt item 14).

### Fixed

- **Analytics no longer drops data** — in-memory `LinkedBlockingQueue` (drop-on-full, log-only
  worker) fully replaced; queue durability survives restarts and bursts (`AGENTS.md` debt items
  5/15).

### Tests

- Unit suite grew to 134 tests; new ITs: `ClickPipelineIT` (persist+count, exact counts under
  burst, blank-code skip) and fail-open proof under Redis outage.

## [0.2.0] - 2026-08-25

### Added

- **Coverage gate** — `jacoco-maven-plugin` 0.8.15 wired at `verify`: LINE ≥ 60%, BRANCH ≥ 60%
  (both green; unit suite expanded from 87 to 125 tests to clear the floor).
- **Static analysis gate** — `spotbugs-maven-plugin` 4.9.8.5 wired at `verify` (effort Max,
  threshold High); zero findings.
- **Boundary gate self-test** — `scripts/check-boundaries.sh --self-test` plants a violation in a
  temp dir and asserts the gate catches it, guarding against silent gate breakage; CI runs both
  modes.

### Changed

- **Framework-free `core/`** — Spring/Lombok annotations removed from
  `CompositeUrlIdGenerator`, `RandomUrlIdStrategy`, `VanityUrlIdStrategy`, `QuotaService`,
  `ReservedWordsValidator`; explicit constructors instead. Beans registered in
  `infra/config/ServiceConfig`. Lombok remains in use in `infra/` only.
- **Testcontainers 1.19.3 → 1.21.3** — required for Docker Engine ≥ 29 (API `1.44+`) compatibility;
  IT/E2E suites run again on current engines.

### Fixed

- **CI boundary check** — updated grep paths from `com.example.urlshortener` to `ca.tyny.urlshortener`.
- **CI integration gate** — replaced `mvn test -Dtest='*IT'` with `mvn verify` so failsafe actually
  runs integration tests.
- **Collision vs alias distinction** — `MongoUrlRepository.save()` now throws `ShortCodeCollisionException`
  for auto-generated code collisions and `AliasAlreadyExistsException` for vanity alias conflicts.
  `UrlShortenerService.saveWithCollisionRetry()` catches only `ShortCodeCollisionException`, not
  `RuntimeException`.
- **`IndexMigration` completeness** — now ensures `userId` index exists on startup (alongside dropping
  `originalUrl_1`).
- **`CodeGenerationException` handler** — added dedicated `@ExceptionHandler` in `GlobalExceptionHandler`
  (returns 500 instead of falling through to generic handler).
- **English-only cleanup** — translated Portuguese comments/logs in `MongoUrlRepository`,
  `ShortUrlEntity`, `MongoCollections`, `UrlIdGenerationStrategy`, `RandomUrlIdStrategy`.
- **`JwtTokenProvider` default encoding** — signing key bytes now use explicit `StandardCharsets.UTF_8`
  (SpotBugs `DM_DEFAULT_ENCODING`); token output no longer depends on the JVM platform charset.

### Changed

- **UserService DIP leak resolved** — `UserService` now depends on `TokenPort`, `PasswordEncoderPort`,
  `AuthenticationPort` (all in `core/ports/outgoing`); REST DTO mapping moved to `AuthController`.
  Infrastructure adapters (`JwtTokenAdapter`, `PasswordEncoderAdapter`, `AuthenticationAdapter`) implement
  the new ports. Zero `infra` imports remain in `core/`.
- **Boundary check passes** — `core/` no longer imports `ca.tyny.urlshortener.infra.*`.

## [0.1.0] - 2026-08-25

### Added

- **Base62 code generator** — cryptographically random codes via `SecureRandom`, configurable
  length (`app.shortener.code-length`, default 7), bounded collision retry on `_id`
  `DuplicateKeyException` (`Base62CodeGenerator` + `saveWithCollisionRetry()` in
  `UrlShortenerService`).
- **SHA-256 `urlHash`** on `ShortUrlEntity` for future analytics (non-unique, computed on save).
- **IndexMigration** — idempotent, versioned index management replacing `auto-index-creation`
  (`auto-index-creation: false` in `application.yaml`).
- **CI workflow** — `.github/workflows/ci.yml` running `mvn test`, `*IT` (Docker), and
  `mvn clean package`.
- **Architecture boundary check** — `scripts/check-boundaries.sh` enforcing `core/` must not
  import `infra/`, Spring, MongoDB, Redisson, JWT or Micrometer types.

### Changed

- **Identity model locked** — short codes are random Base62 (not Hashids, not Redis counter).
  Same URL may be shortened multiple times (no dedup). Generated codes and vanity aliases occupy
  disjoint namespaces. `409 Conflict` means only "custom alias already exists".
- **Removed unique index on `originalUrl`** — duplicate URLs now create distinct codes.
- **`auto-index-creation: false`** — schema indexes managed by committed `IndexMigration`.
- **Package renamed** from `com.example` to `ca.tyny`.
- **GraalVM native `mainClass` corrected** to `ca.tyny.urlshortener.Application`.
- **Dead metrics removed** — `id.generation.duration` and `url.retrieval.duration` timers removed
  from `MetricsService`.

### Removed

- `org.hashids` dependency from `pom.xml`.
- `RangeAwareIdGenerator` class and its test.
- `SHORTENER_SALT` / `app.shortener.salt` config property.
- `RedisIdGenerationIT` test.

### Documentation

- Synced all product docs to the locked identity model (AGENTS.md debt items 3, 4, 7, 11, 13
  resolved).
- Updated `README.md`, `AGENTS.md`, `docs/data-model-decisions.md`, `docs/coding-standards.md`,
  `docs/testing-playbook.md`, `docs/lessons.md`, `docs/twelve-factor.md`.
- `tasks/foundation-identity-model/foundation-identity-model-backlog.md` status updated to completed.
