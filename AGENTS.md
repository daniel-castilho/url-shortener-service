# AGENTS.md — Guidelines for AI & Human Contributors

**URL Shortener Service** — a high-performance, on-premises link-shortening API built with **Java 21**,
**Spring Boot 3.5.7**, **MongoDB** and **Redis**, implementing a **Hexagonal Architecture (Ports &
Adapters)** and **SOLID principles**.

- **Repository:** `daniel-castilho/url-shortener-service`
- **Deployment:** On-premises **bare metal** (MongoDB in Docker Compose); no cloud APIs.
- **Runtime:** JVM (default) and GraalVM native image (`-Pnative`).

Sources of truth: `README.md`, `pom.xml`, `src/main/resources/application.yaml`,
`MONGODB_ARCHITECTURE.md`, and the audit/roadmap under `/home/user` (`AUDITORIA_URL_SHORTENER.md`,
`ROADMAP_CORRECOES.md`). Re-read the relevant parts before starting any task.

> **Scope of this file:** this documents the **target** architecture and standards we are building
> toward, not only the current on-disk code. The codebase is mid-refactor; several sections describe
> the intended end state. Where the code does **not** yet conform, the gap is tracked in
> [Known Technical Debt](#📑-known-technical-debt-traceability-matrix) and must not be silently ignored.

---

## 🚫 Critical Rules (Never Violate)

1. **Architecture Boundaries:** `core/` (domain, use cases, ports) must **never** import
   `ca.tyny.urlshortener.infra.*`, nor any framework/adapter code (Spring, MongoDB, Redis, JWT,
   Camunda, JSON libs for IO). It depends only on its own models, ports, value objects and domain
   exceptions.

   _Verification command before declaring a task done:_
   ```bash
   bash scripts/check-boundaries.sh          # must report PASS (0 violations)
   bash scripts/check-boundaries.sh --self-test   # must PASS: proves the gate detects violations
   ```
   _The self-test plants a violation in a temp dir and asserts the gate catches it — run both._

2. **ID Generation Standard (base62, random, collision retry):**
   Short codes are generated from a **cryptographically secure random** source (`java.security.SecureRandom`)
   over a **Base62** alphabet (`0-9 A-Z a-z`), default length **7** (configurable via
   `app.shortener.code-length`). **No Hashids, no Redis counter, no sequential IDs** for codes.
   Collisions are resolved by **retrying** on the unique `_id` conflict (bounded retries) — never by
   silently dropping or reusing a code.

3. **No URL Deduplication:** the same long URL may be shortened multiple times, producing **distinct**
   codes. There is **no `UNIQUE` constraint** on `originalUrl`. `409 Conflict` means **only** "custom
   alias already exists" — never "URL already shortened".

4. **Namespace Isolation:** auto-generated codes and user vanity aliases never collide. Reserved words
   (`api`, `auth`, `health`, `admin`, `v1`, ...) are always rejected as codes/aliases; generated code
   length and vanity alias rules are kept structurally distinct.

5. **Redirect Path Integrity (performance-critical):** the `GET /{id}` redirect is the hot path.
   - Must be **rate-limited** (per-IP, token-bucket over Redis) — anti-enumeration.
   - **Never blocks** on analytics; click tracking is **async, fire-and-forget**.
   - Performs a **single** DB hit (cache-aside), then a `302` redirect.

6. **Security & Secrets:**
   - **Never log** raw JWTs, passwords, `APP_JWT_SECRET`, or full destination URLs
     containing credentials (no `System.out`, `console.log`, or plaintext files).
   - **HTTPS enforced by default** for all short links and destinations.
   - **No SSRF:** reject destinations resolving to internal/private/link-local IPs
     (`127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.169.254`, ...).
   - **Destinations are validated** with an extensibility hook for reputation checks (Safe Browsing /
     VirusTotal / PhishTank) and a blocklist.

7. **Concurrency Correctness:** quota counters and click counters are incremented **atomically**
   (`$inc`) — never read-modify-write. Vanity-alias creation relies on the atomic `_id` insert, not a
   check-then-put that races.

8. **English Only in Codebase:** identifiers, comments, commit messages, documentation, DTOs and error
   codes are **English** (the existing Portuguese prose in `MONGODB_ARCHITECTURE.md` is being
   migrated).

9. **No Unapproved Dependencies:** do **not** add or remove dependencies in `pom.xml` (e.g. removing
   `hashids`, adding a migration library or a tracing SDK) without explicit human approval.

10. **Doc Sync is Part of "Done":** after any feature, architecture change, bug fix or migration:
    - Update `README.md` (features / roadmap) and `AGENTS.md` (debt matrix) if affected.
    - Update `MONGODB_ARCHITECTURE.md` or add migration documentation when the data model changes.
    - Update the audit/roadmap files in `/home/user` when a roadmap item is completed.
    _Work is NOT done while documentation describes a stale state._

11. **Test Suite Integrity:** the full gate `mvn verify` (unit + `*IT` integration + E2E with
    Testcontainers) must pass before declaring a turn or commit done. Run the targeted unit tests with
    `mvn test` for fast iteration.

---

## 🛠️ Commands Matrix

| Purpose | Command | Location |
| :--- | :--- | :--- |
| **Run the dev server** | `mvn spring-boot:run` | Root |
| **Run unit tests (no Docker)** | `mvn test` | Root |
| **Run + integration/E2E tests (needs Docker + Testcontainers)** | `mvn test -Dtest='*IT'` | Root |
| **Full gate: unit + IT + E2E (Testcontainers) + jar** | `mvn verify` | Root |
| **Build the jar** | `mvn clean package` | Root |
| **Build the GraalVM native binary** | `mvn clean package -Pnative` | Root |
| **Start external services (Mongo + Redis)** | `docker-compose up -d` | Root |
| **Stop external services** | `docker-compose down` | Root |
| **Coverage gate** | `mvn verify` (JaCoCo runs at `verify`; LINE ≥ 60%, BRANCH ≥ 60%) | Root |
| **Static analysis gate** | `mvn verify` (SpotBugs runs at `verify`; effort Max, threshold High) | Root |
| **Architecture boundary check** | `bash scripts/check-boundaries.sh` (+ `--self-test`) | Root |

---

## 🏗️ Architecture & Layer Responsibilities

The application follows **Hexagonal Architecture (Ports & Adapters)** with a strict inward dependency
rule:

```
src/main/java/com/example/urlshortener/
├── Application.java                  # Spring Boot entry point (ca.tyny.urlshortener.Application)
├── core/                             # 🧠 DOMAIN + USE CASES  (pure, zero framework)
│   ├── exception/                    # Domain exceptions (UrlNotFound, AliasAlreadyExists, QuotaExceeded)
│   ├── idgeneration/                 # UrlIdGenerator + strategies (RandomBase62 / Vanity) — composite
│   ├── model/                        # Entities & value objects (ShortUrl, Url, User, ClickEvent, QuotaUsage…)
│   ├── ports/
│   │   ├── incoming/                 # Inbound ports: GetUrlUseCase, ShortenUrlUseCase
│   │   └── outgoing/                 # Outbound ports: UrlRepositoryPort, UserRepositoryPort,
│   │                                 #   UrlCachePort, AnalyticsPort, RateLimiterPort, MetricsPort,
│   │                                 #   IdGeneratorPort, (future) DestinationValidationPort
│   ├── service/                      # Use-case orchestration (UrlShortenerService, QuotaService, UserService)
│   └── validation/                   # ReservedWordsValidator
└── infra/                            # ⚙️ ADAPTERS  (framework & API only)
    ├── adapter/
    │   ├── input/rest/               # REST controllers, DTOs, GlobalExceptionHandler
    │   └── output/
    │       ├── analytics/            # Click-event queue + batched worker (async, persisted)
    │       ├── persistence/          # Mongo repositories, entities & mappers
    │       └── redis/                # Redis cache, bloom filter, rate limiter, ID generator
    ├── config/                       # Spring beans, security, Undertow, OpenAPI, native hints
    ├── observability/                # Micrometer metrics service & adapter
    └── security/                     # JWT filter, token provider, UserDetailsService
```

### Layer Rules

- **`core/` (Domain)** — pure Java: entities, value objects, ports, use-case services. No Spring
  annotations, no framework imports, no `infra.*` references. Inbound ports are named `*UseCase`;
  outbound ports are named `*Port`.
- **`infra/` (Adapters)** — implements the outbound ports and wires the inbound ports to Spring beans
  in `config/`. REST DTOs, Mongo `@Document` entities, Redis clients and JWT live only here.
- **Dependency rule:** `core/` depends on **abstractions** (ports) and is never aware of `infra/`.
  `infra/` depends on `core/` and implements its ports. Nothing ever points outward.
- **Swappability:** replacing MongoDB with another store, Redis with another cache, or adding a
  destination validator is a change confined to `infra/` behind the relevant port. This is the core
  value of the architecture and must be preserved during refactors.

---

## 📐 Conventions & Standards

### Java Conventions

- **Naming:**
  - Packages/classes: `camelCase` / `PascalCase`.
  - Outbound ports: `*Port` (e.g. `UrlRepositoryPort`, `RateLimiterPort`).
  - Inbound ports (use cases): `*UseCase` (e.g. `ShortenUrlUseCase`).
  - Adapters/repository impls: descriptive (e.g. `MongoUrlRepository`, `RedisUrlCache`).
  - Mappers: `*Mapper` (domain ↔ entity); DTOs: `*Request` / `*Response`.
- **Java 21 idioms:** use `record` for value objects, DTOs and immutable models; avoid Lombok in
  `core/` (prefer explicit constructors); use `Pattern`/`ZonedDateTime`/`Instant` for domain time where
  appropriate. `var` is allowed only where the type is obvious.
- **Error handling:** `core/` throws **domain exceptions**; `infra/` maps framework exceptions into
  domain/`RepositoryException` types that never leak Mongo/Redis/JWT details upward. Use
  `Result`-shaped domain errors where a check must be handled, not thrown.
- **DTO boundaries:** never return raw domain entities or internal secrets over the wire. Map to
  explicit response DTOs in the REST adapter.
- **No inline fully-qualified class names** (e.g. `ca.tyny.urlshortener.core.validation.X`);
  use `import`.

### Spring / Configuration Conventions

- Keep `core/` **annotation-free**; register domain beans in `infra/config` (e.g. `ServiceConfig`).
- Bind configuration via typed `@ConfigurationProperties`; reference env-overridable keys
  (`MONGODB_URI`, `REDIS_HOST`, `APP_JWT_SECRET`, `rate-limiter.limit`, `app.shortener.code-length`, ...).
- Never commit real secrets; the bundled defaults in `application.yaml` are dev-only and the JWT
  provider must warn when the default secret is used.

---

## 🧪 Testing Strategy

- **Unit tests (`core/`)** — JUnit 5 + Mockito, no Spring context, no I/O. Cover entities, value
  objects, ID generation (incl. collision retry), quota, reserved words and use-case services against
  mock ports. Run with `mvn test` (no Docker).
- **Integration tests (`*IT`)** — Testcontainers boot real MongoDB + Redis; validate persistence,
  cache, bloom filter, rate limiter, analytics persistence and the redirect path. Named `*IT` so
  `mvn test` does not run them; run with `mvn test -Dtest='*IT'` or by `mvn verify` (failsafe).
- **End-to-end tests** — RestAssured against a running app on a random port
  (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) with Testcontainers. Validate complete flows:
  shorten → redirect, auth → vanity URL, quota enforcement, expiry.
- **Security-driven tests:** JWT with default/weak secret, broken/oversized tokens, SSRF/internal-IP
  destinations, HTTP-destination rejection, rate-limit on the redirect path, open-redirect.
- **Concurrency tests:** vanity-alias race → exactly one winner; atomic `$inc` quota/click under
  concurrent requests.

---

## 📝 Commit & Git Standards

Follow **Conventional Commits**:

- `feat:` New capability or user-facing feature
- `fix:` Bug fix or error resolution
- `refactor:` Code restructuring without changing behaviour (e.g. boundary cleanup, rename)
- `docs:` Documentation updates (`README.md`, `AGENTS.md`, `MONGODB_ARCHITECTURE.md`)
- `chore:` Dependency or build script updates
- `perf:` Performance improvements (cache, async, redirect-path work)

Keep commits atomic and focused. Do not mix doc cleanup with behavioural fixes unless they are
tightly related (Rule 10 example).

- Do **not** push unless the human explicitly asks.

## 🏷️ Releases

- Pick the next version from the **highest existing tag** (`git ls-remote --tags origin`, mirrored
  by the CHANGELOG headings) — do not infer from this note.
- Tag only when a milestone meets its **Definition of Done** and the human asks for it.
- Before tagging:
  1. Add a high-level entry to `CHANGELOG.md` (or promote the `Unreleased` block to a version).
  2. Update `README.md` → "Current State".
  3. Update `AGENTS.md` → "Known technical debt".
  4. Create the annotated tag (`git tag -a v0.X.0 -m "v0.X.0 — <short title>"`).

---

## 📑 Known Technical Debt (Traceability Matrix)

Items currently deferred or awaiting the refactor. Do **not** silently introduce new debt — flag any
new item here. Status: `open` (to do), `in-progress`, `resolved`.

1. **UserService leaks `infra` into `core`** — `core/service/UserService` imports
   `MongoUserRepository`, `JwtTokenProvider`, REST DTOs (`AuthResponse`, `RegisterRequest`, ...).
   Refactored to depend on `UserRepositoryPort`, `TokenPort`, `PasswordEncoderPort`,
   `AuthenticationPort`; REST DTO mapping moved to `AuthController`. — `resolved`
2. **Spring annotations in `core/`** — `@Component`/`@Service`/`@RequiredArgsConstructor` in
   `core/idgeneration`, `core/service/QuotaService`, `core/validation/ReservedWordsValidator`. Lombok
   and Spring annotations removed from all five affected classes (explicit constructors); beans are
   now registered in `infra/config` (`ServiceConfig`, with `@Order(1)` so the vanity strategy wins
   evaluation order in the composite generator). Boundary gate extended with a `lombok` regex check
   and a `--self-test` mode; wired into CI. — `resolved`
3. **Land random Base62 in code (Rule 2)** — the locked identity model is random Base62
   (`SecureRandom`) + bounded collision retry; **not** a Redis counter or Hashids. Remaining work is
   to replace `RangeAwareIdGenerator` / `org.hashids` / `SHORTENER_SALT` with that contract (stories
   I1–I2). Do not document Hashids as the product design. — `resolved`
4. **Drop unique index on `originalUrl` (Rule 3)** — the locked model has **no URL dedup**. Remaining
   work is to drop `UNIQUE` on `originalUrl` and add a SHA-256 `urlHash` (non-unique) for future
   analytics (stories I4–I5). Do not document unique-on-URL as the product design. — `resolved`
5. **Analytics not persisted** — click events are now persisted to a `click_events` collection by the
   Redis-Stream consumer (`ClickBatchWorker` bulk-inserts + `$inc` per unique code); durable queue via
   `RedisClickEventQueue` (`XADD MAXLEN ~`), self-healing consumer group, fail-open policy. — `resolved`
6. **No rate limit on the redirect path** — `GET /{id}` now has a per-IP token bucket over Redis (Rule 5). Implementation uses a Redis TIME-driven atomic Lua script, independent scopes (SHORTEN/REDIRECT), trusted-proxy CIDR IP resolution, and fail-open policy. Rate limit headers (`Retry-After`, `RateLimit-*`) are emitted on 429. — `resolved`
7. **Namespace isolation between generated code and vanity alias (Rule 4)** — locked contract:
   generated codes are exactly `code-length` Base62 chars; vanity aliases use a disjoint shape
   (plan min length and/or `-`/`_`) plus reserved-word rejection. Remaining work is to enforce that
   structurally in code (story I3). — `resolved`
8. **Weak URL validation / SSRF** — only `^https?://.*`; strengthen to enforce HTTPS, validate the
   host, and block internal/private/link-local IPs (Rule 6). — `open`
9. **Actuator & Swagger publicly exposed** — `/actuator/**` and `/swagger-ui/**` are `permitAll` and
   `health.show-details: always`. Restrict in production and gate detail exposure. — `open`
10. **No versioned schema/index migrations** — `spring.data.mongodb.auto-index-creation: true`; adopt a
    migration framework and manage indexes in a deploy step. Required to drop the `originalUrl`
    unique index (item 4). `IndexMigration` now drops `originalUrl_1` index and ensures `userId`
    index. — `resolved`
11. **GraalVM native build broken** — `native` profile `mainClass` points to the non-existent
    `ca.tyny.urlshortener.infra.Application`. Fix to `ca.tyny.urlshortener.Application`. — `resolved`
12. **Observability gaps** — metrics exist but `id.generation.duration` / `url.retrieval.duration`
    timers are never recorded; no tracing (OpenTelemetry), no SLOs, no load harness (k6). When added,
    record real p50/p95/p99 and publish a baseline. — `open`
13. **Stale docs & assets** — `README.md` referenced non-existent `AUDIT_FINAL_REPORT.md`,
    `VALIDATION_CHECKLIST.md`, `LESSONS_LEARNED.md`; residual Cassandra mentions; inflated self-scores;
    committed `build.log`/`build_out.txt`. Product docs now describe the locked identity model
    (`README.md`, `docs/data-model-decisions.md`, coding standards, testing playbook, lessons).
    Residual Portuguese/Cassandra prose in `MONGODB_ARCHITECTURE.md` is still being migrated. — `resolved`
14. **Quota increment is non-atomic** — `QuotaService.incrementVanityUrlUsage` did read-modify-write
    (`set(get()+1)`), losing increments under concurrency. Now delegates to
    `UserRepositoryPort.incrementVanityUsage` (atomic `$inc` on both counters). — `resolved`
15. **In-memory analytics queue drops events** — `LinkedBlockingQueue` (100k) replaced by a durable,
    bounded Redis Stream (`RedisClickEventQueue`, `XADD MAXLEN ~`) behind `AnalyticsPort`; consumer
    is self-healing and at-least-once (item 5). — `resolved`
16. **Quality gates wired** — JaCoCo 0.8.15 (LINE ≥ 60%, BRANCH ≥ 60%) and SpotBugs 4.9.8.5
    (effort Max, threshold High) run at `mvn verify`; both gates green. Testcontainers upgraded
    1.19.3 → 1.21.3. **Environment note:** Docker Engine ≥ 29 only serves API `1.44+` while docker-java
    probes with older defaults — machines running such engines need `~/.docker-java.properties`
    containing `api.version = 1.44` (already configured on the dev workstation; CI runners are
    unaffected). — `resolved`

---

## 🔍 Operational Discipline & Debugging Guidelines

- **Investigate before trial-and-error:** when a compile or test fails, read the full stack trace and
  verify the relevant port/entity contract before editing code.
- **Isolate reproductions:** when an endpoint fails, add/target a `*IT` or unit test against the
  underlying use case first, to separate framework wiring (Spring/Mongo/Redis) from business logic.
- **Respect the boundary:** if a fix requires reaching into `infra` from `core`, stop and re-read Rule 1
  — the fix belongs on the `infra` side or behind a port.
- **Keep the workspace clean:** never commit build artifacts (`target/`, `build.log`,
  `build_out.txt`), `.env` secrets, local test databases, or ephemeral files.
- **Document, don't guess:** every roadmap item you complete must be reflected in the docs (Rule 10).
  When in doubt about a requirement, ask before assuming.

---

Designed & Maintained for **URL Shortener Service** — on-premises, high-performance, hexagonal.
