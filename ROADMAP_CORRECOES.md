# Correction roadmap — url-shortener-service → "high-excellence bar"

**Based on:** `AUDITORIA_URL_SHORTENER.md` (code evidence) + owner decisions:
- ✅ **ID strategy → migrate to random base62** (move away from counter + Hashids).
- ✅ **URL dedup → NO** (drop the unique `originalUrl` index).
- 🎯 **Deliverable:** prioritized correction plan (roadmap), with order, effort and acceptance criteria.

> **Prioritization premise:** first what breaks correctness/security (P0), then the structural
> ID/dedup change (the two decisions), then promised features that do not exist (analytics/TTL),
> then architecture integrity, then operations/doc/tests.

---

## Phase 0 — Quick correctness/security fixes (low risk, high value)

### T0.1 — Duplicated metric `urls.shortened.total` (+2/request)  ·  Effort: **S**
- **Problem:** `recordUrlShortened()` is called in `UrlShortenerService` (via `MetricsPort`) and in
  `UrlController` (via `MetricsService`), both on the same Micrometer `Counter` → each shorten
  counts 2.
- **Action:** keep only **one** call (I recommend removing the one in `UrlShortenerService`/
  `MicrometerMetricsAdapter` and keeping the controller, which already measures latency). Since
  `MetricsPort` is still used for cache hit/miss/bloom, keep the interface, just remove the
  `recordUrlShortened` method from it (or stop calling it).
- **Acceptance:** a test proves 1 shorten → `urls.shortened.total` = +1.

### T0.2 — Rate limit on redirect (`GET /{id}`)  ·  Effort: **S**
- **Problem:** throttling only exists on `POST`. The redirect path (hot and enumerable) has no limit.
- **Action:** apply `rateLimiter.isAllowed(clientIp)` on the `redirect` method too, with a
  configurable policy (can be higher or separate from create).
- **Acceptance:** firing >N requests at the same `/{id}` from the same IP makes the service respond
  `429`; integration tests cover the redirect path.

### T0.3 — Bloom filter: actually short-circuit the database access  ·  Effort: **S/M**
- **Problem:** `urlCache.get()` returns `null` for IDs outside the bloom, but `getOriginalUrl`
  queries Mongo anyway → the claim "invalid IDs don't reach the database" is false.
- **Action:** in `getOriginalUrl`, treat the cache result as "not found" without hitting the DB when
  the bloom rejects. Two options:
  - A) `UrlCachePort.get` returns a type that distinguishes "does not exist (bloom)" from "not in
    cache (miss)" — then the service does not query the DB on bloom-negative (use an
    `Optional`/sentinel).
  - B) The service checks `bloomFilter.contains` via a new port operation and returns 404 directly.
- **Acceptance:** a sequence of `GET /missing-id` N times does not generate N Mongo queries
  (verifiable via logs/DB metrics).

### T0.4 — Remove dead metrics (`id.generation.duration`, `url.retrieval.duration`)  ·  Effort: **S**
- **Action:** either wire the timers (time `generateId` and the lookup) or remove them from
  `MetricsService` so no metrics that never appear are kept.
- **Acceptance:** there are no metrics defined and never recorded; or every registered metric is
  written.

---

## Phase 1 — Structural migration (the 2 decisions): random base62 + no dedup  ·  Effort: **L**  ·  *coupled, do together*

Covers the owner decisions and is the change that **reverses** the repo's current design.

### T1.1 — New ID generation: random base62 (CSPRNG)  ·  Effort: **M**
- **Goal:** replace `RangeAwareIdGenerator` (Redis counter + Hashids) with a purely random generator.
- **Design:**
  - base62 alphabet: `0-9 A-Z a-z`.
  - Length: **7** (configurable via `app.shortener.code-length`).
  - **Cryptographically secure** random source: `java.security.SecureRandom` (not `Random`).
  - Implement as a `RandomUrlIdStrategy` that produces the code **without** depending on Redis.
- **Collision:** probabilistic. Use Mongo's unique `_id` as the guard: on persistence, if a
  `DuplicateKeyException` occurs → **retry** (generate a new code) up to N attempts; enforce a
  retry cap so it never loops forever.
  - Adjust `MongoUrlRepository.save` to distinguish "code collision" (retry for generated codes)
    from "vanity alias already exists" (409).
- **Cleanup:** remove the `org.hashids` dependency, the `app.shortener.salt` config, and
  `RangeAwareIdGenerator`/`IdGeneratorPort` if no longer used.
- **Acceptance:** IDs are 7-char base62, verified as random; a collision test (repeat N times)
  passes; no Redis calls on the generation path.

### T1.2 — Namespace isolation (generated code × vanity alias)  ·  Effort: **S**
- **Problem:** generated codes and user aliases share the same `_id`/collection.
- **Action:**
  - Keep `ReservedWordsValidator` (blocks route words).
  - Additionally, in the generator, **check that the code does not collide with reserved words**
    (cheap) and ensure the user alias, on creation, passes the `existsById` check.
  - Recommended (design): generate codes of **exactly 7** and require aliases to be **≠ 7** or
    belong to a separate set — document the convention.
- **Acceptance:** no generated code coincides with a reserved word; a test tries to create an alias
  with a route value and it is rejected.

### T1.3 — Remove dedup: drop the unique `originalUrl` index  ·  Effort: **S**
- **Problem:** `@Indexed(unique = true)` on `originalUrl` forces 1 short link per URL and, in the
  current flow, a duplicated URL becomes `AliasAlreadyExistsException` (misleading 409).
- **Action:**
  - Remove `unique` from `originalUrl` (adjust `ShortUrlEntity` + create a migration to drop the
    index in Mongo).
  - **Optional (future):** if you later want to query by URL (analytics/dedup), add a **non-unique**
    index and a `urlHash` (SHA-256) field — store the hash from now on so you don't need to migrate
    later (zero cost now).
  - Adjust semantics: `409` now means "custom alias already exists", **not** "duplicated URL".
    Duplicated URLs are now allowed and produce a new link.
- **Acceptance:** shortening the same URL twice creates **2** distinct codes, both redirecting
  correctly; there is no longer a unique on `originalUrl`.

### T1.4 — Update ID-migration docs/config  ·  Effort: **S**  ·  **done (V2)**
- Remove references to "Counter-Based Shuffle", "Hashids", "Zero Collision via counter" and
  `SHORTENER_SALT` as the current design from `README.md`/docs.
- Fix the strategy in `MONGODB_ARCHITECTURE.md`.
- **Acceptance:** docs describe the locked strategy (random base62 + collision retry).

---

## Phase 2 — Promised features that do not exist (real value)

### T2.1 — Real analytics: persist clicks + counter  ·  Effort: **L**
- **Problem:** `ClickBatchWorker` only logs; events are dropped; there is no `click_count`.
- **Action:**
  - Persist `ClickEvent` into a **new collection** (`click_events`) in the worker (batch insert),
    **outside** the redirect path.
  - Add `clickCount` to `ShortUrl` and **increment atomically** (Mongo `$inc`) in the worker.
  - Consider a **durable queue** (Redis Stream/Kafka) instead of the in-memory queue (which drops
    when full).
- **Acceptance:** N clicks on a link → `click_events` has N records and `clickCount` = N; the
  redirect **does not** block waiting for the write.

### T2.2 — Link expiration (TTL)  ·  Effort: **M**
- **Action:** add `expiresAt` to `ShortUrl`; optional field on creation; **TTL index** in Mongo;
  `getOriginalUrl` must validate expiration (return an error/expired link, not redirect); optional
  purge job.
- **Acceptance:** a link with `expiresAt` in the past does not redirect and responds with the proper
  status; a test covers expired links.

---

## Phase 3 — Architecture integrity

### T3.1 — Fix Dependency Inversion in `core/service/UserService`  ·  Effort: **M**
- **Problem:** it imports `MongoUserRepository`, `JwtTokenProvider` and infra DTOs (`AuthResponse`,
  `LoginRequest`, `RegisterRequest`).
- **Action:** make `UserService` depend on **ports** (`UserRepositoryPort`, a token/JWT abstraction)
  and work with **domain** objects (`User`, commands); move the DTO→domain mapping to the adapter
  layer (`AuthController`).
- **Acceptance:** the `core` layer does **not** import classes from `infra.*`; a `grep` for
  `import ca.tyny.urlshortener.infra` inside `core/` returns empty (or only types you decide to
  allow).

### T3.2 — `core` layer without Spring annotations (or document the choice)  ·  Effort: **S/M**
- **Action:** move `@Component`/`@Service`/`@RequiredArgsConstructor` out of `core` into config/bean
  registration in the infra layer, keeping `core` pure; or, if you prefer to keep them,
  **explicitly document** that core uses the annotations even though it does not depend on Spring at runtime.
- **Acceptance:** either `core` has no Spring imports/annotations, or the doc declares and justifies
  it.

### T3.3 — Drop inline names (FQCN)  ·  Effort: **S**
- `UrlShortenerService` references `ca.tyny.urlshortener.core.validation.ReservedWordsValidator`
  without an `import`; same for `GlobalExceptionHandler`/`UrlController`.
- **Acceptance:** no inline fully-qualified names (use `import`).

---

## Phase 4 — Security hardening

### T4.1 — URL validation and destination blocking  ·  Effort: **M**
- Strengthen the `Url` value object: actually validate the host, prefer/block `http://` via config,
  **block private/metadata IPs** (169.254.0.0/16, 127.0.0.0/8, RFC1918...) to mitigate SSRF, and
  integrate destination **blocklist/reputation** (hook for Safe Browsing, VirusTotal, PhishTank) —
  at least as an extension point.
- **Acceptance:** URLs with malformed/internal hosts are rejected; there is a documented hook for
  reputation checks.

### T4.2 — Restrict operational exposure  ·  Effort: **S**
- `/actuator/**` and Swagger `permitAll` + `health.show-details: always` leak information.
- **Action:** in production profile, restrict actuator/health (auth or internal network) and reduce
  detail; limit Swagger exposure.
- **Acceptance:** no sensitive info on public endpoints in prod.

---

## Phase 5 — Operations, docs, quality standardization

### T5.1 — Versioned schema/index migration  ·  Effort: **M**
- Replace `auto-index-creation: true` with a **migration framework** (e.g. mongock/fluent
  migrations) and manage indexes in the deploy step. Needed especially to **drop** the unique
  `originalUrl` index (T1.3).
- **Acceptance:** versioned migrations, applied explicitly and reproducibly.

### T5.2 — Align documentation with reality  ·  Effort: **S/M**  ·  **done (V2)**
- Remove/fix links to `AUDIT_FINAL_REPORT.md`, `VALIDATION_CHECKLIST.md`, `LESSONS_LEARNED.md`
  (nonexistent).
- Remove **Cassandra** references (it is MongoDB).
- **Remove the self-assigned scores** ("9.2/10", "Clean Architecture 10/10", "Production Ready") or
  replace them with verifiable targets.
- Remove build artifacts (`build.log`, `build_out.txt`) and add to `.gitignore`.
- Product docs describe the **locked identity model** (Base62, no dedup, namespace); Hashids/
  unique-on-URL are not the product design.
- **Acceptance:** docs describe the locked contract; code gaps stay in the debt matrix (`AGENTS.md`).
### T5.3 — Fix the native build bug  ·  Effort: **S** — **DONE**

- `pom.xml` `native` `mainClass` fixed to `ca.tyny.urlshortener.Application`.
- **Acceptance:** `mvn clean package -Pnative` resolves the main class correctly.

### T5.4 — Observability, TLS and on-prem deploy  ·  Effort: **M/L**
- Add **tracing (OpenTelemetry)** and alerts; define **SLOs** and a **load harness** (k6/JMeter) to
  record real p50/p95/p99.
- Document **TLS termination** (reverse proxy) and bare-metal deploy (systemd/manual); optionally
  include the app in compose.
- **Acceptance:** there is a real latency/throughput measurement and a documented deploy+TLS route.

---

## Phase 6 — Tests and CI

### T6.1 — Fill coverage gaps  ·  Effort: **M**
- **Read path** with the full cache stack (L1/L2/Bloom) and the "bloom-negative" behaviour.
- **Analytics worker** and click persistence.
- **Concurrency**: vanity-alias race and **atomic** quota increment.
- **Security**: JWT with default secret, open redirect, invalid URL, SSRF/private IP, rate limit on
  redirect.
- **Code collision** in the new base62 generator.

### T6.2 — CI with real verification  ·  Effort: **S/M**
- Run `mvn verify` with Testcontainers in CI (actually validate "all tests pass").

---

## Recommended execution order

```
Phase 0 (S)  →  Phase 1 (L, decisions)  →  Phase 2 (L, features)  →  Phase 3 (M)  →  Phase 4 (M)  →  Phase 5 (M/L)  →  Phase 6 (M)
```
- **Phase 0** and **Phase 1** first (correctness + ID/dedup decisions) — unlock the rest.
- **Phase 1** and **Phase 2** are the most impactful (change behaviour and add value).
- **Phase 5.2 (doc)** can be done early/in parallel to lock the claims.

### Definition of "high bar" (Definition of Done of the effort)
- [ ] All **P0** items resolved (correctness + security).
- [ ] ID migration complete (random base62) and **no dedup**.
- [ ] Analytics **persist** and `clickCount` is correct; **TTL/expiration** works.
- [ ] `core` does not depend on `infra.*` (Dependency Inversion ok) and has no inline names.
- [ ] Security: no improper open redirect, rate limit on redirect, validated URL, restricted
  actuator.
- [ ] Versioned migrations; docs aligned with reality; native build works.
- [ ] CI running; tests covering the critical scenarios (including concurrency/security).
- [ ] SLOs measured (latency p50/p95/p99) under load at the real volume.

---

## Notes
- **Phases 0/1 scopes are the highest ROI** (correctness + design change).
- **Do not inflate to hyperscale**: the repo already has CDN/sharding/Kafka/Bloom/Redis cluster as
  "features", but for **on-prem/bare metal** that is usually over-engineering — prioritize only
  what solves your real-volume problem. (Reinforced by item P2-9 of the audit.)
- Any item can be split into smaller PRs; the roadmap is the target, not the commit plan.