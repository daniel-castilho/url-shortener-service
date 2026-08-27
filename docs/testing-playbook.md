# Testing Playbook

**Role:** Define how to design, run, diagnose and maintain tests for this Java 21 / Spring Boot 3.5.7
Hexagonal service (MongoDB + Redis, JWT, virtual threads).
**Audience:** Human contributors and AI software-engineering agents.
**Stack constraints:** JUnit 5 + Mockito + Testcontainers (MongoDB, Redis) + RestAssured. Do not add a
new test dependency without explicit human approval (`AGENTS.md`, rule 9).

**Sources of truth:**

1. `AGENTS.md`
2. `pom.xml`
3. `docs/coding-standards.md`
4. Colocated `*Test` / `*IT` classes
5. `README.md`

When this document disagrees with executable configuration, `pom.xml` wins. Fix the documentation in
the same change set.

---

## 1. Testing principles

1. Test behaviour and observable contracts, not implementation details.
2. Keep the fastest useful feedback loop at the lowest appropriate layer.
3. Put business-rule assertions in domain/application tests; use HTTP tests for routing, validation,
   authentication, authorization, serialization and status/body contracts.
4. Mock outbound boundaries, not domain state.
5. Every important rejection path is as valuable as its happy path.
6. A test must be deterministic, isolated and repeatable locally and in CI.
7. Never weaken, skip or delete a valid test merely to make the build green.
8. A green suite is necessary but not sufficient: coverage, static analysis and review must also be
   interpreted.

---

## 2. Test taxonomy

| Level                    | Naming                 | Runtime                                                   | Purpose                                                                          |
| ------------------------ | ---------------------- | --------------------------------------------------------- | -------------------------------------------------------------------------------- |
| **Domain unit**          | `*Test`                | Plain JUnit; no Spring, mocks or I/O                      | Entity/value-object invariants (`UrlTest`, `SubscriptionPlanTest`, `QuotaUsageTest`) |
| **Application unit**     | `*Test`                | JUnit + Mockito; no Spring context                        | Use-case orchestration, port interactions, rejection paths (`UrlShortenerServiceTest`, `QuotaServiceTest`) |
| **Spring context smoke** | `*Test`                | `@SpringBootTest`; no Docker                              | Application wiring and context startup                                            |
| **Adapter integration**  | `*IT` (rename from `*IntegrationTest`) | Testcontainers (Mongo + Redis)        | Real repository/cache/rate-limiter behaviour (`MongoUrlRepositoryIT`, `RedisUrlCacheIT`) |
| **HTTP end-to-end**      | `*IT`                  | `@SpringBootTest(RANDOM_PORT)` + RestAssured + Testcontainers | Full request flow, auth, validation, redirect contracts (`ShortenFlowIT`) |
| **Release smoke**        | Manual until automated | Compose/local runtime                                     | Small, high-value path before release/deployment                                 |

> **Convention (landed):** integration/E2E tests are named `*IT`, run by the **maven-failsafe-plugin**
> in `mvn verify`, and are excluded from the fast `mvn test` loop.

### Test placement

Mirror production packages under `src/test/java`:

```text
.../core/idgeneration/*Test.java
.../core/model/*Test.java
.../core/service/*Test.java
.../infra/adapter/input/rest/*Test.java
.../infra/adapter/output/persistence/*Test.java
.../infra/adapter/output/redis/*Test.java
.../(BusinessFlow)IT.java   # e.g. UrlShortenerIntegrationTest → ShortenFlowIT
```

Use one of these naming styles consistently inside a class:

```text
method_condition_expectedResult
shouldDescribeExpectedBehaviour
```

---

## 3. Commands and Maven lifecycle

### 3.1 Fast loop — no Docker

```bash
mvn test
```

This runs `*Test` / `*Tests` classes (domain/application unit and context smoke). It does **not**
require Docker.

### 3.2 Integration + E2E — Docker required

```bash
mvn test -Dtest='*IT'        # quick targeted run
# full gate: mvn verify      # failsafe runs *IT during integration-test
```

Docker must be available because `BaseIntegrationTest` starts MongoDB and Redis via Testcontainers.
On hosts running Docker Engine ≥ 29, docker-java needs `api.version = 1.44` in
`~/.docker-java.properties` (see `AGENTS.md` debt item 16).

### 3.3 Quality checks

```bash
mvn verify                   # JaCoCo + SpotBugs run as part of the default lifecycle
mvn jacoco:check             # coverage alone (LINE ≥ 60%, BRANCH ≥ 60%)
mvn spotbugs:check           # static analysis alone (effort Max, threshold High)
```

Both gates are wired into `pom.xml` at the `verify` phase; a build that violates either floor fails.

### 3.4 Full gate

`mvn verify` is the complete local gate: Surefire runs `*Test` during `test`,
maven-failsafe-plugin runs `*IT` during `integration-test`, and quality executions plus the packaged
jar complete at `verify`. `mvn clean package` alone remains a production **artifact build**, not a
test gate.

---

## 4. Mandatory patterns

| Area                  | Rule                                                                                                                                          |
| --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| **Domain tests**      | Instantiate real domain objects. No mocks, Spring context or infrastructure classes.                                                          |
| **Application tests** | Mock outbound domain ports (`UrlRepositoryPort`, `UrlCachePort`, `IdGeneratorPort`, `AnalyticsPort`, `RateLimiterPort`, `MetricsPort`, `UserRepositoryPort`). Keep application collaborators real when practical. |
| **Typed failures**    | Prefer domain exceptions (`UrlNotFoundException`, `AliasAlreadyExistsException`, `QuotaExceededException`). Reserve `IllegalArgumentException` for invalid arguments/preconditions. Assert exact messages only when part of the public contract. |
| **ID generation**     | Test random Base62 codes: length, alphabet, and **collision retry** (the generator must retry on a unique conflict, bounded — never loop).      |
| **Quota/atomicity**   | Test that concurrent flag/quota and click increments do not lose counts (use `$inc`; the current read-modify-write is a bug under test).     |
| **Redirect path**     | Verify a single DB hit (cache-aside), a `302` to the destination, and that analytics is **not** awaited synchronously.                          |
| **Security**          | Every new endpoint needs an explicit `SecurityConfig` rule plus at least one allowed and one rejected HTTP scenario. Test JWT default/weak secret, malformed/expired token, SSRF/private-IP destination, and rate-limit on the redirect path. |
| **Isolation**         | IT data must use unique codes/emails/names and must not depend on test order or leftovers. `BaseIntegrationTest` drops/flushes per method — use it, don't fight it. |
| **Boundaries**        | Run the rule-1 import check. Domain/application tests must not introduce infra/Spring/Mongo/Redis/JWT dependencies into the core.             |

### Boundary check

```bash
grep -rEn "^import com\.example\.urlshortener\.infra" src/main/java/com/example/urlshortener/core
grep -rlE "org\.springframework|org\.mongodb|org\.redisson|io\.jsonwebtoken|io\.micrometer" src/main/java/com/example/urlshortener/core
```

Expected result: no matches. Do not weaken the expression to hide a violation. If the architecture
policy intentionally changes, update `AGENTS.md`, coding standards and architecture doc together.

---

## 5. Current automated suite map

| Area                       | Main test files                                                                    | What is pinned                                                                 |
| -------------------------- | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| **ID generation**          | `CompositeUrlIdGeneratorTest` (Base62 / collision-retry tests as they land)         | Strategy dispatch (random vs. vanity); Base62 length/alphabet; bounded collision retry. Do not pin Hashids or a Redis counter. |
| **Domain models**          | `UrlTest`, `SubscriptionPlanTest`, `QuotaUsageTest`, `UserTest`                     | URL value-object validation, plan/quota rules, user factory & canCreateVanityUrls |
| **Shortening service**     | `UrlShortenerServiceTest`                                                            | shorten/getOriginalUrl flow, cache hit/miss, quota, vanity decision             |
| **Quota**                  | `QuotaServiceTest`                                                                    | Plan limits, min alias length, reset logic                                     |
| **User service**           | `UserServiceTest`                                                                    | register/login flow, email-uniqueness guard (currently imports infra — see debt) |
| **Auth HTTP**              | `AuthControllerTest`, demo `UrlControllerRateLimitingIntegrationTest`                | Auth endpoint contract; rate-limit behaviour                                   |
| **URL HTTP**               | `UrlControllerTest`                                                                  | Shorten/redirect contract, 400/404/409/429 error bodies                        |
| **Error handler**          | `GlobalExceptionHandlerTest`                                                          | Standard bodies for 400/401/403/404/409/500                                    |
| **Persistence**            | `MongoUrlRepositoryIntegrationTest`, `MongoUserRepositoryTest`                       | Real Mongo save/findById; `_id` uniqueness; duplicate original URL allowed     |
| **Cache / Redis**          | `RedisIntegrationTest`, `RedisUrlCacheTest`, `RedisRateLimiterAdapterTest`           | Redis cache get/put + bloom filter, rate limiter counting                       |
| **End-to-end**             | `UrlShortenerIntegrationTest` (→ `ShortenFlowIT` target)                              | Run against `RANDOM_PORT` + Testcontainers; full shorten → redirect flow        |
| **Context smoke**          | `ApplicationTests` (base)                                                             | Spring context start                                                           |

When behaviour covered by this map changes, extend the existing test where it remains cohesive; create
a new class when the new concern has a distinct fixture/lifecycle.

---

## 6. Traceability matrix

For milestone-sized work, record or verify this chain in the change description:

```text
requirement / risk
    → lowest useful test level
    → test class and scenario
    → command that executes it
    → CI step that gates it
```

| Requirement/risk                          | Lowest useful test | Representative class                              | Command / CI step        |
| ----------------------------------------- | ------------------ | ------------------------------------------------- | ------------------------ |
| Non-owner cannot use a vanity alias       | Application + E2E  | `UrlShortenerServiceTest`, `ShortenFlowIT`       | `test`; then `*IT`       |
| No URL dedup (duplicate original → new code) | Adapter         | `MongoUrlRepositoryIT`                            | Slice step               |
| Collision retry on random code            | Unit + Adapter     | `CompositeUrlIdGeneratorTest`, `MongoUrlRepositoryIT` | `test`; then `*IT`   |
| Malformed/expired JWT → standard 401       | E2E                | `GlobalExceptionHandlerTest`, `ShortenFlowIT`    | E2E step                 |
| Rate limit on the redirect path            | Adapter + E2E      | `RedisRateLimiterAdapterTest`, `ShortenFlowIT`   | Slice + E2E step         |
| Domain independent of infrastructure       | Static boundary    | Rule-1 grep                                       | Local; add to CI         |

A feature is not fully covered if its only test mocks the behaviour that carries the main risk.

---

## 7. Known coverage and process gaps

Keep this section honest. Move an item out only when an automated test/gate exists.

1. **Failsafe lifecycle — CLOSED.** `maven-failsafe-plugin` is wired; `*IT` classes run in
   `mvn verify` and are excluded from the fast `mvn test` loop.
2. **Actuator/Swagger exposure — CLOSED.** Actuator endpoints tiered: liveness/readiness/info public; health detail requires ADMIN; metrics/prometheus require ADMIN/METRICS_VIEWER; other actuator endpoints require ADMIN. Swagger conditionally enabled via `app.security.swagger.enabled` (default false). `SsrfProtectionIT` and `RedirectRateLimitIT` verify throttling headers.
3. **Rate-limit integration on the redirect path — CLOSED.** `RedirectRateLimitIT` proves the token bucket (capacity 3, PT1M window) throttles both valid and unknown codes with 429 + `Retry-After` + `RateLimit-*` headers; scope isolation (shorten budget untouched); concurrent burst admits exactly the configured capacity.
4. **Analytics/click persistence — CLOSED.** `ClickPipelineIT` proves redirect→persist+`$inc`,
   exact counts under burst, blank-code skip; `RedisClickEventQueueFailOpenTest` proves the
   fail-open policy (no throw + dropped metric when Redis is unreachable).
5. **No expiry (TTL) test** — add once `expiresAt`/TTL is implemented.
6. **Coverage/static-analysis gate — CLOSED.** JaCoCo 0.8.15 (LINE ≥ 60%, BRANCH ≥ 60%) and SpotBugs
   4.9.8.5 (effort Max, threshold High) are enforced at `mvn verify`, locally and in CI.
7. **CI workflow — CLOSED.** `.github/workflows/ci.yml` runs the boundary gate (+ self-test),
   `mvn test`, `mvn verify` (Testcontainers), and packaging on every push/PR.
8. **No performance/load baseline** — add a k6/Gatling scenario for the redirect path and record
   p50/p95/p99 as a regression tripwire.
9. **Boundary check automated in CI — CLOSED.** `scripts/check-boundaries.sh` runs locally/CI with a
   `--self-test` mode proving the gate detects planted violations.

---

## 8. Regression checklist

| Area              | Must verify                                                                                                        |
| ----------------- | ------------------------------------------------------------------------------------------------------------------ |
| **Shorten**       | URL validated (http/https); same original URL twice → two distinct Base62 codes (`200`); vanity alias reserved-word + format + uniqueness; quota enforced; `409` only on alias conflict; 429 on rate limit |
| **Redirect**      | `302` to the destination; cache-aside single DB hit; unknown code → 404; expired link → expiry (not 404); no sync analytics |
| **Auth**          | Register/login return a JWT; wrong password/unknown user don't leak existence; malformed/expired token → 401; no secret in logs |
| **Quota/atomic**  | Concurrent vanilla/vanity creates converge; counters use `$inc` (no lost increments)                                |
| **HTTP errors**   | Standard body + correct status for validation 400, auth 401, authorization 403, missing 404, conflict 409, generic 500 |
| **Security**      | Every endpoint has an explicit matcher; mutating routes never permit-all by default; SSRF/private-IP destinations rejected |
| **Cache**         | Bloom filter short-circuits non-existent codes; Redis outage degrades to DB (doesn't fail redirect)                |
| **Boundaries**    | Rule-1 grep has no matches; controllers call inbound use cases, not repositories                                     |

---

## 9. Release regression smoke

Run against disposable local services before a release tag. Prefer the automated `*IT` suite; use this
smoke to validate the assembled local runtime.

```bash
docker-compose up -d        # mongodb + redis
mvn spring-boot:run         # or java -jar target/*.jar
```

Prerequisites: disposable local environment only; never use production credentials; retain IDs/tokens
from prior steps rather than hardcoding real values. **Stop on first failure.**

| #  | Step                                                              | Expected                                                                    |
| -- | ----------------------------------------------------------------- | --------------------------------------------------------------------------- |
| 1  | Shorten a URL (anonymous)                                         | 200 with `shortUrl`; code is 7 chars from `[0-9A-Za-z]`; short URL resolves     |
| 1b | Shorten the **same** original URL again                            | 200 with a **distinct** code; both redirect                                      |
| 2  | Open the returned short URL                                        | `302` redirects to the original destination                                   |
| 3  | Open a non-existent short code                                     | Standard 404                                                                 |
| 4  | Register and authenticate a user                                   | 200 with JWT; no secret in logs                                               |
| 5  | Create a vanity alias as anonymous user                            | 400 (auth required)                                                          |
| 6  | Create a vanity alias as authenticated user                        | 200 with the custom code                                                     |
| 7  | Reuse an existing vanity alias                                     | 409                                                                          |
| 8  | Use a reserved-word alias                                          | 400                                                                          |
| 9  | Exceed the rate limit on shorten                                   | 429                                                                          |
| 10 | Call protected/actuator endpoints per the intended security policy | Matches `SecurityConfig`; no unexpected data exposure                         |

---

## 10. Flakiness, isolation and test-data policy

### Determinism
- Unit tests use deterministic values unless uniqueness is under test.
- ITs may use random codes/emails to avoid collision, but assertions must never depend on random order.
- Inject or wrap time/randomness when exact timestamps/ordering become business-relevant.
- Never depend on test execution order.

### Shared Testcontainers state
`BaseIntegrationTest` starts MongoDB and Redis as **static** containers (manually, not `@Container`)
so Spring can reuse its cached context, and `@DirtiesContext` / per-method `cleanup()` drops/flushes
the data. When extending it: use unique partition keys/emails; don't assume an empty DB unless the test
provisions/cleans its own data; don't enable parallel IT execution until isolation is proven.

### Concurrency and network tests
- Use bounded timeouts for `Future.get`, HTTP calls and polling.
- Always close `ExecutorService`, HTTP resources and application contexts.
- Avoid unbounded `Thread.sleep`; poll a condition with a deadline.
- A rerun may be used once to classify reproducibility, never as the fix. A flaky test must be corrected
  or tracked with an owner and reason — do not add blind retries.

### Failure diagnostics
Failure output should identify the resource/operation without printing passwords, raw JWTs, secret
keys or full token payloads.

---

## 11. Reading failures

| Class              | Signal                                             | First move                                                                         |
| ------------------ | -------------------------------------------------- | ---------------------------------------------------------------------------------- |
| **Logic**          | Domain/application assertion fails                 | Verify the invariant/use case before changing the expectation                      |
| **Contract**       | Wrong HTTP status/body                             | Check typed exception, handler/entry point and API contract                        |
| **Authorization**  | Unexpected 401/403                                 | Separate auth failure, role matcher and owner guard                                |
| **Boundary**       | Forbidden core import                              | Restore the port boundary; do not weaken the check                                 |
| **Compile**        | Mock/signature mismatch                            | Verify the port surface and update all callers/tests coherently                    |
| **MongoDB**        | Save/find error, duplicate key, missing index      | Compare entity annotations, IT provisioning and README schema; reproduce with the repository IT |
| **Redis**          | Cache/rate-limit/bloom failure                      | Check endpoint, keys, TTL and BaseIntegrationTest flush/init behaviour              |
| **Environment**    | Docker/port/Testcontainers problem                  | Verify prerequisites; distinguish infra failure from product failure               |
| **Discovery**      | `*IT` not picked up                                 | Failsafe only runs them in `mvn verify`; use that or the explicit `-Dtest='*IT'`    |

---

## 12. Analyzer reply format

```text
## Summary
Failing class / scenario
Category: Logic | Contract | Authorization | Boundary | Compile | MongoDB | Redis | Environment | Coverage | Static analysis | Dependency
Root cause: one concise sentence

## Fix plan
1. Smallest production-code fix
2. Regression test at the lowest useful level
3. Wider verification, if required

## Verify
mvn test
# when persistence/cache/security/HTTP changed:
mvn test -Dtest='*IT' -DfailIfNoTests=false   # or, once failsafe is wired: mvn verify
```

---

## 13. Do not

- Skip, delete or add `@Disabled` merely to green the build.
- Weaken an assertion without proving the previous contract was wrong.
- Catch and ignore failures in tests.
- Add blind retries for flaky tests.
- Use real production secrets/data.
- Log plaintext passwords, JWT secrets, full bearer tokens or destinations with credentials.
- Mock domain entities/value objects.
- Mock persistence/cache in an adapter integration test.
- Assert business rules only through controller/E2E tests when a lower-level test is possible.
- Treat coverage percentage as proof of correctness.
- Widen Mongo/Redis- or Spring-specific types into the core; use the domain port abstractions.
- Add a dependency or change the test lifecycle without explicit human approval and doc sync.

---

## 14. Done when

- [ ] The change has a happy path and at least one relevant rejection path.
- [ ] New domain/application behaviour has a colocated `*Test`.
- [ ] Authorization/quota is tested for every affected mutation.
- [ ] HTTP changes pin status and standard response body.
- [ ] Persistence/cache changes have an appropriate `*IT` against real Mongo/Redis.
- [ ] Concurrency-sensitive changes are tested with real atomic/conditional behaviour.
- [ ] Test data is isolated and execution-order independent.
- [ ] `mvn test` is green.
- [ ] `*IT` is green when persistence, cache, security or HTTP behaviour changed.
- [ ] The boundary grep has no new matches.
- [ ] Smoke instructions are updated when the assembled HTTP/runtime flow changed.
- [ ] `README.md`, `AGENTS.md` and this suite map/gap list are synchronized for milestone-sized work.

---

## 15. Document maintenance

- **Stable policy:** principles, taxonomy, mandatory patterns and "Do not". Change only when
  engineering policy changes.
- **Executable commands:** keep synchronized with `pom.xml`.
- **Current suite map:** update whenever test classes are added, renamed or removed.
- **Known gaps:** remove an item only when the corresponding automated test/gate exists; add newly
  discovered risks immediately.
- **Release smoke:** update whenever endpoint paths, authentication, or runtime security changes.

A milestone is not done if the tests changed but this document still describes the previous suite.
