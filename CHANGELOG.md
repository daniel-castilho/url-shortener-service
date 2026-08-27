# Changelog

All notable changes to URL Shortener Service will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project
intends to follow [Semantic Versioning](https://semver.org/) starting from its first tag.

## [Unreleased]

## [0.6.0] - 2026-08-26

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
- `tasks/foundation-identity-model-backlog.md` status updated to completed.
