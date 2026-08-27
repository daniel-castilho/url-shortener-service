# Coding Standards — Java / Spring Boot / Maven (URL Shortener Service)

Practical reference for solo and AI-assisted development. Goal: **consistency over time**, not ceremony. Living document — edit as the project evolves.

> **Scope:** this documents the **target** state we are building toward (Hexagonal Architecture with a
> framework-free `core/`). The codebase is mid-refactor; where the code does not yet conform, the gap
> is tracked in `AGENTS.md` → *Known Technical Debt* and must not be silently ignored.

**Relationship to other docs:**

| Doc               | Wins when                                            |
| ----------------- | ---------------------------------------------------- |
| `AGENTS.md`       | Project conventions, hard agent rules, release flow  |
| **This file**     | Day-to-day coding detail that does not fit in AGENTS |
| `docs/lessons.md` | Durable rules learned the hard way                   |

Where this file conflicts with `AGENTS.md`, **`AGENTS.md` wins**.

---

## 1. Naming

| Element                        | Convention                                       | Example                                                        |
| ------------------------------ | ------------------------------------------------ | -------------------------------------------------------------- |
| Packages                       | lowercase, feature-first per layer               | `ca.tyny.urlshortener.core.idgeneration`                   |
| Use-case interfaces            | `*UseCase`                                       | `ShortenUrlUseCase`, `GetUrlUseCase`                            |
| Use-case implementations       | `*Service`                                       | `UrlShortenerService`                                           |
| Domain models / value objects  | `PascalCase`; codes/ids as value objects         | `ShortUrl`, `Url`, `User`, `ClickEvent`, `SubscriptionPlan`     |
| Domain outbound ports          | `*Port`                                          | `UrlRepositoryPort`, `UrlCachePort`, `RateLimiterPort`          |
| Domain inbound ports           | `*UseCase`                                       | `ShortenUrlUseCase`, `GetUrlUseCase`                            |
| Persistence adapters           | `Mongo*Repository`                               | `MongoUrlRepository`, `MongoUserRepository`                     |
| Persistence entities           | `*Entity`                                        | `ShortUrlEntity`, `UserEntity`                                  |
| Persistence mappers            | `*Mapper`                                        | `ShortUrlMapper`                                                |
| Web controllers                | `*Controller`                                    | `UrlController`, `AuthController`                               |
| Web DTOs                       | `*Request` / `*Response`                         | `ShortenRequest`, `ShortenResponse`, `AuthResponse`             |
| ID strategies                  | `*IdStrategy` / `*IdGenerator`                   | `RandomUrlIdStrategy`, `VanityUrlIdStrategy`                    |
| Constants                      | `UPPER_SNAKE_CASE` (usually in the owning class) | `SHORT_URLS` (in `MongoCollections`)                             |
| Test classes                   | `*Test` (unit), `*IT` (integration/E2E)         | `UrlShortenerServiceTest`, `ShortenFlowIT` (convention: `*IT`)  |

Name for **what it is or does**, not the implementation: `UrlCachePort`, not `RedisUrlCacheV2`.
Use-case names speak **business language** (`shorten`, `getOriginalUrl`), not HTTP verbs or paths.

---

## 2. Package / folder structure (Hexagonal Architecture)

```
ca.tyny.urlshortener/
├── core/                          # 🧠 DOMAIN + USE CASES  (pure, zero framework)
│   ├── exception/                 # domain exceptions (UrlNotFound, AliasAlreadyExists, QuotaExceeded)
│   ├── idgeneration/              # UrlIdGenerator + *IdStrategy (composite)
│   ├── model/                     # entities + value objects (ShortUrl, Url, User, ClickEvent…)
│   ├── ports/
│   │   ├── incoming/              # inbound ports: *UseCase
│   │   └── outgoing/              # outbound ports: *Port (Repository, Cache, Metrics, Analytics, RateLimiter)
│   ├── service/                   # use-case orchestration (UrlShortenerService, QuotaService, UserService)
│   └── validation/                # ReservedWordsValidator
├── infra/                         # ⚙️ ADAPTERS  (framework & API only)
│   ├── adapter/
│   │   ├── input/rest/            # controllers, dto/{request,response}, advice (GlobalExceptionHandler)
│   │   └── output/
│   │       ├── analytics/         # click-event queue + batched worker (async, persisted)
│   │       ├── persistence/       # Mongo*Repository, *Entity, *Mapper, config (MongoCollections)
│   │       └── redis/             # Redis cache, bloom filter, rate limiter
│   ├── config/                    # beans, security, Undertow, OpenAPI, native hints
│   ├── observability/             # Micrometer metrics service & adapter
│   └── security/                  # JWT filter, token provider, UserDetailsService
└── Application.java               # Spring Boot entry point (ca.tyny.urlshortener.Application)
```

**Framework boundary (enforce with the grep in `AGENTS.md` rule 1):**

| Layer    | Framework / external imports                                                       |
| -------- | ---------------------------------------------------------------------------------- |
| `core/`  | **Pure Java.** No Spring annotations, no `infra.*`, no `org.springframework.*`, no `org.mongodb`, no `org.redisson`, no `io.jsonwebtoken`, no `io.micrometer`. Depends only on its own models, ports and service classes. |
| `config/`| Registers domain beans (e.g. `ServiceConfig`), `@ConfigurationProperties`, security, cache. |
| `infra/` | Full stack allowed: Spring Web, Spring Security, Spring Data MongoDB/Redis, Redisson, jjwt, Caffeine, Micrometer. |

Web controllers are **thin**: they bind an inbound `*UseCase` and DTOs only. No business rules, no
repository calls from controllers.

---

## 3. Clean Architecture & SOLID

- **Dependency rule (DIP).** Inject **ports, never concretions**. Services depend on `core/`
  outbound ports; `infra/` adapters implement those ports. A service never imports a concrete
  adapter or a framework bean. (`core/service/UserService` currently violates this — see
  `AGENTS.md` debt item 1; fix before adding new code there.)
- **Interface segregation (ISP).** Prefer narrow interfaces over wide ones. Split `*ReadRepository`
  / `*WriteRepository` when a use case needs only a subset of the surface; the adapter satisfies the
  union.
- **Open/closed (OCP).** Behaviour that varies by type goes behind a strategy/factory. The
  `UrlIdGenerationStrategy` family (`RandomUrlIdStrategy`, `VanityUrlIdStrategy`) behind
  `CompositeUrlIdGenerator` is the model. Do **not** add `if/else` chains on plan or alias shape to
  introduce variants.
- **Single responsibility (SRP) & clean code.** One responsibility per service; small methods;
  extract private helpers. Methods named as **verbs** (`shorten`, `getOriginalUrl`); booleans as
  predicates. No abbreviations beyond established conventions.
- **Rich domain model.** Business invariants live in the **domain** (`Url` validates its format,
  `User.canCreateVanityUrls()` owns quota policy), not in services and never in `*Entity` documents.
  Avoid anemic models.
- **Tell, don't ask.** `user.canCreateVanityUrls()` instead of a chain of getters in the service.
- **No magic numbers / strings.** Use named constants or enums; never scatter literals (e.g. the
  reserved-words set lives in `ReservedWordsValidator`).
- **Exceptions over error codes.** Business-rule failures throw domain exceptions; fail fast with a
  clear message. No silent error flags or `null`-as-failure.

---

## 4. Design patterns (house style)

House patterns are the ones already established in the codebase — **reuse them instead of inventing
variants**. Prefer the pattern that matches existing code; if none fits, ask the human.

| Pattern                          | Where it lives                                  | Use it when                                                                 | Avoid / instead                                                        |
| -------------------------------- | ----------------------------------------------- | --------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| **Strategy**                     | `UrlIdGenerationStrategy` family                | Behaviour varies by type (random code vs. vanity alias) and new variants may come | `if/else` on alias shape / plan                                        |
| **Factory / Composite**          | `CompositeUrlIdGenerator`                       | Resolving a strategy from a condition; centralize the mapping                | Stringly-typed dispatch, lookups scattered across services             |
| **Adapter (Ports & Adapters)**   | `MongoUrlRepository`, `RedisUrlCache`, `RedisRateLimiterAdapter` | Any technical boundary (Mongo, Redis, JWT, analytics) — implement the domain port | Framework calls outside `infra/`                                       |
| **Facade (use-case service)**    | `*Service` in `core/service`                    | Orchestrating a use case across domain ports; keep services thin             | Putting business rules in the service                                  |
| **Repository**                   | `*RepositoryPort` domain ports                  | Data-access abstraction; lookups driven by indexes                           | Raw Mongo/Redis calls in services or controllers                       |
| **Mapper (anti-corruption)**     | `ShortUrlMapper`                                | Translating between domain ↔ persistence at the boundary                    | Silently sharing one model across layers                               |
| **Circuit Breaker**              | Resilience4j on repos / rate limiter            | Fault tolerance against a downstream dependency                             | Wrapping every call; keep the breaker at the dependency boundary       |
| **Value Object**                 | `Url`, `ShortUrl` (record)                      | Immutable, self-validating domain types                                     | Mutable data classes in the domain                                      |

**Cross-cutting (declarative).** Prefer framework annotations over hand-rolled wrappers:

- Circuit breakers via `@CircuitBreaker` on the adapter (never in the domain).
- JWT auth as a single filter + explicit `SecurityConfig` rules — never per-controller auth code.
- Rate limiting as the `RateLimiterPort` + filter — never a hand-rolled counter in a controller.

**Not now, but planned (do not build yet):**

- **Domain events** for cross-aggregate consistency / audit once workflows grow.

**Anti-patterns to avoid:**

- **Service Locator** — no `ApplicationContext.getBean()` in services/controllers; inject at the
  composition root.
- **God classes / anemic domain models.**
- **Stringly-typed dispatch** (`switch` on strings/ids) where a Strategy/Factory fits.
- **Direct instantiation of concrete adapters in services.**
- **Inline fully-qualified class names** (e.g. `ca.tyny.urlshortener.core.X`) — use `import`.

---

## 5. Java 21 language features

- **Records** for immutable value objects and DTOs (`ShortUrl`, `Url`, `*Request` / `*Response`).
  Prefer records when the shape is fixed; use explicit classes only where behaviour matters.
- **Sealed interfaces** for closed hierarchies (`SubscriptionPlan`, ID strategies) — prevents
  accidental extension of a deliberately closed set.
- **Pattern matching for `switch`** on sealed/enum types instead of `instanceof` chains.
- **Text blocks** for multi-line literals (SQL, JSON samples, large strings).
- **Immutability.** `final` fields; no public setters on value objects; defensive copies where a
  mutable object crosses a boundary.
- **Null discipline.** Never return `null` from `core/`. Use `Optional` for possibly-absent results,
  `Objects.requireNonNull` for preconditions, and validate at the input boundary. Never use `Optional`
  as a method parameter or field (the domain currently returns `Optional<ShortUrl>` from the port,
  which is acceptable for a repository — keep method-parameter optional usage out).
- **Streams.** Prefer `Stream`/`collect` over manual loops where clearer. No stateful lambdas, no
  side effects inside stream pipelines.
- **Virtual threads** are enabled globally under Undertow — write blocking-but-simple code in
  services (no thread-pool over-engineering for I/O).

---

## 6. Spring Boot conventions

- **Java 21 / Spring Boot 3.5.7.** `core/` is annotation-free: no `@Component`/`@Service`, no
  Lombok. Domain/use-case classes use explicit constructors; beans are registered via `@Bean` in
  `infra/config` (see `ServiceConfig`). Lombok is allowed in `infra/` adapters only.
- **Constructor injection only** (`@RequiredArgsConstructor` for Lombok-managed fields in `infra/`,
  or an explicit constructor). Never field injection.
- **Typed configuration.** Use `@ConfigurationProperties` (`app.prop.*`, `rate-limiter.*` are the
  pattern). Do **not** scatter new `@Value` fields for config.
- **Secrets via env vars.** `MONGODB_URI`, `REDIS_HOST`/`REDIS_PORT`, `APP_JWT_SECRET`. The bundled
  defaults in `application.yaml` are **dev-only**; the JWT provider must warn when the default secret
  is used. Never commit real secrets. Short codes do **not** use a salt (`SHORTENER_SALT` is not part
  of the identity model).
- **`@Transactional`** only in services when a use case spans multiple writes. Keep it short; never
  on adapters — and never over Mongo+Redis, which are separate systems (see `data-model-decisions`).
- **Caching.** Prefer `@Cacheable` on application services, or the `UrlCachePort` for the explicit
  L1/L2 + bloom flow. TTL logic stays in the adapter/config, never in the domain.
- **Analytics pipeline (applied).** The redirect calls `AnalyticsPort.track()` fire-and-forget and
  must never block or throw on analytics failure (fail-open; drops are counted). Events go onto a
  durable, bounded Redis Stream (`XADD MAXLEN ~`) and are drained by `ClickBatchWorker`, which
  bulk-inserts to `click_events` and increments `clickCount` with **one `$inc` per unique code per
  batch** — never read-modify-write anywhere (quota counters included). Delivery is at-least-once
  without an idempotency key; the worker is self-healing if the stream/group disappears.
- **Rate limiting (applied).** The hot path (`GET /{id}`) is rate-limited per IP via a Redis
  token-bucket (Rule 5): independent scopes (SHORTEN/REDIRECT), Redis TIME-driven atomic Lua
  script, continuous refill, TTL-based key reclamation. Trusted-proxy CIDR IP resolution;
  fail-open policy (throttling never blocks the endpoint it protects). 429 responses emit
  `Retry-After` + `RateLimit-Limit/Remaining/Reset` headers. Scope isolation: exhausting one
  scope never affects the other.
- **URL validation & SSRF protection (applied).** Destination URLs validated by `UrlValidator`:
  HTTPS enforced by default (`app.url.allow-http=false`), host syntax validated, DNS resolution
  with caching (`app.url.dns-cache-ttl-seconds`), private/internal/metadata IP blocking
  (RFC1918, loopback, link-local, cloud metadata), userinfo rejection. Configurable via
  `app.url.*` properties. Extensible via `DestinationValidatorPort` for reputation checks
  (Safe Browsing, VirusTotal, etc.). Fail-closed on DNS failure (secure default).
- **Actuator & Swagger security (applied).** Actuator endpoints tiered: liveness/readiness/info
  public; health detail requires ADMIN; metrics/prometheus require ADMIN or METRICS_VIEWER;
  other actuator endpoints require ADMIN. Swagger conditionally enabled via
  `app.security.swagger.enabled` (default false). Health detail defaults to `when-authorized`.
  Swagger conditionally loaded via `@ConditionalOnProperty(name="app.security.swagger.enabled")`.
- **Observability (applied).** Business timers (`id.generation.duration`, `url.retrieval.duration`)
  are recorded behind `MetricsPort` only — `core/` never imports Micrometer/OTel. Tracing uses Spring
  Boot auto-instrumentation (`micrometer-tracing-bridge-otel`) exported via OTLP/HTTP; **no manual
  span code in `core/`** — HTTP spans come from auto-instrumentation, error traces are always kept by
  collector tail-sampling (`deploy/otel/otel-collector-config.yml`). Logback patterns include
  `%X{traceId}` `%X{spanId}`. Metrics must be registered once (one adapter owns each meter) and
  recorded exactly once per business event.
- **Bean scoping.** Default singleton; services are stateless — no per-request mutable fields.
  The analytics event queue is durable (Redis Stream), not in-memory.
- **DTOs for every external input/output.** Never expose domain entities through the API.
- **Validation.** `spring-boot-starter-validation` + `jakarta.validation.constraints.*` on request
  DTOs, `@Valid` in controllers. Centralized errors (see § 8).

---

## 7. Formatting & tooling

- 4-space indent, no tabs.
- Follow the layout of the layer you are editing; Maven/Spring Boot convention. No formatter config is
  committed — keep style consistent manually.
- Imports: keep them clean and ordered (IDE auto-organize); **no wildcard imports** in new code.
- Run `mvn test` (fast loop) before commit; run `mvn verify` after significant changes — it is the
  full gate (unit + IT/E2E + JaCoCo coverage floor + SpotBugs + jar).
- Quality gates: JaCoCo 0.8.15 (LINE ≥ 60%, BRANCH ≥ 60%) and SpotBugs 4.9.8.5 (effort Max,
  threshold High) fail the build at `verify`. Do not weaken thresholds or add suppressions without
  explicit human approval.
- There is **no Maven wrapper** (`./mvnw`) — use `mvn`. Integration tests follow the `*IT` +
  failsafe convention.

---

## 8. Errors & logging

- **Centralized error handling.** `GlobalExceptionHandler` (`infra/adapter/input/rest/advice`) maps
  exceptions to `ErrorResponse` / `ValidationErrorResponse`:
  - `MethodArgumentNotValidException` / `MethodArgumentTypeMismatchException` → `400` Validation Error
  - `IllegalArgumentException` → `400` Invalid Request (business-rule violations)
  - `QuotaExceededException` → `402`/`429` Quota Exceeded
  - `AliasAlreadyExistsException` → `409` Conflict
  - `UrlNotFoundException` → `404` Not Found
  - anything else → `500` Internal Server Error (logged as `error` with stack trace)
- Never empty `catch`. Log with context (id, operation, resource), not only `"error occurred"`.
- Never log passwords, JWT secrets, full bearer tokens, or destinations containing credentials.
- Logging: SLF4J (`LoggerFactory.getLogger(...)`). Levels: `error` — needs attention; `warn` —
  handled anomaly; `info` — significant lifecycle; `debug` — diagnostic detail.
- English only in log messages.

---

## 9. Persistence (MongoDB) & cache (Redis)

- **All MongoDB access lives in `infra/adapter/output/persistence`.** Adapters implement the domain
  port; repositories are thin `MongoTemplate` queries; entities (`*Entity`) are persistence records;
  `*Mapper` translates explicitly between domain and entity — no silent casts.
- **Indexes**: there is **no unique index on `originalUrl`** (no URL dedup; Rule 3). Add a
  **non-unique** index only if you query by URL, and store a SHA-256 `urlHash`. Keep `_id` unique for
  code resolution. MongoDB TTL index `expires_at` drives link expiry (later epic). Dropping any leftover
  unique-on-URL index is story I4, not a product-rule change.
- **Atomic counters.** Quota and click counters must be incremented with **`$inc`** (atomic), never
  read-modify-write — `QuotaService.incrementVanityUrlUsage` currently does `set(get()+1)` and loses
  increments under concurrency (debt item 14). Fix to `$inc`.
- **Business invariants stay in the domain model**, never in `*Entity` documents.
- **No embedded collections that can diverge** — single source of truth per relationship (see
  `data-model-decisions`).
- **Redis** (`StringRedisTemplate` + Redisson): cache only. TTLs set in the cache adapter, with jitter
  to avoid stampede; the bloom filter short-circuits non-existent codes. Never store secrets. The
  cache is **best-effort** — a Redis outage degrades to DB lookups, never fails the redirect.
- Schema/index creation: `auto-index-creation` is convenient but non-deterministic — replace with
  **versioned migrations** (debt item 10). Until then, keep the README/IT provisioning in sync.

---

## 10. Testing

| Kind                | Tooling                       | Notes                                                        |
| ------------------- | ----------------------------- | ------------------------------------------------------------- |
| Domain unit         | JUnit 5                       | Pure entities/value objects, **no mocks**                    |
| Application unit    | JUnit 5 + Mockito             | Mock the **domain ports only**; happy path + rejection       |
| Adapter integration | Testcontainers (Mongo + Redis) | Real MongoDB/Redis adapters (`*IT`)                          |
| End-to-end          | RestAssured + Testcontainers  | `*IT` on `RANDOM_PORT`; run explicitly `-Dtest='*IT'`        |

- Method/test names: `method_condition_expectedResult` or descriptive `should ...`.
- Fast loop: `mvn test` (no Docker). Full gate: `mvn verify` (once failsafe is wired — see testing
  playbook). Identity tests must pin Base62 length/alphabet, collision retry, duplicate URL → new
  code, and namespace isolation — never Hashids or unique-on-URL as expected behaviour.
- After significant changes: `mvn clean package` + smoke against `docker-compose up -d`.
- Full guidance: `docs/testing-playbook.md`.

---

## 11. Documentation

- Javadoc where the purpose is not obvious from the name; skip trivial getters.
- Comment **why**, not what.
- English only for code, comments, commits, and docs (migrating the Portuguese prose in
  `MONGODB_ARCHITECTURE.md`).

### Doc sync

After milestone-sized work (new feature area, public behaviour change, debt resolution), the same
change set — or an immediate follow-up commit — MUST update:

- `README.md` → "Current State" / "Roadmap"
- `AGENTS.md` → "Known technical debt" (add or clear)
- This file and `docs/testing-playbook.md` / `docs/data-model-decisions.md` when affected.

Do **not** claim work DONE while `README.md` or `AGENTS.md` still describes a previous milestone as
current. The hard rule lives in `AGENTS.md` (rule 10).

---

## 12. Version control

- Imperative commit subject: `feat(urls): switch to random base62 codes`.
- Conventional prefixes: `feat`, `fix`, `refactor`, `docs`, `test`, `perf`, `chore`.
- Small, focused commits.
- Do **not** push unless the human asks.
- CHANGELOG: every milestone-sized change set adds an entry under the next version or
  `Unreleased` (Keep a Changelog format; sections `Added` / `Changed` / `Fixed` / `Removed` /
  `Documentation`). Promote the `Unreleased` block when tagging.
- Annotated tags only at milestones with DoD met (`v0.X.0` — see `AGENTS.md`).

---

## 13. Security

- **Authorization is centralized in `SecurityConfig`.** Every new endpoint gets an explicit rule.
  Never default a mutating route to permit-all. `/actuator/**` and `/swagger-ui/**` must be restricted
  in production (debt item 9).
- **Passwords:** **BCrypt** via a single `PasswordEncoder` bean. Swap hashing by changing that bean
  only. Never store plaintext.
- **JWT:** `jjwt`; `app.jwt.secret` in `application.yaml` is a **dev-only example** — production must
  override via `APP_JWT_SECRET`. The provider validates the minimum length at startup and warns on the
  default. Never commit real secrets.
- **Server-side validation always** (`@Valid`); never trust the client alone.
- **URL validation & SSRF:** the `Url` value object currently only enforces `http(s)://`. Strengthen it
  to enforce HTTPS by default, validate the host, and **block internal/private/link-local IPs**
  (`127.0.0.0/8`, `10/8`, `172.16/12`, `192.168/16`, `169.254.169.254`) — debt item 8.
- **Rate limiting:** token-bucket over Redis, per-IP, configurable (`rate-limiter.limit` /
  `.window`). Currently only on the shorten endpoint; **must also cover the redirect path** (debt
  item 6). Fails open (does not block) when Redis is down.
- **No open redirect for invalid/blocked destinations** — validate before issuing `302`.

---

## Quick pre-commit checklist

- [ ] No wildcard imports added; 4-space indent; layout follows the layer
- [ ] `core/` free of `infra.*`, Spring, MongoDB, Redisson, jjwt, Micrometer imports
- [ ] Injects ports only (DIP); no concrete adapter imported by a service
- [ ] No `if/else` on plan/alias shape; strategy used when behaviour varies by type
- [ ] Used a house pattern (Strategy/Factory/Adapter/Facade/Repository/Mapper/CircuitBreaker) instead of raw SDK calls or stringly-typed dispatch
- [ ] No Service Locator (`ApplicationContext.getBean()`); wiring stays in the composition root
- [ ] Invariant placed in the domain, not in the service or `*Entity`
- [ ] Records/sealed/immutable where it fits; no `null` returned from the core; no `Optional` params
- [ ] No magic numbers/strings; named constants or enums
- [ ] DTOs for external I/O; controller is thin
- [ ] New endpoint has an explicit `SecurityConfig` rule; mutating routes never permit-all by default
- [ ] Counters use `$inc` (atomic); no read-modify-write under concurrency
- [ ] Unit test for new domain/application behaviour; existing suite not weakened
- [ ] No secrets in the diff; log messages in English with context
- [ ] `mvn test` green (and `*IT` green when persistence/cache/security/HTTP changed)
- [ ] Commit message says what and why
