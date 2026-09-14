# AGENTS.md — Guidelines for AI & Human Contributors

**URL Shortener Service** — a high-performance, on-premises link-shortening API built with **Java 25**,
**Spring Boot 4.1.1**, **MongoDB** and **Redis**, implementing a **Hexagonal Architecture (Ports &
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
    migrated). All code, configuration, and documentation must be in English — this includes comments,
    commit messages, documentation, DTOs, and error codes.

9. **No Unapproved Dependencies:** do **not** add or remove dependencies in `pom.xml` (e.g. removing
   `hashids`, adding a migration library or a tracing SDK) without explicit human approval.

10. **Doc Sync is Part of "Done":** after any feature, architecture change, bug fix or migration:
    - Update `README.md` (features / roadmap) and `AGENTS.md` (debt matrix) if affected.
    - Update `MONGODB_ARCHITECTURE.md` or add migration documentation when the data model changes.
    - Update the audit/roadmap files in `/home/user` when a roadmap item is completed.
    _Work is NOT done while documentation describes a stale state._

11. **Test Suite Integrity:** the full gate `./mvnw verify` (unit + `*IT` integration + E2E with
    Testcontainers) must pass before declaring a turn or commit done. Run the targeted unit tests with
    `./mvnw test` for fast iteration.

---

## 🛠️ Commands Matrix

| Purpose | Command | Location |
| :--- | :--- | :--- |
| **Run the dev server** | `./mvnw spring-boot:run` | Root |
| **Run unit tests (no Docker)** | `./mvnw test` | Root |
| **Run + integration/E2E tests (needs Docker + Testcontainers)** | `./mvnw test -Dtest='*IT'` | Root |
| **Full gate: unit + IT + E2E (Testcontainers) + jar** | `./mvnw verify` | Root |
| **Build the jar** | `./mvnw clean package` | Root |
| **Build the GraalVM native binary** | `./mvnw clean package -Pnative` | Root |
| **Start external services (Mongo + Redis)** | `docker-compose up -d` | Root |
| **Stop external services** | `docker-compose down` | Root |
| **Coverage gate** | `./mvnw verify` (JaCoCo runs at `verify`; LINE ≥ 60%, BRANCH ≥ 60%) | Root |
| **Static analysis gate** | `./mvnw verify` (SpotBugs runs at `verify`; effort Max, threshold High) | Root |
| **Architecture boundary check** | `bash scripts/check-boundaries.sh` (+ `--self-test`) | Root |
| **Living-spec traceability check** | `bash scripts/check-living-spec.sh` (+ `--self-test`) | Root |

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
    ├── config/                       # Spring beans, security, Tomcat, OpenAPI, native hints
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
- **Java 25 idioms:** use `record` for value objects, DTOs and immutable models; avoid Lombok in
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
  mock ports. Run with `./mvnw test` (no Docker).
- **Integration tests (`*IT`)** — Testcontainers boot real MongoDB + Redis; validate persistence,
  cache, bloom filter, rate limiter, analytics persistence and the redirect path. Named `*IT` so
  `./mvnw test` does not run them; run with `./mvnw test -Dtest='*IT'` or by `./mvnw verify` (failsafe).
- **End-to-end tests** — RestAssured against a running app on a random port
  (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) with Testcontainers. Validate complete flows:
  shorten → redirect, auth → vanity URL, quota enforcement, expiry.
- **Security-driven tests:** JWT with default/weak secret, broken/oversized tokens, SSRF/internal-IP
  destinations, HTTP-destination rejection, rate-limit on the redirect path, open-redirect.
- **Concurrency tests:** vanity-alias race → exactly one winner; atomic `$inc` quota/click under
  concurrent requests.
- **Living specifications:** each Business Component owns a `package-info.java` in its main package
  declaring its **EARS requirements** (`### REQ-<COMP>-<NNN>`, `**When** ... **the Business
  Component shall** ...`), its ports and its local ADR-style decisions. Tests are linked to
  requirements **only** via `@TracesRequirement("REQ-...")` (`core/annotation`) — the single
  source of truth for traceability; `@DisplayName` conventions are cosmetic and never read by the
  gate. A component joins the hard gate by setting `@spec-complete true` (ratchet: gate one
  component at a time). Gate: `bash scripts/check-living-spec.sh`
  (+ `--self-test`), wired into CI — it enforces (a) ≥ 90% of the declared requirements of
  spec-complete components are traced (the threshold is contract — refine EARS granularity, never
  the threshold), (b) no dangling `@TracesRequirement` pointing at a requirement nobody declared,
  and (c) every test class inside a gated component's package with no traces at all is listed in
  the registry below.

### Living-Spec Debt Registry (machine-readable — `check-living-spec.sh` reads this)

Untraced test classes inside gated component packages. Fields: `class — reason (owner, deadline)`.
Items expire at the next epic; do not add new ones without a reason that survives review.

- `ca.tyny.urlshortener.infra.adapter.output.persistence.UserEntityTest` — structural POJO test
  (constructors/getters on `UserEntity`); observes no EARS requirement behavior — user round-trip
  persistence is covered by `MongoUserRepositoryIT` (REQ-PERSIST-005). Fold into
  `MongoUserRepositoryIT` or remove at the next epic (owner: daniel-castilho, deadline: next epic).

Entries must be removed from this list before their deadline (next epic) or they block the gate.

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
   host, and block internal/private/link-local IPs (Rule 6). — `resolved`
9. **Actuator & Swagger publicly exposed** — `/actuator/**` and `/swagger-ui/**` are now tiered: liveness/readiness/info public; health detail requires ADMIN; metrics/prometheus require ADMIN or METRICS_VIEWER; other actuator endpoints require ADMIN. Swagger enabled only when `app.security.swagger.enabled=true` (default false). Health detail defaults to `when-authorized`. — `resolved`
10. **No versioned schema/index migrations** — previously `spring.data.mongodb.auto-index-creation: true`.
    `MongoSchemaMigrator` (a versioned, in-code, checksummed, fail-fast runner recording into
    `schema_migrations`) now owns the schema: migrations `V1`–`V6` create core collections, drop the
    `originalUrl` unique index, and ensure the `userId`, `click_events`, `expiresAt` TTL, and `users`
    (`email` unique, `plan`, `createdAt`) indexes.
    `IndexMigration` is retired. A Flyway-for-MongoDB migration was attempted and rejected (JDBC
    driver not published to Central; native connectors are CLI-only) after human approval of the
    in-code runner. — `resolved`
11. **GraalVM native build broken** — `native` profile `mainClass` fixed to `ca.tyny.urlshortener.Application`. — `resolved`
12. **Observability gaps** — metrics exist but `id.generation.duration` / `url.retrieval.duration`
    timers are never recorded; no tracing (OpenTelemetry), no SLOs, no load harness (k6). Timers now
    recorded with p50/p95/p99 via `MetricsPort`; OpenTelemetry tracing (10% head sampling) with the
    OTel Collector tail-sampling ERROR traces always-on; SLOs + burn-rate alerts; k6 scripts +
    manual-dispatch CI; Grafana dashboards. **Published first k6 baseline** (`docs/load-test-baseline.md`);
    **analytics.queue.depth gauge** exposed via `RedisClickEventQueue`. — `resolved`
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
    (effort Max, threshold High) run at `./mvnw verify`; both gates green. Testcontainers upgraded
    1.19.3 → 1.21.3. **Environment note:** Docker Engine ≥ 29 only serves API `1.44+` while docker-java
    probes with older defaults — machines running such engines need `~/.docker-java.properties`
    containing `api.version = 1.44` (already configured on the dev workstation; CI runners are
    unaffected). — `resolved`
17. **Observability follow-ups (after item 12)** — the Grafana overview panel `analytics.queue.depth`
    is empty until the analytics Redis-stream depth is exposed as a gauge, and `docs/load-test-baseline.md`
    is a template until the first real k6 run (manual dispatch via `.github/workflows/load-test.yml`)
    records numbers. **Both done: gauge exposed, baseline published.** — `resolved`
18. **Operational Excellence gaps** — no TLS termination, no systemd deploy/rollback, no backup/restore
    scripts, no click_events retention, no graceful-shutdown verification, no fail-fast startup validator.
    **All landed:** NGINX/Caddy configs (`deploy/proxy/`); systemd unit (`deploy/url-shortener.service`);
    `ProdConfigValidator` (fail-fast on missing/weak env vars in `prod` profile); `scripts/backup-mongodb.sh`
    + `scripts/restore-mongodb.sh`; `ClickEventsRetentionPurge` (daily, bounded, idempotent, 90-day default);
    `scripts/verify-graceful-shutdown.sh`; `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`;
    load baseline (`scripts/performance-baseline.sh`) with real k6 numbers published. — `resolved`

19. **Read-path residue (P0-3)** — the Bloom filter did not actually avoid MongoDB; non-existent codes
    hit the DB unconditionally. **Resolved via Policy B:** introduced `CacheLookup` domain type with
    explicit `Absence { NONE, MISS, BLOOM_NEGATIVE }` signal; `UrlCachePort.lookup()` returns this;
    `RedisUrlCache` maps bloom-negative → `BLOOM_NEGATIVE`; `UrlShortenerService` treats bloom-negative
    as lightweight cache-miss per Policy B (resolved by `findById`). Added `ReadPathIT` with 5 tests
    verifying the behaviour. Corrected "bloom avoids DB" claim in docs. `ReadPathIT` + k6 baseline
    published. — `resolved`

20. **Links as Resource (Phase B) — link management endpoints** — implemented per `tasks/links-as-resource/links-as-resource-*.md`:
    ISP port split (`LinkQueryPort` + `LinkMutationPort`, `MongoUrlRepository` implements both;
    `UrlRepositoryPort` for the shortening/redirect port unchanged), 4 use cases (`ListUserLinks`,
    `GetLink`, `UpdateLink`, `ArchiveLink`), REST endpoints `GET/PATCH/DELETE /api/v1/urls[/{id}]`
    (`LinkController`, authenticated, owner-scoped at the application layer with `ForbiddenException`
    → 403), cursor pagination (Base64url `<epochMillis>:<id>`, `createdAt DESC` + `_id` DESC, limit 100,
    malformed → 400, `PageResult<ShortUrl>` return type), PATCH supplied-field capture (`@JsonAnySetter`
    on `UpdateLinkRequest`; `*Supplied` flags on `UpdateLinkCommand` so present-null clears only
    `expiresAt`/`utm`), soft delete via `deletedAt` (idempotent; archived redirect → 404)](*), cache
    eviction on update/archive (`UrlCachePort.evict`). Docs synced (README, data-model-decisions, coding-standards,
    testing-playbook). Tests: `LinkResourceIT` (25), `LinkUseCasesTest` (12), extended `MongoUrlRepositoryIT`.
    `./mvnw verify` + boundary gate green. — `resolved`

---

21. **Platform upgrade: Java 21 + Spring Boot 3.5.7 + Undertow → Java 25 + Spring Boot 4.1.1 + Tomcat**
    — Boot 4 removed Undertow support; the app now runs on **Tomcat 11** (virtual threads enabled),
    Jakarta EE 11, `spring.mongodb.*` (was `spring.data.mongodb.*`), `spring-boot-starter-aspectj`
    (was `aop`), Jackson 3 (`tools.jackson` for object I/O), `@WebMvcTest`/`@AutoConfigureMockMvc` from
    `spring-boot-starter-webmvc-test`, Spring Security 7 (`DaoAuthenticationProvider` constructor),
    Redis:4 `ValueOperations.set()` generics, Testcontainers **2.0.5** (`MongoDBContainer`
    `org.testcontainers.mongodb`; **replica-set init is explicit via `withReplicaSet()`** — the 1.x
    auto-init is gone), Redisson 4.7.0, jjwt 0.12.7, springdoc 3.1.1, spotbugs 4.10.4.1, lombok
    1.18.46, logstash-encoder 9.0, **REST Assured 6.0.1** (5.5.7 pulls Groovy 5.0.8 which NPEs in
    `ClosureMetaClass` — 5.5.x cannot run under Groovy 5), and a bounded Maven **wrapper 3.9.16**
    (`./mvnw`). Dockerfile/CI/systemd/prod-validator/`application*.yaml` updated; `ReadPathIT` etc.
    use the singleton-container pattern (no `@DirtiesContext`) with WT cache cap + ulimit. Full gate
    `./mvnw verify` (265 unit + 114 IT) + SpotBugs + JaCoCo + boundary gate green. **Follow-up done:**
    `scripts/performance-baseline.sh` re-run on the new platform (isolated ports; k6 v2.2.0 container)
    and `docs/load-test-baseline.md` refreshed — 2026-09-09 baseline published; tails ~40–85% above the
    2026-08-27 baseline, but the measurement stack changed too (k6 v0.58.0 native → v2.2.0 container,
    Redis 7 → 8.10.1), so a like-for-like comparison is still pending before attributing any regression
    to Tomcat 11 vs Undertow. The baseline script also gained an isolated mode (`BASELINE_SKIP_COMPOSE=1`,
    `PORT`/`MONGODB_URI`/`REDIS_*`) and a k6 v2 summary-export parser fix.
    — `resolved`

22. **Epic 1 (Maintainable — Foundation & Patterns): maintainability gates** — Spotless
    3.10.2 + google-java-format 1.36.1 bound at `validate` (check-only; `spotless:apply` to
    normalize — base already normalized in dedicated `style:` commit, 220 files, unit suite 269/269
    green after reformat); ArchUnit 1.5.0 (test scope) with `BoundaryRulesTest` encoding Rule 1
    in bytecode + `BoundaryRulesSelfTestTest` (gate that bites — same discipline as
    `check-boundaries.sh --self-test`); promotion of recurring lessons (≥3 occurrences) to
    `coding-standards.md` §14 ("Inherit Lessons": §14.1 deliberate fail-open/fail-fast, §14.2
    atomic counters) with marking `→ coding-standards` on source lessons; new gate
    `scripts/check-doc-sync.sh` (+ `--self-test`) validating debt matrix status and promotions
    `lessons ↔ coding-standards`, wired in CI (job `doc-sync`). Human approval for additions to
    `pom.xml` (Rule 9) recorded in epic planning session. — `resolved`

23. **Epic 2 (Secure by Design) — OWASP Dependency-Check in CI**: dependency-check-maven
    12.2.2 bound to `verify` (fail CVSS ≥ 7, `owasp-suppressions.xml` empty with policy
    rationale+review per line), `scripts/check-security.sh` (+ `--self-test`) and CI job `security-check`
    (cache NVD + retry 2x + fail-open documented). Fixed kotlin-stdlib CVE-2026-53914 (bump
    2.4.20) and removed spring-boot-devtools (CVE-2022-31691 false positive from CPE; owner approval);
    `opentelemetry-api` CVE-2026-54285 = MEDIUM < 7 and CPE from opentelemetry-js, no suppression.
    **NVD data source (2026-09-11): nightly ODC mirror (`DependencyCheck_Builder`) via
    `nvdDatafeedUrl`** — the cold sync directly from NVD API 2.0 hung >1h in CI (keyed and keyless),
    matching upstream #7431/#8435; the mirror sync completes in ~2min (verified locally) and check in
    ~15s; the secret `NVD_API_KEY` remains in the org but is **not** wired (datafeed ignores it). — `resolved`

24. **Metrics duplicated outside the port (Epic 3 story 3.2)** — `infra/observability/MetricsService`
    recorded `urls.shortened.total`, `redirects.total`, `cache.hits.total`, `cache.misses.total`,
    `bloomfilter.rejections.total`, `shorten.latency`, `redirect.latency` and was wired directly into
    `UrlController` (infra→infra, outside `MetricsPort`); `MicrometerMetricsAdapter` registered the
    same series in an overlapping way. README even labeled `redirects.total`/`shorten.latency`/
    `redirect.latency` as "phantom" — incorrect claim: `MetricsService` was actively used by
    `UrlController`. **Resolved:** `MetricsService` + `MetricsServiceTest` removed; the 7 meters
    were folded into `MicrometerMetricsAdapter` via 3 new methods in `MetricsPort`
    (`recordRedirect`, `recordShortenLatency`, `recordRedirectLatency`); `UrlController` now depends
    on `MetricsPort`. Names/tags/descriptions preserved byte-for-byte (identical Prometheus series).
    New gate `scripts/check-metrics-frozen.sh` (+ `--self-test`) freezes the **24 business series**
    registered (list in `docs/slos.md` §2) and runs in CI; AC "gate at `verify`" implemented by CI
    step (precedent from Epic 2 / Rule 9 — no new pom dependency). — `resolved`

25. **Actuator index accessible anonymously via catch-all `GET /{id}` (Epic 3 story 3.3)** — the
    `SecurityConfig` permitted `GET /{id}` (redirect) as permitAll; since the matcher for `/{id}`
    was declared BEFORE the actuator block, the index `/actuator` (1 segment) fell into the permitAll
    and returned 200 anonymously, listing exposed endpoints. **Resolved:** matcher
    `.requestMatchers("/actuator").hasRole("ADMIN")` moved before `/{id}` ("actuator" is
    a reserved word by `ReservedWordsValidator`, so no vanity alias collides). New
    `ProductionLockdownIT` (7 tests): liveness 200, readiness 200 (Mongo+Redis up, body without
    components/redis/mongo), `/actuator/health` no detail leak (401 anonymous, 403 authenticated
    without role), `/actuator/prometheus`/`/actuator/metrics` + other endpoints role-gated,
    `/actuator/info` public. — `resolved`

26. **Actuator tier effectively hard-locked: no operator role (Epic 3 story 3.3, follow-up)** —
     `CustomUserDetailsService` returned `Collections.emptyList()` as authorities and the `User`
     model has no role fields; no principal could reach `ROLE_ADMIN`/`METRICS_VIEWER`, so
     `/actuator/health` (components), `/actuator/metrics` and `/actuator/prometheus` were inaccessible
     via HTTP even for operators (401/403 always). Additionally `security.actuator.health-detail-enabled`
     was bound in `SecurityProperties` but never consumed (the real switch is
     `management.endpoint.health.show-details`).
     **Resolved (2026-09-11):** operator identity implemented as **BasicAuth scoped to
     actuator** — `BasicOperatorAuthFilter` (non-bean, instantiated via `addFilterBefore` in
     `SecurityConfig` per the canonical Spring Security 7 pattern for custom filters) grants
     `ROLE_OPERATOR` when credentials match `app.security.operator.username/password`
     (env `OPERATOR_USERNAME`/`OPERATOR_PASSWORD`, constant-time comparison `MessageDigest.isEqual`;
     empty outside prod = **no** operator account exists, any Basic fails closed). Tiers:
     health/metrics/prometheus/circuitbreakers → `ADMIN | OPERATOR` (metrics also
     `METRICS_VIEWER`); env/beans/index remain ADMIN-only (least privilege). Dead property
     `health-detail-enabled` **removed** from `SecurityProperties` (the real switch
     `HEALTH_SHOW_DETAILS:when-authorized` now has a real authenticated user to authorize — health
     with components visible to operator). `ProdConfigValidator` fail-fast in prod: operator missing,
     password < 16 chars or guessable username (operator/admin) aborts boot. Fixed together: the
     `security:` block in `application.yaml` NEVER bound (real prefix is `app.security`; lived on
     `@DefaultValue`) — moved to `app.security:`. `ProdConfigValidatorIT` +3 (8 total);
     `OperatorAccessIT` new (9: health with details, metrics+named, prometheus scrape,
     circuitbreakers with databaseCb, least-privilege 403 on env/beans/index, wrong password 401,
     non-existent user 401, anon 401, JWT user 403). Manual curl (real env): health 200 with
     components (mongo/redis/circuitBreakers CLOSED), `metrics/jvm.memory.used` 200 (value
     208288504.0), prometheus 200, circuitbreakers 200, env 403, wrong password 401, anon 401. — `resolved`

27. **Prometheus registry missing from pom (Epic 3 story 3.7)** — after the Boot 4.1.1
    upgrade (debt 21), `micrometer-registry-prometheus` was not in `pom.xml`; without the artifact the
    Actuator does not mount `/actuator/prometheus` (no data source for scrape/SLOs/alerts/Grafana).
    **Resolved:** dependency added (Rule 9 — explicit owner approval 2026-09-11, version
    managed by BOM, jar 1.17.1). `MetricsIT.playbackExportsEpic2BusinessSeries` plays back the
    Prometheus scrape (normalized names `*_total`/`*_seconds`) of the EP2 series + `analytics_queue_depth`.
     Bonus: fixed ordering regression in `SecurityConfig` (introduced in 3.3) that caused
     `POST /api/v1/urls` (permitAll) to fall into the `/api/v1/urls/**` matcher (authenticated → 401
     anonymous); publics reordered before managed ones and `/actuator` → `ROLE_ADMIN` before `GET /{id}`.

28. **Epic 5 (Performance) — SLOs, like-for-like, profiling, cache config, stress** — completed
     2026-09-11. (a) Baseline k6 re-run on identical stack to 2026-09-09 (k6 v2.2.0 container,
     Redis 8.10.1, Mongo 6.0.28): tails **lower** than both baselines (redirect p99 8.75ms vs
     21.7/17ms; shorten p95 11.97ms vs 24.1/16ms) — verdict: measurement noise, **not** a Tomcat 11
     regression; like-for-like pending `docs/load-test-baseline.md` resolved. (b) JFR profile
     (236s under mixed load, `jcmd`): healthy GC (155 pauses, median 3.43ms, 0.21% wall clock), zero
     contention, allocation dominated by framework observation-plumbing — **no mitigation
     justified** (37× headroom); `docs/performance-profiling.md`. (c) `RedisUrlCache` externalized
     via `UrlCacheProperties` (`app.cache.l1-max-size`/`l1-ttl`/`bloom-*`, historical defaults
     preserved) + `UrlCachePropertiesIT` (4). (d) `load-tests/stress.js` (ramping 2×: 400/40 rps,
     hold 4m): 165.498 reqs, **0 failures**, p95 < 5ms. `./mvnw verify` green (271 unit + 144 IT),
     gates + CI green; evidence in `tasks/epic-5/epic-5-dod.md`. — `resolved`

29. **Epic 6 (Scalable) — ADRs, explain audit, multi-instance artifacts, horizontal scale
     validation** — completed 2026-09-11. (a) 4 ADRs in `docs/adr/` (0001 horizontal stateless
     scale; 0002 rate-limit global via Redis; 0003 L1 Caffeine per instance w/ staleness
     ≤5s; 0004 circuit breakers resilience4j). (b) Explain audit on isolated infra with real data
     (7.103 short_urls / 112.956 click_events): redirect = IDHACK (1 key/1 doc), cursor
     pagination = IXSCAN V7, analytics = IXSCAN V4, TTL V5 — zero COLLSCAN, no new indexes.
     (c) Multi-instance artifacts: `nginx.conf` upstream w/ weights (canary 10→30→100) +
     `max_fails=2 fail_timeout=10s`; systemd template `url-shortener@.service` (derived port);
     image `url-shortener:sha-583832b` = 302MB (198MB base JRE + 77MB jar; meta 150MB of
     template not adopted — jlink out of scope); runbook §0/§12. (d) Real horizontal
     validation: 2 instances (18080/18081) + LB nginx sharing Mongo/Redis, stress 2× via LB
     = 165.499 reqs, **0 5xx, p95 7.28ms** (27× headroom); **rate-limit global proven**:
     burst concurrent of 300 via LB → exactly 120×302 + 180×429 (single bucket
     `rl:redirect:127.0.0.1`). `./mvnw verify` green (271 unit + 144 IT, rerun 144/0); evidence
     in `tasks/epic-6/epic-6-dod.md`. HTTP read of the CB state (`/actuator/circuitbreakers`)
     is available through the **operator role** of debt 26 — status `resolved` since 2026-09-11,
     covered by `OperatorAccessIT` (circuitbreakers → 200 via BasicAuth, no JWT/role). — `resolved`

30. **Epic 7 (Reliable) — failure mode contract, real PEL, proven DR drill** — completed
     2026-09-11. (a) Failure mode matrix 7.1 (fail-open/fail-closed per dependency) in
     `docs/reliability.md` + **ADR 0005** (fail-open Redis/analytics) + **ADR 0006** (at-least-once,
     exactly-once rejected). (b) CB/timeout/retry inventory + ITs for Mongo/Redis down; shutdown
     script green + liveness ≠ readiness. (c) **Real PEL redelivery** in `ClickBatchWorker`
     (crash-recovery: drains PEL with `ReadOffset.from("0")` before `lastConsumed()`) — before,
     unacked batch was orphaned and redelivery was dead code; `ClickPipelineRedeliveryIT`
     proves reassign + reclaim (assertions by delta on `analytics.events.failed.total`, which accumulates
     in shared context) and poison doesn't block the group. (d) Isolated DR drill (Mongo 27018 /
     Redis 6380 ports Epic 5/6): containerized backup/restore via `mongorestore` (7,640 short_urls;
     RPO proven: pre-backup code 302, post-backup code 404); fault injections with numbers —
     Redis-down mid-run = 5730/5730 checks 100%, `http_req_duration` p95 744ms (fail-open, ADR
     0005); Mongo-down cold-cache = CB fail-closed p50 3.56ms (surge of 504) + p99 27s (sampling
     window), auto-recovery (HALF_OPEN → CLOSED, no restart). Incident playbooks with real
     commands in `docs/release-runbook.md` §5b. (e) Final gates green:
     `check-metrics-frozen`/`check-boundaries`/`check-doc-sync`/`check-security` (all + `--self-test`);
     **`promtool test rules` caught invalid annotation name in `alerts.yml`** (`runbook-§Fast-burn`
     → `runbook_fast_burn`/`runbook_slow_burn`/`runbook_budget_exhausted`; `docs/slos.md` +
     `alertmanager.yml` comment synced); `check-rules`/`test-rules`/`amtool check-config`
     green (prom 2.53 / alertmanager 0.27 containers). `./mvnw verify` green (271 unit + 165 IT).
     Closing commit: 59bdc70 (7.5) + evidence in `tasks/epic-7/epic-7-dod.md`. — `resolved`

31. **Epic 8 (Deployable / Release Engineering) — release pipeline, blue-green, verified backups** —
    completed 2026-09-13. (a) Release contract + ADRs (`docs/release-engineering.md`, ADR
    0007 blue-green bare-metal fail-closed, ADR 0008 artifact promotion = jar from GitHub Release +
    sha256 verified; no SSH deploy from CI). (b) Artifact identity: `<revision>` +
    flatten-maven-plugin + `.flattened-pom.xml` gitignored + Dockerfile `ARG VERSION`/OCI labels +
    multi-arch (`--platform`); `scripts/check-changelog.sh` gate (red/green proven). (c) Blue-green:
    `scripts/deploy.sh` (canary 10→30→100, 30s dwell, abort names the step, self-test) + systemd
    `url-shortener-{blue,green}.service`; local cutover exercised (redirect loop only 302 through
    the bumps). (d) `scripts/smoke.sh` (8 legs) + `scripts/rollback.sh` (+ self-tests) + dead-port
    red proof. (e) Scheduled verified backups: `backup-mongodb.sh` (manifest with per-collection
    counts) + `restore-mongodb.sh --verify` (negative test) + systemd timer +
    `ci-restore-drill.sh` (RTO 19s ≤ budget 300s; lessons: leftover stack makes health-check lie;
    mongodump exits 0 silently on missing db → preflight + fail-closed). (f) **`release.yml` green
    end-to-end on tag `v0.14.0`** (run 34732409909, all 5 jobs success): Gates → k6-gate →
    runtime-smoke → restore-drill → Release (non-root image gate `uid!=0`, **Trivy HIGH/CRITICAL
    gate fixed by `apk upgrade --no-cache` in the runtime stage** closing openssl/libexpat CVEs
    — verified locally 0 vulns), CycloneDX SBOM. GitHub Release v0.14.0 with
    `url-shortener-service-0.14.0.jar` + `SHA256SUMS` + `sbom-url-shortener-0.14.0.json` (sha256
    verified against downloaded assets). Root-cause fixes in the workflow: step missing `run:` key
    (parse error → empty jobs), dangling artifact download, `action-gh-release@v1` node16 → pinned
    `@v3.0.3` (node24), non-root gate hardcoded `uid=1000` → alpine `adduser -S` yields **100**.
    `actionlint` (docker image `rhysd/actionlint`) now validates every workflow before push
    (only info-level SC2012 hints remain). `./mvnw verify` green (271 unit + 165 IT) + OWASP known
    CVE only `opentelemetry-api` MEDIUM (pre-existing). Gates 8.7 all PASS (+ self-tests),
    promtool/amtool/circle green. Evidence in `tasks/epic-8-dod.md`. — `resolved`

32. **Living Specifications (spec-driven development adoption, Phase 0+1 + Epic 9 ratchet)** —
    EARS requirements live in `package-info.java` living specs; `@TracesRequirement`
    (`core/annotation`) is the single traceability source (test-name conventions and
    `@DisplayName` are cosmetic), and `scripts/check-living-spec.sh` (+ `--self-test`) is a hard
    CI gate for `@spec-complete` components (≥ 90% traced, no dangling refs, untraced test classes
    must sit in the debt registry), also enforced at tag time in `release.yml`. Supporting tooling:
    `scripts/extract-requirements.sh` (JSON export), `scripts/generate-package-info.sh`
    (template). **Epic 9 (2026-09-13) walked the ratchet across all five remaining components —
    Cache (5 reqs, incl. the shape-versioned `url:v1:` key fix), Auth (4), Analytics (6, incl.
    the blue/green payload-compat rule as EARS), Persistence (8, incl. expand-only migrations;
    first component to exercise the 90% threshold as designed: 27/28 = 96% with REQ-PERSIST-003
    enforced at review instead) and UrlShortener (7)** — gate totals 34/35 across six components
    (97%): (a)~~`recordRateLimitExceeded()` had no production caller~~ **resolved 2026-09-13:
    wired at the single 429 egress (`UrlController.tooManyRequests`); live-proven via
    `/actuator/prometheus` with `RATE_LIMITER_LIMIT=1`**; (b) ~~only RateLimiting was
    spec-complete~~ **resolved 2026-09-13 (Epic 9): all six components spec-complete**; (c)
    ~~Spotless excludes `package-info.java`~~ **resolved 2026-09-13: ADR 0009 — the Javadoc
    structure inside a living-spec `package-info.java` is a machine-read contract, exempt from
    google-java-format by design; the gate's `--self-test` is the format-contract test; revisit
    trigger = two or more extractor false positives/negatives in practice.** — `resolved`

33. **Living-spec drift after ADR 0010 cookie auth** — the Auth component grew from 4 to **9 EARS
    requirements** (REQ-AUTH-005..009) to cover the additive HttpOnly-cookie transport: cookie-only
    authentication via the JWT filter fallback, Bearer-wins precedence, `GET /api/v1/auth/me`
    identity (+401 anonymous), `POST /api/v1/auth/logout` clearing both cookies (+204 idempotent),
    and refresh-via-cookie (new access JWT re-set; refresh cookie value unchanged per D5; 401 when
    neither body nor cookie). Gate totals are now **39/40 across six components (98%)** —
    REQ-PERSIST-003 remains the single above-threshold missing requirement. Tests: `AuthControllerTest`
    (WebMvc slice, 9) + new `AuthCookieIT` (Testcontainers end-to-end, 15 — root package
    `ca.tyny.urlshortener`, subject-named per convention). No new meters (metrics-frozen gate must
    stay green). — `resolved`

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
