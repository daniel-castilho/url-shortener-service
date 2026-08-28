# URL Shortener Service

![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.7-6DB33F?style=for-the-badge&logo=spring&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.8+-C71A36?style=for-the-badge&logo=apache-maven&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)

URL Shortener Service is a high-performance link-shortening API built with **Java 21**, **Spring Boot 3.5.7**
and a **Hexagonal Architecture (Ports & Adapters)**. Its business core (`core` package) is free of
framework and adapter dependencies — the shortening logic talks only to abstractions (ports), which
keeps the application testable, swappable and independent of the persistence, cache and web
technologies used by the `infra` layer.

## Table of Contents

- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [Getting Started](#getting-started)
- [Commands](#commands)
- [Testing](#testing)
- [API & Documentation](#api--documentation)
- [Current State](#current-state)
- [Roadmap](#roadmap)
- [Documentation](#documentation)

## Tech Stack

| Category | Technology |
| :--- | :--- |
| **Language & Framework** | ![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.7-6DB33F?style=for-the-badge&logo=spring&logoColor=white) |
| **Web Server** | ![Undertow](https://img.shields.io/badge/Undertow-1F77B4?style=for-the-badge) ![Virtual Threads](https://img.shields.io/badge/Virtual_Threads_(Loom)-00C4CC?style=for-the-badge) |
| **Build & Dependencies** | ![Maven](https://img.shields.io/badge/Maven-3.8+-C71A36?style=for-the-badge&logo=apache-maven&logoColor=white) |
| **Database** | ![MongoDB](https://img.shields.io/badge/MongoDB_6.0-47A248?style=for-the-badge&logo=mongodb&logoColor=white) |
| **Cache** | ![Redis](https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white) ![Redisson](https://img.shields.io/badge/Redisson-4A90E2?style=for-the-badge) ![Caffeine](https://img.shields.io/badge/Caffeine-DA5B0B?style=for-the-badge) |
| **Security** | ![Spring Security](https://img.shields.io/badge/Spring_Security-6DB33F?style=for-the-badge&logo=spring-security&logoColor=white) ![JWT](https://img.shields.io/badge/JWT-000000?style=for-the-badge&logo=json-web-tokens&logoColor=white) |
| **Resilience** | ![Resilience4j](https://img.shields.io/badge/Resilience4j-008282?style=for-the-badge) |
| **Observability** | ![Micrometer](https://img.shields.io/badge/Micrometer-0067B8?style=for-the-badge) ![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=prometheus&logoColor=white) |
| **API Docs** | ![Swagger](https://img.shields.io/badge/Swagger-85EA2D?style=for-the-badge&logo=swagger&logoColor=black) |
| **Testing** | ![JUnit 5](https://img.shields.io/badge/JUnit5-25A162?style=for-the-badge&logo=junit5&logoColor=white) ![Mockito](https://img.shields.io/badge/Mockito-D43A2A?style=for-the-badge&logo=mockito&logoColor=white) ![Testcontainers](https://img.shields.io/badge/Testcontainers-262261?style=for-the-badge&logo=testcontainers&logoColor=white) ![RestAssured](https://img.shields.io/badge/REST_Assured-000000?style=for-the-badge&logo=rest-assured&logoColor=white) |

- **Web:** Spring Web + **Undertow** (non-blocking I/O, direct buffers) with **Virtual Threads** enabled.
- **Data:** Spring Data MongoDB (`auto-index-creation: false`; schema managed by versioned in-code
  migrations via `MongoSchemaMigrator`) and Spring Data Redis.
- **Cache:** **Caffeine** local (L1, 100 items / 5s TTL) → **Redis** (L2, 24h TTL + jitter) →
  MongoDB. A **Redisson Bloom Filter** short-circuits the Redis `get` for codes that certainly do not
  exist; a bloom-negative is treated as a lightweight cache-miss and resolved by `findById` (Policy B).
  The Bloom filter short-circuits **only the Redis `get`**, not the MongoDB lookup.
- **ID generation (locked identity model):** cryptographically random **Base62** codes
  (`SecureRandom`, alphabet `0-9A-Za-z`, default length **7** via `app.shortener.code-length`).
  Collisions retry on the unique `_id`. **No Hashids, no Redis counter, no sequential codes.**
  The same original URL may be shortened many times (no unique index on `originalUrl`).
  `409 Conflict` means only “custom alias already exists”. See `docs/data-model-decisions.md`.
- **Resilience:** Resilience4j circuit breakers for the rate limiter / ID generator and the database.
- **Security:** Spring Security + **jjwt** 0.12 (HS256, access + refresh tokens), **BCrypt** hashing.
- **API docs:** springdoc-openapi (Swagger UI) + **Actuator** (`health`, `metrics`, `prometheus`,
  `circuitbreakers`).

## Architecture

The application follows a hexagonal (Ports & Adapters) layout with a strict inward dependency rule:

```
src/main/java/com/example/urlshortener/
├── Application.java                      # Spring Boot entry point
├── core/                                 # 🧠 DOMAIN — pure business logic
│   ├── exception/                        # Domain exceptions (UrlNotFound, AliasExists, QuotaExceeded)
│   ├── idgeneration/                     # UrlIdGenerator + strategies (random / vanity) — composite
│   ├── model/                            # Entities & value objects (ShortUrl, Url, User, ClickEvent…)
│   ├── ports/
│   │   ├── incoming/                     # Use-case contracts (ShortenUrlUseCase, GetUrlUseCase)
│   │   └── outgoing/                     # Outbound ports (Repository, Cache, Metrics, Analytics, RateLimiter…)
│   ├── service/                          # Use-case orchestration (UrlShortenerService, QuotaService)
│   └── validation/                       # ReservedWordsValidator
└── infra/                                # ⚙️ ADAPTERS — framework & cloud
    ├── adapter/
    │   ├── input/rest/                   # REST controllers, DTOs, GlobalExceptionHandler
    │   └── output/
    │       ├── analytics/                # Async click-event queue + batched worker
    │       ├── persistence/              # Mongo repositories, entities & mappers
    │       └── redis/                    # Redis cache, bloom filter, rate limiter
    ├── config/                           # Spring beans, security, Undertow, OpenAPI, native hints
    ├── observability/                    # Micrometer metrics service & adapter
    └── security/                         # JWT filter, token provider, UserDetailsService
```

**Boundary rules:**

- `core/` must never import `ca.tyny.urlshortener.infra.*` nor any framework adapter. It depends
  only on its own models, ports and services.
- `core/` services depend on **outbound ports** (interfaces) — not on concrete implementations — so
  Mongo, Redis and the cache are swappable behind those ports.
- `infra/` implements the outbound ports and exposes them through `config` beans.

> **Known exception (tracked in the roadmap):** `core/service/UserService` currently imports
> `infra` classes directly (`MongoUserRepository`, `JwtTokenProvider`, REST DTOs) and `core/` mixes
> in a few Spring annotations. The intended boundary is enforced for the URL-shortening flow; the
> user-service layer is the remaining piece to refactor.

## Requirements

- JDK 21
- Maven 3.8+ (this project does **not** bundle a `./mvnw` wrapper)
- Docker and Docker Compose

## Getting Started

### 1. Start the external services

With Docker running, start MongoDB and Redis via Docker Compose:

```sh
docker-compose up -d
```

This starts `mongo:6.0` and `redis:alpine`. MongoDB indexes are managed on startup by the
application's versioned schema migrations (`MongoSchemaMigrator`, versions `V1`–`V7`, recorded in the
`schema_migrations` history; `auto-index-creation` is off).

### 2. Run the application

```sh
mvn spring-boot:run
```

Also available as a container (multi-stage `Dockerfile`, non-root user, healthcheck):

```sh
docker build -t url-shortener-service .
docker run --network url-shortener-url-shortener-net -p 8080:8080 url-shortener-service
```

The application is available at `http://localhost:8080` (Swagger UI, see below).

> The default config in `src/main/resources/application.yaml` points MongoDB and Redis at
> `localhost` and ships a **dev-only** `APP_JWT_SECRET`. Override it via environment variables in
> production — the JWT provider validates the secret at startup and warns when the bundled default
> is used. Short codes do **not** use a salt.

## Commands

| Purpose | Command |
| :--- | :--- |
| Run the dev server | `mvn spring-boot:run` |
| Run unit tests (no Docker needed) | `mvn test` |
| Run the integration suite (needs Docker + Testcontainers) | `mvn test -Dtest='*IT'` |
| Full gate (unit + IT + jar) | `mvn verify` |
| Build the jar | `mvn clean package` |
| Build the GraalVM native binary | `mvn clean package -Pnative` |
| Start external services (Mongo + Redis) | `docker-compose up -d` |
| Stop external services | `docker-compose down` |

## Testing

- **Unit tests** — exercise the `core` layer (models, ID generation, services, quota) with JUnit 5 +
  Mockito, no Spring context, no I/O. Run with `mvn test`.
- **Integration tests** — use **Testcontainers** to boot real MongoDB and Redis in Docker and validate
  persistence, cache, rate limiting and the redirect path. Target naming is `*IT` (run with
  `mvn test -Dtest='*IT'` or `mvn verify`).

## API & Documentation

### Interactive documentation (Swagger UI)

With the application running, open the API docs:

- **URL:** [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

### Endpoint summary

| Domain | Method | Endpoint | Description |
| :--- | :--- | :--- | :--- |
| **Auth** | `POST` | `/api/v1/auth/register` | Register a new user (name, e-mail, password ≥ 6 chars) and return an access + refresh token. Fails `400` if the e-mail is already in use. |
| | `POST` | `/api/v1/auth/login` | Authenticate and return an access + refresh token. |
| | `POST` | `/api/v1/auth/refresh` | Exchange a valid refresh token for a new access token. |
| **URLs** | `POST` | `/api/v1/urls` | Shorten a URL. Anonymous allowed; `customAlias` (vanity) requires authentication; optional `ttlSeconds` (bounded by `app.shortener.max-ttl-seconds`, default 1 year, `null` = never expires). `429` on rate limit. |
| | `GET` | `/api/v1/urls` | List the **caller's** links (archived included), newest first, cursor-paginated (`?limit=&cursor=`; `limit` capped at 100, malformed cursor → `400`). Requires authentication. |
| | `GET` | `/api/v1/urls/{id}` | Get one link's details (owner only; `403` for non-owner, `404` unknown). |
| | `PATCH` | `/api/v1/urls/{id}` | Partially update a link — only **supplied** fields change; `expiresAt`/`utm` present-and-`null` clears. Owner only. Archived links are immutable (`400`). |
| | `DELETE` | `/api/v1/urls/{id}` | **Soft-delete** (archives) a link; idempotent; owner only. The redirect then returns `404`. |
| **Redirect** | `GET` | `/{id}` | Resolve a short code and redirect (`302`) to the original URL. `404` if unknown or archived, `410 Gone` if expired, and never blocks on analytics. |

### Monitoring endpoints (Actuator)

- **Health:** `GET /actuator/health` (status + details)
- **Metrics:** `GET /actuator/metrics` and `GET /actuator/prometheus` (Micrometer / Prometheus)
- **Circuit breakers:** `GET /actuator/circuitbreakers` (Resilience4j state)

Custom business metrics exposed via Micrometer include `urls.shortened.total`, `redirects.total`,
`urls.expired.total`, `schema.migrations.applied.total`, `schema.migrations.failed.total`,
`shorten.latency` (p50/p95/p99), `redirect.latency`, `cache.hits.total` / `cache.misses.total`,
`bloomfilter.rejections.total`, `id.generation.duration` (p50/p95/p99) and
`url.retrieval.duration` (p50/p95/p99).

## Current State

Implemented on `main`:

- **URL shortening & redirection** — `POST /api/v1/urls` creates a short code; `GET /{id}` performs a
  `302` redirect. URL input is validated against a value object (requires `http://`/`https://`).
  The same long URL may be shortened repeatedly; each call yields a **distinct** code.
- **ID generation (locked model)** — random **Base62** (`SecureRandom`), default length 7, bounded
  retry on `_id` collision. Generated codes and vanity aliases are namespace-isolated (length /
  alphabet / reserved words). `409` is **only** “custom alias already exists”.
  No URL dedup: the same long URL may be shortened repeatedly, each call yielding a **distinct**
  code.
- **Link expiry (TTL) — landed.** Optional `ttlSeconds` on `POST /api/v1/urls` (positive, server-capped
  via `app.shortener.max-ttl-seconds`, `null` = never expires) becomes an `expiresAt` `Instant` on the
  short URL. The redirect path checks expiry **eagerly** (application logic is the source of truth): an
  expired link returns **`410 Gone`**, an unknown code `404`, a valid code `302`. Expired links are
  never served from cache: the cache value carries `expiresAt` and the Redis TTL is capped at the
  remaining time. A **MongoDB TTL index** on `expiresAt` (migration `V5`) purges expired rows on the
  database side (~60 s cadence). Schema is now managed by a **versioned in-code migration runner**
  (`MongoSchemaMigrator`, `schema_migrations` history, checksummed, fail-fast) — `IndexMigration` is
  retired.
- **Custom aliases (vanity URLs)** — authenticated users can create custom slugs; blocked reserved
  words, plan-minimum alias length, and quota enforcement per subscription plan (`FREE`/`SILVER`/
  `GOLD`/`DIAMOND`).
- **Multi-level caching** — Caffeine L1 → Redis L2 with **TTL jitter** (anti-stampede) and a Redisson
  **Bloom filter** to short-circuit lookups of non-existent codes.
- **Async click tracking** — redirects enqueue click events fire-and-forget onto a **durable Redis
  Stream** (bounded, survives restarts); a self-healing worker persists them to a `click_events`
  collection and increments the per-link `clickCount` atomically (`$inc`). Analytics failure never
  blocks or fails a redirect (fail-open).
- **Rate limiting** — per-IP token-bucket over Redis on **both shorten and redirect endpoints**, independent scopes (SHORTEN/REDIRECT), configurable via `rate-limiter.limit` / `rate-limiter.window` and `rate-limiter.redirect-limit` / `rate-limiter.redirect-window`. Trusted-proxy CIDR IP resolution; fails open (does not block) if Redis is unavailable. 429 responses include `Retry-After`, `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` headers.
- **Security hardening** — Actuator endpoints tiered: liveness/readiness/info public; health detail requires ADMIN; metrics/prometheus require ADMIN or METRICS_VIEWER; other actuator endpoints require ADMIN. Swagger enabled only when `app.security.swagger.enabled=true` (default false). Health detail defaults to `when-authorized`. Swagger conditionally loaded via `@ConditionalOnProperty`.
- **Fault tolerance** — Resilience4j circuit breakers (`databaseCb` fail-fast for Mongo,
  `rateLimiterCb` fail-open for the rate limiter / ID generator), exposed via Actuator.
- **Auth & users** — stateless JWT (HS256, access + refresh), BCrypt password hashing, `FREE` plan by
  default.
- **Observability (four pillars)** — Micrometer metrics + Prometheus endpoint with latency
  percentiles and the `id.generation.duration` / `url.retrieval.duration` timers; OpenTelemetry
  tracing via Spring Boot auto-instrumentation (`micrometer-tracing-bridge-otel`) exported through
  OTLP/HTTP with 10% head sampling and collector **tail-sampling that always keeps ERROR traces**
  (`deploy/otel/otel-collector-config.yml`); `traceId`/`spanId` MDC in logs; **SLOs** — availability
  99.9%, latency p99 < 200 ms, error rate < 0.1% (`docs/slos.md`, Prometheus recording rules +
  burn-rate alerts under `deploy/monitoring/`); Grafana dashboards (`dashboards/`); k6 load tests
  (`load-tests/`, manual dispatch via `.github/workflows/load-test.yml`) and baselines
  (`docs/load-test-baseline.md`). Tracing is fail-open, proven by `TracingFailOpenIT`. See
  `docs/observability.md`.
- **Links as Resource — landed.** Authenticated, owner-scoped link management under `/api/v1/urls`:
  cursor-paginated **list** of the caller's links (archived included, `deletedAt` exposed; order
  `createdAt DESC, _id DESC`; opaque Base64url cursor; `limit` capped at 100; malformed cursor → 400),
  **get** details (owner only, 403 otherwise), **PATCH** partial update applying **only supplied
  fields** (`@JsonAnySetter` presence capture; `expiresAt`/`utm` present-and-`null` clears; archived
  links immutable) and **DELETE = soft delete** (`deletedAt`, idempotent; the `GET /{id}` redirect
  returns `404` for archived codes). Mutations **evict** the Redis/L1 cache entries so no stale
  destination is ever served. Unauthenticated → `401`; non-owner → `403` (application-layer, both
  tested). See `docs/data-model-decisions.md` → *Links as Resource*.
- **API docs** — springdoc OpenAPI / Swagger UI, bean validation and structured error responses via a
  global exception handler.
- **Quality gates** — `mvn verify` enforces JaCoCo coverage (LINE ≥ 60%, BRANCH ≥ 60%), SpotBugs
  static analysis (effort Max, threshold High), integration/E2E suites via Testcontainers, and an
  architecture boundary check with self-test. `core/` is framework-free: no Spring/Lombok
  annotations; beans are wired explicitly in `infra/config`.

> **Scope note adapted from the original README:** earlier revisions of this README overclaimed
> (e.g. "invalid IDs never reach the database", persisted analytics). The documentation now reflects
> the code as it stands, and remaining gaps are listed in the Roadmap and in `AGENTS.md` (debt matrix).

## Roadmap

Deliberately not implemented yet — candidate backlog, in priority order:

- **Links as Resource (Phase B) — landed.** `/api/v1/urls` list/get/patch/delete (owner-scoped,
  cursor pagination, soft delete, PATCH supplied-field semantics). See Current State and
  `tasks/links-as-resource-*.md`.
- **Real analytics persistence — landed.** Click events persist to `click_events` via a durable
  Redis Stream + batched worker; `clickCount` is updated atomically (`$inc`), quota counters too.
- **Rate limiting on the redirect path — landed.** `GET /{id}` now has per-IP token-bucket over Redis (Rule 5): independent REDIRECT scope with capacity 120/min (configurable), Redis TIME-driven atomic Lua script, trusted-proxy CIDR IP resolution, fail-open policy, 429 with `Retry-After` + `RateLimit-*` headers. Scope isolation: exhausting redirect budget never affects shorten. ITs prove anti-enumeration (unknown-code probes throttled), exact capacity under burst, and scope isolation.
- **TTL / link expiration — landed.** `ttlSeconds` input → `expiresAt`; `410 Gone` for expired at
  redirect; expiry-aware cache (never serve an expired link); MongoDB TTL index via the versioned
  migration `V5`. See Current State.
- **Land the locked identity model in code** — stories I1–I6: random Base62 + collision retry, drop
  the unique index on `originalUrl`, isolate generated codes from vanity aliases (debt items 3, 4, 7).
  The **contract** is documented in this README and `docs/data-model-decisions.md`.
  **Status: landed** in this codebase.
- **Framework-free `core` — landed.** Spring/Lombok annotations removed from the domain layer;
  beans registered in `infra/config` (`ServiceConfig`); boundary gate enforces it in CI with a
  self-test. Quality gates (JaCoCo + SpotBugs) run at `mvn verify`.
- **Validate the destination — landed.** SSRF protection implemented: HTTPS enforced by default, host validation, private/internal IP blocking (RFC1918, loopback, link-local, metadata IPs), userinfo rejection, DNS resolution with caching. Extensibility hook via `DestinationValidatorPort` for reputation checks. ITs: `SsrfProtectionIT` proves rejection of HTTP, userinfo, invalid schemes, private IPs; valid HTTPS allowed.
- **Tighten operational exposure — landed.** Actuator endpoints tiered (liveness/readiness/info public; health detail requires ADMIN; metrics/prometheus require ADMIN/METRICS_VIEWER; other actuator endpoints require ADMIN). Swagger conditionally enabled via `app.security.swagger.enabled` (default false). Health detail defaults to `when-authorized`. Swagger loaded conditionally via `@ConditionalOnProperty`.
- **Fix the GraalVM native build** — correct the `mainClass` in the `native` profile, and verify the
  documented startup/memory targets under load.
- **Observability (four pillars) — landed.** Micrometer metrics + Prometheus endpoint with latency
  percentiles and the `id.generation.duration` / `url.retrieval.duration` timers; OpenTelemetry
  tracing via Spring Boot auto-instrumentation (`micrometer-tracing-bridge-otel`) exported through
  OTLP/HTTP with 10% head sampling and collector **tail-sampling that always keeps ERROR traces**
  (`deploy/otel/otel-collector-config.yml`); `traceId`/`spanId` MDC in logs; **SLOs** — availability
  99.9%, latency p99 < 200 ms, error rate < 0.1% (`docs/slos.md`, Prometheus recording rules +
  burn-rate alerts under `deploy/monitoring/`); Grafana dashboards (`dashboards/`); k6 load tests
  (`load-tests/`, manual dispatch via `.github/workflows/load-test.yml`) and baselines
  (`docs/load-test-baseline.md`). **Structured (JSON) logging** via a `json` profile (`-Dspring.profiles.active=json`)
  using `logstash-logback-encoder`; default profile stays plain for local dev. Tracing is fail-open,
  proven by `TracingFailOpenIT`. See `docs/observability.md`.
- **Operational excellence — landed.** TLS termination via reverse proxy (NGINX/Caddy configs in
  `deploy/proxy/`); systemd unit (`deploy/url-shortener.service`) with graceful shutdown
  (`server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 30s`); fail-fast
  startup validation (`ProdConfigValidator` checks required env vars in `prod` profile); MongoDB
  backup/restore scripts (`scripts/backup-mongodb.sh`, `scripts/restore-mongodb.sh`); `click_events`
  retention purge (daily at 02:00 UTC, deletes events older than `app.analytics.retention-days`,
  default 90 days, in bounded batches); load baseline script (`scripts/performance-baseline.sh`)
  with k6 thresholds-as-code; graceful shutdown verification (`scripts/verify-graceful-shutdown.sh`).
- **CI** — `ci.yml` runs `mvn verify` with Testcontainers on push/PR; `load-test.yml` runs k6 on manual
  dispatch. **k6 redirect gate** (p95 < 200ms, error < 0.1%) is the baseline for promotion to a
  blocking gate after 2–3 calibration runs.

## Documentation

| Document | Purpose |
| :--- | :--- |
| `README.md` | This file — overview, architecture, setup and testing |
| `AGENTS.md` | Contributor/agent rules, architecture, debt matrix |
| `docs/data-model-decisions.md` | Locked identity model (Base62, no URL dedup, namespace isolation) |
| `docs/coding-standards.md` | Day-to-day Java/Spring conventions |
| `docs/testing-playbook.md` | How to design, run and maintain tests |
| `docs/twelve-factor.md` | Twelve-factor compliance |
| `docs/observability.md` | Metrics, tracing, SLOs, dashboards and runbooks |
| `docs/slos.md` | SLO definitions, error budgets, burn-rate alerting |
| `docs/load-test-baseline.md` | k6 load-test baselines (p50/p95/p99 vs SLOs) |
| `docs/release-runbook.md` | Operate and release the service |
| `docs/lessons.md` | Durable engineering lessons |
| `MONGODB_ARCHITECTURE.md` | MongoDB layering notes (Portuguese prose being migrated to English) |
| `docker-compose.yaml` | MongoDB + Redis for local development |
| `pom.xml` | Dependency, build and native-image configuration |
