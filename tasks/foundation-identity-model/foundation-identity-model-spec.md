# Foundation (Identity Model) — Technical Specification
## Random Base62 codes, no dedup, namespace isolation & quality gate

**Status:** ready for implementation after baseline verification — live status tracked in
`foundation-identity-model-backlog.md` and the as-built report.
**Priority:** P0 — architecture foundation. First epic.
**Companions:** `foundation-identity-model-backlog.md` · `foundation-identity-model-implementation-sequence.md`

---

## 1. Purpose

Make the current URL-shortener identity model converge on the architecture already locked in
`AGENTS.md`, `docs/data-model-decisions.md` and `docs/coding-standards.md`:

- replace the **counter + Hashids** code generator with **cryptographically random Base62** codes;
- remove **URL deduplication** (no `UNIQUE` on `originalUrl`);
- **isolate the namespace** so generated codes and user vanity aliases never collide;
- make `409 Conflict` mean **only** "custom alias already exists";
- stand up a **quality gate** (failsafe integration suite, CI, and the architecture boundary check) so
  the most sensitive change lands with a safety net.

This epic deliberately solves one high-risk problem deeply (the identity model) and does not attempt a
full redesign or any new feature.

---

## 2. Scope

### In scope

- new random **Base62** short-code generator (CSPRNG `SecureRandom`, alphabet `0-9 A-Z a-z`,
  default length 7, configurable via `app.shortener.code-length`);
- collision handling via **retry** on the `_id` `DuplicateKeyException` (bounded retries);
- removal of `org.hashids`, `SHORTENER_SALT`, `RangeAwareIdGenerator` and the now-unused counter path;
- removal of the `UNIQUE` index on `originalUrl` + (optional) non-unique index and a SHA-256 `urlHash`;
- **namespace isolation** between generated codes and vanity aliases;
- semantic fix of `409` (alias conflict only) and the debug/error message for duplicate URL;
- fix of the GraalVM native `mainClass` (points at a non-existent class);
- removal of dead metrics (`id.generation.duration`, `url.retrieval.duration`);
- the **quality gate**: wire `maven-failsafe-plugin`, rename `*IntegrationTest` → `*IT`, add a CI
  workflow, and add the architecture boundary grep as a local/CI gate.

### Out of scope

- analytics persistence, link expiry (TTL), rate-limit on the redirect path, `$inc` quota refactor,
  SSRF/URL hardening, actuator lockdown, tracing/SLOs — these are separate later epics;
- API v2 / route renaming;
- any new Maven coordinate beyond what is already in `pom.xml` (no new library, and no removal that
  requires a replacement not already present).

---

## 3. Architectural constraints

### 3.1 Dependency direction (unchanged, now enforced)

- `core/` (domain, use cases, ports) must **never** import `ca.tyny.urlshortener.infra.*`, nor
  Spring, MongoDB, Redisson, jjwt or Micrometer types.
- `infra/` implements the outbound ports (`UrlRepositoryPort`, `IdGeneratorPort`,
  `UrlCachePort`, ...) and wires them in `infra/config`.
- The ID-generation seam stays behind a `core` port so the storage strategy and the code scheme are
  both swappable.

### 3.2 Suggested package shape (target)

```text
ca.tyny.urlshortener/
├── core/
│   ├── idgeneration/
│   │   ├── UrlIdGenerator.java              # port (inbound to service / used by service)
│   │   ├── UrlIdGenerationStrategy.java     # strategy interface (supports / generateId)
│   │   ├── CompositeUrlIdGenerator.java     # picks strategy by alias presence
│   │   ├── RandomUrlIdStrategy.java         # NEW: produces random Base62 codes
│   │   ├── VanityUrlIdStrategy.java         # validates + returns the custom alias
│   │   └── Base62CodeGenerator.java         # (target) pure algorithm behind a strategy / generator
│   ├── model/
│   │   ├── ShortUrl.java                    # record (id, originalUrl, createdAt, userId, isCustomAlias)
│   │   └── Url.java                         # value object (validates http(s))
│   └── ports/outgoing/IdGeneratorPort.java  # generateId()
└── infra/
    ├── adapter/
    │   ├── input/rest/                      # controllers, dto, advice
    │   └── output/
    │       ├── persistence/                 # Mongo*Repository, *Entity, *Mapper
    │       └── redis/                       # RedisUrlCache, RedisRateLimiterAdapter (counter path removed)
    └── config/                              # ShortCodeConfig (Hashids bean removed), ServiceConfig
```

Names may follow existing repository conventions, but layer boundaries and behaviours are mandatory.

### 3.3 Cross-cutting rule

`@Transactional` does not make MongoDB and Redis atomic. Code generation must not depend on a
coordinated write across both — the strategy is chosen in `core`, and persistence enforces uniqueness
via the `_id`.

---

## 4. Exact ID-generation contract

### 4.1 Alphabet and length

```text
ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"   # 62 chars
CODE_LENGTH = 7   # configurable via app.shortener.code-length; integer, >= 6
```

Combinations at 7 chars ≈ 3.5 trillion; collision probability is negligible for any realistic
on-premises volume (see `AGENTS.md` rule 2).

### 4.2 Randomness source

Use `java.security.SecureRandom` (CSPRNG). **Never** `java.util.Random` or a counter. Generation is
pure — no Redis, no Mongo, no external call during generation.

### 4.3 Collision policy

- Codes are stored as the `_id` of `short_urls`.
- A collision surfaces as `DuplicateKeyException` on insert. The generator/repository must **retry** a
  bounded number of times (e.g. up to 8) with a fresh random code.
- If retries are exhausted, the operation fails with a domain error — it never reuses or silently
  drops a code.
- **No pre-check `existsById` is required** for generated codes (the DB index is the authority); the
  retry is the mechanism.

### 4.4 ID generator seam

- Keep `IdGeneratorPort.generateId()` as the abstraction the random strategy uses.
- `RandomUrlIdStrategy` becomes the strategy that produces random Base62 codes; `VanityUrlIdStrategy`
  continues to validate and return the custom alias.
- Remove `RangeAwareIdGenerator` (counter + Hashids) and `ShortCodeConfig`'s `Hashids` bean.

---

## 5. Exact data-model contract

### 5.1 Remove URL deduplication

- `ShortUrlEntity.originalUrl` is currently `@Indexed(unique = true)`. **Remove the `unique` flag.**
- The **same** URL may be shortened multiple times → distinct codes. `OriginalUrl` no longer
  enforces a 1-to-1 mapping.
- Add an optional **non-unique** index on `originalUrl` **only if** you will query by URL (no current
  use case — leave it out unless a query is added).

### 5.2 Optional analytics-ready `urlHash`

- Add a `String urlHash` field storing SHA-256 of the original URL (lowercase hex). This costs nothing
  now and enables de-duplicated aggregate queries later. Store it on write; do **not** add a unique
  index on it.

### 5.3 Registry of indexes (target for this epic)

| Collection     | Index             | Type         | Purpose                                  |
| -------------- | ----------------- | ------------ | ---------------------------------------- |
| `short_urls`   | `_id`             | unique       | Code identity; retry-on-collision        |
| `short_urls`   | `userId`          | non-unique   | User link listing (kept from today)      |
| `short_urls`   | `originalUrl`     | **removed**  | was forcing dedup — no longer desired    |
| `users`        | `email`           | unique       | Email uniqueness (registration guard)    |

> `expiresAt` (TTL) and `click_events` are **out of scope** for this epic (different epics). Do not
> add them here.

### 5.4 Schema/index management

Replace `spring.data.mongodb.auto-index-creation: true` with **versioned migrations** in this epic so
dropping the `originalUrl` unique index is deterministic. A lightweight migration runner (e.g.
`mongock`-style) is acceptable **only with explicit approval** (new coordinate). If no migration tool
is approved, implement index creation/drop as an ordered, idempotent app-level migration step —
committed, and applied before traffic, not on every startup.

---

## 6. Namespace isolation

### 6.1 Goal

Auto-generated codes and user vanity aliases **never** collide, and neither collides with system routes.

### 6.2 Rules

- **Reserved words** (`api`, `auth`, `health`, `admin`, `swagger`, `metrics`, `actuator`, `v1`,
  `login`, `register`, ...) are rejected as alias/code by `ReservedWordsValidator`. Keep this.
- **Structural separation (recommended):**
  - generated codes are **exactly** `app.shortener.code-length` chars from the pure Base62 alphabet;
  - vanity aliases must follow a **different** shape: min length per plan (FREE ≥ 8, SILVER ≥ 5,
    GOLD ≥ 4, DIAMOND ≥ 3) and regex `[a-zA-Z0-9-_]+`. A generated 7-char code cannot equal an alias
    that is ≥ 8 *or* that contains `-`/`_` (which the Base62 alphabet excludes). This makes the two
    sets structurally disjoint.
- **Collision safety:** alias creation relies on the atomic `_id` insert (the `DuplicateKeyException` →
  `AliasAlreadyExistsException` path), **not** on a check-then-put. A concurrent duplicate alias must
  resolve to a single `409`.

### 6.3 Semantics of `409`

`409 Conflict` now means **only** "custom alias already exists". A **duplicate URL** with the same
original URL is **allowed** (it creates a new code) and must **not** raise `409`.

---

## 7. Validation & error mapping (only what this epic touches)

| Scenario                                   | Response                    |
| ------------------------------------------ | --------------------------- |
| Invalid URL format (not `http(s)://`)      | 400                         |
| Custom alias invalid characters            | 400                         |
| Custom alias is a reserved word            | 400                         |
| Custom alias length below plan minimum     | 400/Quota (per current code) |
| Custom alias already exists                | **409**                     |
| Generated-code collision (after retries)   | 500 (unexpected)            |
| Duplicate original URL                     | **200** (new code, no dedup) |
| Unauthenticated custom alias               | 400                         |

Do not change any other HTTP contract in this epic.

---

## 8. Config contract

`application.yaml` (and overrides):

```yaml
app:
  shortener:
    code-length: 7        # NEW, replaces reliance on Hashids salt
    # salt: REMOVE (SHORTENER_SALT no longer used once Hashids is dropped)
```

- `SHORTENER_SALT` is **removed**; the JWT secret (`APP_JWT_SECRET`) is unaffected.
- `IdGeneratorPort` no longer uses Redis; the Redis `SEQUENCE_KEY` counter path is dropped.

---

## 9. Quality gate (prerequisite work in this epic)

To land the identity change with a safety net, wire the gate **before** the generator swap:

### 9.1 Failsafe + integration suite

- Add `maven-failsafe-plugin` so `*IT` classes run during `mvn verify`.
- Rename `*IntegrationTest` → `*IT` (and `MongoUrlRepositoryIntegrationTest`,
  `UrlShortenerIntegrationTest`, ...) so the fast `mvn test` does not run them.
- `mvn test` (fast, no Docker) vs. `mvn verify` (full gate incl. `*IT`).

### 9.2 CI workflow

Add `.github/workflows/ci.yml` running, on every push/PR:
`mvn test` → `mvn test -Dtest='*IT'` (Docker) → `mvn clean package`.

### 9.3 Architecture boundary gate

Add the two `grep` commands from `AGENTS.md` rule 1 as a local pre-commit check and a CI step. They must
return 0 matches.

---

## 10. Verification commands

At each relevant step run the smallest useful subset, and finish with the full mirror:

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
mvn clean package
mvn clean package -Pnative   # only after the mainClass fix

grep -rEn "import com\.example\.urlshortener\.infra" src/main/java/com/example/urlshortener/core
grep -rlE "org\.springframework|org\.mongodb|org\.redisson|io\.jsonwebtoken|io\.micrometer" src/main/java/com/example/urlshortener/core
```

---

## 11. Documentation deliverables

Update in the same epic:

- `README.md` — current state (ID strategy, no dedup), tech stack (remove Hashids), roadmap reflection;
- `AGENTS.md` — clear debt items 3 (base62), 4 (dedup), 7 (namespace), 11 (native mainClass),
  13 (stale docs) where resolved; keep item 10 (migrations) if a tool is not adopted;
- `docs/data-model-decisions.md` — record the Base62 + no-dedup + namespace decisions as applied;
- `docs/coding-standards.md` — update ID-generation, index and namespace rules;
- `docs/testing-playbook.md` — suite map (`*IT`), command changes, new collision/namespace tests;
- `docs/lessons.md` — note the durable lessons (reversible Hashids, misleading unique index,
  bloom-short-circuit, dead metrics).

The epic is **not** Done while documentation (or `AGENTS.md` debt matrix) describes the counter +
Hashids scheme or the unique-on-URL dedup as current.
