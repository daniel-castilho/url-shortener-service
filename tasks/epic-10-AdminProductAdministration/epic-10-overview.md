# Epic 10: Admin — Product Administration Surface + ADMIN Role (additive)

**Project:** url-shortener-service
**Context:** Java 25, Spring Boot 4.1.1, Hexagonal Architecture, MongoDB 6.0 (single-node), Redis (single-node), Tomcat 11 (virtual threads). Auth = stateless JWT with dual-write HttpOnly cookie (ADR 0010), `GET /me`, `POST /logout`, auth throttle (AUTH scope).
**Origin:** formal request from `url-shortener-web` owner (single operator) — evaluated and approved with changes (rulings D1–D6 below, already incorporated into this epic).

**Goal:** give the single operator a **data-plane** administration surface (users and links) behind the **same** JWT/cookie stack as the product: list users, open a user's links, find a short code, block a user, force-archive a link. **Not** "the SPA scrapes /actuator": actuator remains operator BasicAuth for machines. Factor 6 (stateless) **remains**: ADMIN is a **claim in the token** (and therefore in the access cookie), not a server session.

**Out of scope (not doing in this epic):**

- Admin of branded domains (domain queue) — does not exist in the product today.
- Rate limit dashboard (Prometheus already exists; actuator does not change).
- JWT revocation on block (denylist) — **registered follow-up**, same family as refresh rotation.
- New cookie path `/admin` (access cookie `Path=/` already covers).
- CORS / CSRF header (R2 of ADR 0010: SameSite=Lax still holds; same-origin).
- `role` field in the database (circular bootstrap — see D1) and "first user = admin" mechanism (rejected — D1).
- Second role name/cookie (rejected — D2).

## Repo state (pre-existing, not new work)

Verified at `d48bc88` — the epic **reuses**, does not rewrite:

- **Owner-scoped product surface:** `GET/DELETE /api/v1/urls`, detail with owner guard (`GetLinkUseCaseImpl` throws `ForbiddenException("User does not own this link")`), list contract `LinkListResponse{items, nextCursor, hasMore}` + `PageResult<C>`/`Cursor` (opaque cursor, style already contracted by SPA with `useInfiniteQuery`).
- **`ROLE_ADMIN` already exists as a reserved slot:** `SecurityConfig` has tiers `hasRole("ADMIN")` (`/actuator` bare, `/actuator/**` remaining) and `hasAnyRole("ADMIN", …)` (`health`, `metrics`, `circuitbreakers`) — and **no mechanism grants this role today** (the `BasicOperatorAuthFilter` only grants `ROLE_OPERATOR` via `OPERATOR_USERNAME/PASSWORD`). The chain default is `.anyRequest().authenticated()` — **no catch-all permitAll** (the `/me` matcher-order trap does not apply to `/api/v1/admin/**`).
- **Errors:** `core.exception.ForbiddenException` → 403 and `UrlNotFoundException` → 404, both mapped in `GlobalExceptionHandler` with body `ErrorResponse{status, title, message, timestamp}`. The owner guard already uses this pattern — admin inherits the same.
- **Short code = document id:** `GET /{id}` resolves via `findById(code)` directly — global lookup by code **does not need a new repository method**.
- **Archive:** `ArchiveLinkUseCase` (owner) + `urlCachePort.evict(id)` (pattern in `UpdateLinkUseCaseImpl`) — force archive reuses the same semantics (`deletedAt`) + eviction.
- **Auth:** ADR 0010 (dual-write cookie, Bearer first-class, `/me`, `/logout`, `no-store`), `TokenPort.generateToken(email)` (claim by email), JWT filter Bearer→cookie with Bearer-wins, AUTH throttle (10/min) **before** the use case.
- **Env culture:** secrets/operational parameters in env (`OPERATOR_*`, `RATE_LIMITER_*`, `APP_*` in yaml) — admin bootstrap follows this pattern (D1).
- **Living spec:** Auth component spec-complete (REQ-AUTH-001..010); `check-living-spec` gate alive — changes in gated packages require requirements + traces (granularity per test).

## What is actually missing

1. **ADMIN nowhere:** the token carries no role; `/me` and auth bodies do not expose `role` (SPA cannot hide `/admin` without guessing); no admin endpoints.
2. **Account blocking does not exist:** abuse handled today = Mongo + curl; no way to prevent login/refresh of an abusive user (the throttle 429 is by IP, not by account).
3. **Force archive does not exist:** operator cannot archive another user's link via API.
4. **Global lookup by code does not exist:** finding a short code requires Mongo.
5. **Write path without blocked account check:** blocked user with valid token keeps creating links until expiry.
6. **Contract not documented:** none of this is in OpenAPI (the SPA's `docs/api-contract.md` is captured from `/v3/api-docs` — without OpenAPI, their doc is born stale).

## Why this epic now?

- The backend has already delivered the base the request assumes: `/me`, HttpOnly cookies, logout, auth throttle (all verified at `d48bc88`). The SPA was **waiting for OpenAPI** to build `/admin` (PrivateAdmin by `role === ADMIN`, datagrid, code search, block/archive dialog) — every day without the contract is a day of the web squad stopped.
- Single operator = data-plane admin surface with minimal internal abuse risk and controlled blast radius (6 endpoints, 1 consumer).
- The `ROLE_ADMIN` slot already sits idle in the security chain — occupying it (D2) is cheaper and more consistent than inventing a second role.

## Owner decisions (incorporated — not open questions)

- **D1 — ADMIN bootstrap: email list in env.** `app.admin-emails` (list in yaml; env `APP_ADMIN_EMAILS`, comma-separated; empty list = no admin). On issuance, `role = ADMIN` if the user's email is in the list, otherwise `USER`; claim `role` **only in the access token** (refresh carries no new claim — minimal surface). Token **without** claim = `USER` (legacy token compat). The `role` field in the **listing** is the live truth from env (current status), not the claim (which may be stale). "First user" rejected: ambiguous in UAT, surprise post-reset, untestable in isolation. Circular bootstrap rejected: `role` flag in the database would require Mongo+curl to set the first admin — which the ticket eliminates.
- **D2 — Occupy the reserved `ROLE_ADMIN` slot.** The JWT claim satisfies the actuator ADMIN tiers (accepted consequence documented in ADR: the single operator already has OPERATOR credentials; other users remain blocked at those tiers). No second role name.
- **D3 — Enforce in application layer; own packages.** Thin controller extracts caller identity (email + role from principal) and delegates; the `role == ADMIN` check lives in the use case (throws `ForbiddenException` → 403 via existing handler). Packages: `core/ports/incoming/admin/`, impls in `core/service/` (prefix `Admin`), controller in `infra/adapter/input/rest/admin/`. Rationale: clean hexagonal + living spec gate is per-package (new package = non-gated = new tests don't trip the registry) + owner-scoped `UrlController` untouched.
- **D4 — Block: `blocked` field on `User`; 403 on login/refresh; write path check included; self-block 400.** `blocked` is data (not config): new field on `User` record + entity mapper (absent → `false`; **no V-migration** — `V*` here are indexes/collections). Blocked login/refresh → **403, not 401** (valid credential, access denied; 401 is "unauthenticated/invalid" — answering 401 to a blocked user would be a lie in the UI). Order: validate credential **first** (invalid → 401), then check block (→ 403 "Account blocked."). `POST /urls` with blocked session → 403 **before** shorten (lookup only when there is a session — the common anonymous path does not change; documented limit: block is by account, not by IP — anonymous shorten continues). Self-block → **400**. No revocation in v1: access JWTs live until expiry (same semantics as logout); denylist follow-up registered in commit body + ADR.
- **D5 — `q` is email prefix, cursor in urls list style.** `q` = prefix (not contains), regex-escaped in Mongo; opaque and stable cursor (`createdAt desc + id`), same default/cap of `limit` as the urls list.
- **D6 — 5 commits (one per story), all green; ADR 0011 in commit 1; OpenAPI/CHANGELOG in commit 5.** Each story is mergeable on its own with `./mvnw verify` + bash gates + living-spec green.

## Acceptance Criteria (grounded)

1. **ADR 0011** registered (data-plane admin surface × ops actuator; role from env and legacy compat; occupation of `ROLE_ADMIN` slot + consequence on actuator tiers; block semantics 403 + write path + self-block; no revocation in v1 + revisit triggers).
2. **Role in token and bodies:** claim `role` in access token (env list); filter → `ROLE_ADMIN`/`ROLE_USER` (absent claim → `ROLE_USER`); `role` (`"USER"|"ADMIN"`) in bodies of **login, register, refresh and `/me`** (additive; OpenAPI).
3. **`GET /api/v1/admin/users`** — items `{userId, email, name, role, blocked, createdAt}`; `limit`/`cursor` in urls list style; `q` = email prefix; `nextCursor`/`hasMore`.
4. **`GET /api/v1/admin/users/{userId}/urls`** — `LinkListResponse` contract, includes archived (`deletedAt` visible); 404 non-existent user.
5. **`GET /api/v1/admin/urls?code=`** — `ShortUrlResponse` + `ownerUserId` + `ownerEmail` (null if user doc does not exist — documented); 404 unknown code.
6. **`POST /api/v1/admin/users/{userId}/block`** and **`/unblock`** — idempotent, empty body, 204; 404 non-existent user; **self-block 400**. Blocked: login and refresh → **403** "Account blocked."; `POST /urls` with blocked session → 403; reads continue working (v1).
7. **`DELETE /api/v1/admin/urls/{id}`** — force archive: same `deletedAt` semantics as owner + `urlCachePort.evict`; idempotent, 204; 404 non-existent link; `GET /{id}` → 404 after.
8. **Authorization:** `/api/v1/admin/**` authenticated (explicit matcher); **USER on any admin route → 403**; anonymous → 401. ArchUnit: admin use cases in application layer, not in controller. Zero new meters (frozen).
9. **Gates + proof:** `./mvnw verify` green (18 new contract tests, mapped in `epic-10-testing.md`) + bash gates + `check-living-spec` (Auth extended with traces; Admin component still non-gated) + **live proof** (admin bootstrap via env → register → `/me` ADMIN → block/unblock → force archive) pasted in DoD.

**Quick traceability:**

| Story | Reference doc | Key aspect |
|-------|----------------|---------------|
| 10.1 | ADR 0011 + `TokenPort`/`JwtTokenProvider`/filter + auth DTOs | Role in token + legacy compat |
| 10.2 | `User`(+`blocked`) + `UserRepositoryPort` + `AdminController` (users) | Block/unblock + listing |
| 10.3 | `AdminController` (urls by user, lookup by code) | Read-only inspection |
| 10.4 | `AdminController` (force archive) + write path of shorten | Forced mutation + write check |
| 10.5 | OpenAPI + CHANGELOG + AGENTS.md + DoD | Contract + gates + live proof |

---

*Next step: execute stories 10.1–10.5 (`epic-10-technical-tasks.md`) and paste evidence into `epic-10-dod.md`.*