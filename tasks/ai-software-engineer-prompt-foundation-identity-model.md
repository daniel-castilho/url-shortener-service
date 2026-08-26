# AI Software Engineer Prompt — Foundation (Identity Model)
## Random Base62 codes, no dedup, namespace isolation & quality gate

**Status:** ready for implementation after Step 0 baseline verification.
**Priority:** P0 — architecture foundation. First epic.
**Target:** make the codebase converge on the locked identity model without breaking behaviour.
**Package:** `ca.tyny.urlshortener` plus repository-level configuration, tests, scripts and docs.

You implement the complete **Foundation (Identity Model)** epic. Correctness of the identity model —
non-predictable codes, no unintended dedup, collision safety, and boundary integrity — takes priority
over new features, cosmetic refactors and premature abstraction.

---

## Sources of truth — read in this order

1. `AGENTS.md`
2. `pom.xml` and `src/main/resources/application.yaml`
3. `docs/coding-standards.md`
4. `docs/testing-playbook.md`
5. `docs/data-model-decisions.md` and `docs/lessons.md`
6. `tasks/foundation-identity-model-spec.md`
7. `tasks/foundation-identity-model-backlog.md`
8. `tasks/foundation-identity-model-implementation-sequence.md`
9. Current production code and colocated `*Test` / `*IT` classes

If documentation disagrees with executable configuration, stop, report the mismatch and resolve it in
the same change set. Do not rely on an analysis file that is not tracked in the repository.

---

## Goal

The current code generates short codes with a Redis counter + Hashids (reversible/enumerable, coupled
to a central counter and a salt) and forces a 1-to-1 mapping via a unique index on `originalUrl`
(which surfaces duplicates as a misleading `409`). This epic replaces that with cryptographically
random Base62 codes, removes dedup, isolates the generated-code namespace from vanity aliases, and
lands the change behind a working quality gate.

The epic closes these current risks:

- short codes are enumerable/reversible if `SHORTENER_SALT` leaks and are coupled to a central counter
  with a colliding fallback;
- the same URL cannot be shortened twice without a misleading `409` (dedup semantics that don't match
  the desired model);
- generated codes and user vanity aliases share a namespace and can collide;
- the integration suite runs in the fast loop instead of a gate, with no CI and no architecture boundary
  check;
- the GraalVM native build points at a non-existent `mainClass`;
- metrics are registered but never recorded.

---

## Locked technical decisions

These are not left for the implementation agent to invent:

1. **Random Base62 codes.** `java.security.SecureRandom`, alphabet `0-9 A-Z a-z`, default length **7**
   (configurable via `app.shortener.code-length`). Never `java.util.Random` or a counter.
2. **No URL dedup.** Removed the unique index on `originalUrl`. Same URL → distinct codes. `409` means
   **only** "custom alias already exists".
3. **Collision via retry.** On `_id` `DuplicateKeyException`, retry a bounded number of times; a domain
   error if exhausted. Never reuse/drop a code. No `existsById` pre-check needed for generated codes.
4. **Namespace isolation.** Generated codes are exactly `code-length` Base62 chars; vanity aliases are
   a different shape (per-plan min length and/or `-`/`_` set) so the sets are disjoint. Reserved words
   are rejected as either.
5. **No Hashids / counter / salt.** Remove `org.hashids`, `RangeAwareIdGenerator`, the Redis
   `SEQUENCE_KEY` path and `SHORTENER_SALT`. `IdGeneratorPort` has one pure implementation.
6. **Index management is deterministic.** Replace `auto-index-creation` with a committed, idempotent
   migration step (a runner needs explicit approval). Keep `_id`, `userId`; drop `originalUrl` unique;
   add `urlHash` (no unique index).
7. **Quality gate first.** Wire failsafe + rename `*IT` + CI + the boundary grep **before** the identity
   change.
8. **Fix the native build** `mainClass` → `ca.tyny.urlshortener.Application`.
9. **No new Maven coordinate** without explicit approval (removing `hashids` is fine; adding a migration
   runner is not).

---

## Non-negotiable engineering rules

- Keep `core/` free of Spring, servlet, MongoDB, Redis, jjwt, Micrometer and `infra.*` types.
- Controllers remain thin; services depend on ports.
- Always validate the code length and alphabet; never accept a code outside the Base62 shape for a
  generated code.
- A generated code must never equal a reserved word; a vanity alias must never equal a valid generated
  code (structural separation).
- Never treat a duplicate `originalUrl` as an error; it creates a new code.
- Use the atomic `_id` insert for alias uniqueness — no check-then-put.
- Every schema/index change updates local setup, integration-test provisioning and README in the same
  step.
- Every step adds its own tests; do not postpone all tests to the final step.
- English only in code, tests, logs and docs.
- Do not push unless the human explicitly asks.
- Do not expand into analytics, link expiry/TTL, rate-limit on redirect, `$inc` quota, SSRF/URL
  hardening, actuator lockdown, tracing/SLOs, or API redesign — those are later epics.

---

## Required behaviour summary

### Shorten (unchanged route)

```http
POST /api/v1/urls
{ "originalUrl": "https://example.com/long/path", "customAlias": "my-link" }   // customAlias optional
```

- No alias → random Base62 code (`code-length` chars from `[0-9A-Za-z]`).
- Alias present, authenticated → returns the custom code; invalid/reserved → 400; already exists → 409.
- Same `originalUrl` twice → two distinct codes, both `200`.

### Redirect (unchanged)

```http
GET /{id}   -> 302 Location: <originalUrl>
```

### Config

```yaml
app:
  shortener:
    code-length: 7
    # salt: removed (SHORTENER_SALT no longer used)
```

---

## Scope exclusions

Do not implement in this epic:

- analytics persistence (`click_events`, `clickCount`);
- link expiry (`expiresAt`, TTL);
- rate limiting on the redirect path;
- `$inc`-based quota/click counters (that's a separate correctness epic);
- SSRF/URL hardening beyond keeping the existing `http(s)` guard;
- actuator/Swagger production lockdown;
- tracing/OpenTelemetry/SLOs;
- full route redesign/API v2.

---

## Definition of Done

The epic is complete only when all are true:

### Quality gate
- [ ] Failsafe wired; `mvn test` is Docker-free and `mvn verify` runs the `*IT` suite.
- [ ] CI workflow committed and runs both stages.
- [ ] Architecture boundary grep returns 0 matches and is enforced in CI.

### Identity model
- [ ] Codes are random Base62 (`SecureRandom`, length `code-length`, alphabet `0-9A-Za-z`).
- [ ] `hashids`, `RangeAwareIdGenerator`, the Redis counter path and `SHORTENER_SALT` are removed.
- [ ] `RandomUrlIdStrategy` + `VanityUrlIdStrategy` dispatch correctly.
- [ ] Generated codes and vanity aliases are structurally disjoint; reserved words rejected as either.
- [ ] Duplicate original URL → 200 with a distinct code (no dedup).
- [ ] `409` means only "custom alias already exists".
- [ ] `originalUrl` unique index is dropped; `_id` is the identity key; `urlHash` is stored (no unique index).

### Verification & delivery
- [ ] Collision/namespace/dedup/concurrency tests pass (unit + `*IT`).
- [ ] Native `mainClass` fixed; dead metrics removed.
- [ ] `mvn test`, `mvn verify` and the boundary check pass.
- [ ] README, AGENTS, data-model-decisions, coding-standards, testing-playbook and lessons are
      synchronized; `AGENTS.md` debt matrix reflects resolved items.

Start at **Step 0** of `foundation-identity-model-implementation-sequence.md`. Stop immediately if the
current baseline is red, a locked decision cannot be implemented with the approved dependency graph, or
repository state contradicts the specification.
