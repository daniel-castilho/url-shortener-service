# Changelog

All notable changes to URL Shortener Service will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project
intends to follow [Semantic Versioning](https://semver.org/) starting from its first tag.

## [Unreleased]

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
