# Epic 10 – Technical Tasks

General rule (house): commits in English without accents; code in English; epic docs in PT-BR (pattern `tasks/epic-*`); ADR/CHANGELOG in English; `git add -p` per story; each commit with `./mvnw verify` + green bash gates before push (rule zero: paste outputs in DoD). Anchor line: re-verify with grep before editing — the names below were verified at `d48bc88`.

## 10.1 ADMIN role in token + ADR 0011 (commit 1)

- `docs/adr/0010-*.md` → **new `docs/adr/0011-admin-role-and-account-block.md`**:
  Status Accepted. Context: SPA owner-scoped, abuse handled via Mongo+curl, single operator, ADR 0010 (dual-write) already in force. Decision: (1) role via **env list** `app.admin-emails` — circular bootstrap (flag in DB) and "first user" rejected and why; claim `role` only in access token; legacy token without claim = USER; (2) occupation of **reserved `ROLE_ADMIN` slot** — actuator tiers (`/actuator` bare, `/actuator/**`) become satisfiable by product admin (accepted: single operator already has OPERATOR; others remain blocked); (3) block = `blocked` field on `User` (data, not config; expand-only, no V-migration); 403 (not 401) on login/refresh — valid credential, access denied; write path check (blocked session → 403 on `POST /urls`); self-block 400; documented limit (block by account, not IP); (4) **no revocation in v1** — JWTs live until expiry (logout semantics); Consequences + Revisit triggers: cross-origin with credentials → double-submit (R2 of ADR 0010); denylist/revocation on block (same family as refresh rotation) → separate design; access-TTL reduction → requires SPA refresh loop in cookie mode (see owner note).
- `infra/config/properties/AdminProperties.java` (new, same standard package as `*Properties`): `app.admin-emails` — list; binding of env `APP_ADMIN_EMAILS` comma-separated (see `RateLimiterProperties` binding pattern); normalization trim+lowercase; getter `isAdminEmail(String)` (case-insensitive).
- `src/main/resources/application.yaml` + `deploy/url-shortener.env.example`:
  `app.admin-emails: []` (comment: "Comma-separated admin emails (env APP_ADMIN_EMAILS). Empty = no admin. (ADR 0011)") + line in env.example.
- `core/ports/outgoing/TokenPort`: `generateToken(String email)` → `generateToken(String email, String role)` (Javadoc: claim `role`; refresh unchanged). Update ALL callers (`UserService`) and the adapter (`JwtTokenAdapter`) — grep `generateToken` in entire repo before commit.
- `infra/security/JwtTokenProvider`: claim `role` in access token (parser for new claim: `getRoleFromToken`, absent → `null`); TTLs/signature intact.
- `infra/security/JwtAuthenticationFilter`: claim → authorities (`ROLE_ADMIN`/`ROLE_USER`; `null` → `ROLE_USER`) — today's principal (username = email) doesn't change; keep Bearer-wins and Javadoc of `getJwtFromRequest` (ADR 0010) intact.
- `core/service/UserService`: `AuthResult` + `role` field; `login`/`register`/`refreshToken`/`me` compute `role` via `AdminProperties.isAdminEmail` (inject properties); `me` still creates nothing (reuses `findByEmail`).
- `infra/adapter/input/rest/dto/auth/AuthResponse` + `/me` DTO (`UserResponse` — locate; it's the body of `GET /me`): + `role` (additive). `AuthController.toAuthResponse` passes the field.
- OpenAPI (in this commit, minimal): `role` in schemas/response of login/register/refresh/`me` (story 10.5 completes the rest).
- **Living spec:** `infra/security/package-info.java` (gated): new EARS requirements at test granularity — e.g. `REQ-AUTH-011` (token carries `role` claim per env list; absent → `ROLE_USER`), `REQ-AUTH-012` (`/me` and auth bodies expose `role`); traces on new tests (`@TracesRequirement`).
- **Tests** (mapped in `epic-10-testing.md`): `JwtTokenAdapterTest` (claim present/absent); `JwtAuthenticationFilter` (3 authority cases — locate existing filter test class or create in same package, traced); `UserServiceTest` (role in AuthResults + `me`); IT (new, root IT package `ca.tyny.urlshortener.`): **bootstrap** — register with test app's `APP_ADMIN_EMAILS` → login body `role=ADMIN` → `/me` `role=ADMIN`; register outside list → `USER`; **legacy token** (token generated without claim via provider → request authorized as USER + `/me` `role=USER`).
- CHANGELOG `[Unreleased]` → Added: `role` role (claim + bodies, additive; legacy tokens without claim = USER).

## 10.2 Block/unblock + user listing (commit 2)

- `core/model/User`: + `boolean blocked` (after `name`); update all constructions (factory `createFreeUser`, tests, mappers) — grep `new User(` in repo.
- `infra/adapter/output/persistence` (user entity + mapper): `blocked` field; **absent in doc → `false`**; serializer never omits implicit `false` ambiguously (mapping both ways tested in `UserEntityTest` — extend).
- `core/ports/outgoing/UserRepositoryPort`: + `PageResult<User> findPage(Cursor cursor, int limit)`, + `PageResult<User> findPageByEmailPrefix(String emailPrefix, Cursor cursor, int limit)`, + `void setBlocked(String id, boolean blocked)`. Mongo impl: sort `createdAt desc, id` (stable, same contract as link list — copy existing `findPage` pattern from link); prefix with `Pattern.quote` (regex-safe); `setBlocked` = `updateOne` (expand-only, doesn't touch other fields).
- **New packages (D3):**
  - `core/ports/incoming/admin/`: `AdminListUsersUseCase`, `AdminBlockUserUseCase`, `AdminUnblockUserUseCase` (signatures receive caller identity: `callerEmail`, `callerRole` — port doesn't know Spring).
  - `core/service/`: `AdminListUsersUseCaseImpl`, `AdminBlockUserUseCaseImpl`, `AdminUnblockUserUseCaseImpl` — **ADMIN enforce at top of each impl** (`callerRole != ADMIN → throw ForbiddenException`); block/unblock: `findById` (404) → **self-block → 400** (`IllegalArgumentException` mapped to existing 400 in handler — verify current mapping; if not exists, create handler) → `setBlocked` (idempotent).
  - `infra/adapter/input/rest/admin/AdminController` (new): `@RequestMapping("/api/v1/admin")` — thin: `Authentication` → (email = principal name, role = authority) → delegates.
- `UserService.login` / `refreshToken`: **after** validating credential, `user.blocked() → throw ForbiddenException("Account blocked.")` (order is contracted: invalid → 401 first).
- `infra/config/SecurityConfig`: + `.requestMatchers("/api/v1/admin/**").authenticated()` with comment (self-documentation; default already `anyRequest().authenticated()` — matcher makes intent explicit).
- **Tests** (mapped in `epic-10-testing.md`): IT (new `AdminUsersIT` in root IT package — new package = non-gated): USER on admin route → 403; anonymous → 401; pagination (3 users, limit 2); `q` prefix (positive + negative); `role` live in listing (register inside/outside list); `blocked` visible post-block; block 2× → 2× 204; blocked login → 403 (body "Account blocked."); invalid credential of blocked → 401 (order); blocked refresh → 403; unblock → login 200; self-block → 400; unknown → 404. Unit: `UserEntityTest` (mapper `blocked` both ways + doc without field → `false`).

## 10.3 Read-only inspection: urls by user + lookup by code (commit 3)

- `core/ports/incoming/admin/`: + `AdminListUserLinksUseCase`, `AdminLookupUrlUseCase`; impls in `core/service/` (ADMIN enforce at top; reuse existing ports — **no new repository method**):
  - list by user: same port/contract as owner list (locate cursor usage by `userId` in `ListUserLinksUseCaseImpl` and mirror, including archived — owner list already includes archived with `deletedAt`).
  - lookup by code: `LinkQueryPort.findById(code)` (the code IS the id) + `UserRepositoryPort.findById` for owner; `ownerEmail` null if user doc doesn't exist (DTO with nullable field + Javadoc).
- `AdminController`: + `GET /users/{userId}/urls` (query `limit`/`cursor` — same default/cap as owner list) and `GET /urls?code=`; new DTOs in `.../rest/admin/dto/` (`AdminUrlLookupResponse` = `ShortUrlResponse` + `ownerUserId` + `ownerEmail`; list items reusing `ShortUrlResponse`).
- 404s: `UrlNotFoundException` (link/code) and `UserNotFoundException` (check if exists; if not, create in `core/exception` + mapping in `GlobalExceptionHandler` → 404, pattern `UrlNotFoundException`).
- **Tests** (mapped): urls by userId (items + **archived with `deletedAt`** + pagination); 404 user; lookup 200 (`ownerUserId`/`ownerEmail` correct); lookup 404 unknown code; lookup with deleted user (setup via `UserRepositoryPort.deleteById`) → `ownerEmail` null.

## 10.4 Force archive + write path block check (commit 4)

- `core/ports/incoming/admin/`: + `AdminArchiveUrlUseCase`; impl in `core/service/` (ADMIN enforce) — **reuse `ArchiveLinkUseCase` semantics** (set `deletedAt` idempotent + `urlCachePort.evict`); 404 non-existent link via `UrlNotFoundException`.
- `AdminController`: + `DELETE /urls/{id}` (204).
- **Write path (D4):** `ShortenUrlUseCase`/`UrlShortenerService` — when caller (Bearer session **or** cookie) is present: load user and `blocked() → throw ForbiddenException` **before** generate/write (quota/metrics/counter no side-effect — verify shorten step order and position check right after caller resolution, before any side-effect); anonymous path (no session) without lookup. Controller already resolves optional caller (see `UrlController` — `User` already imported there) — don't change controller contract, only use case in service.
- **Tests** (mapped): force archive → `GET /{id}` 404; victim owner list shows `deletedAt`; second force archive → 204; 404 non-existent; blocked with token issued **before** block → `POST /urls` 403 (and link doesn't exist — lookup by code 404) + `GET /api/v1/urls` 200; `POST /urls` anonymous → 200 for blocked email (documented limit).

## 10.5 Contract and final gates (commit 5)

- **Complete OpenAPI** (what 10.1–10.4 didn't document): all 6 admin endpoints with `@Operation`/`@ApiResponse` (summary/description in English; params `limit`/`cursor`/`q`/`code`; responses 200/204/400/401/403/404 with `ErrorResponse` bodies); written semantics: **403 "Account blocked."** (login/refresh/write/admin), **403 "Forbidden"** (role), **400 self-block**, `ownerEmail` nullable, `q` = prefix, opaque stable cursor. `GET /v3/api-docs` validated in live proof (new JSON is SPA contract).
- **CHANGELOG** `[Unreleased]` completed (all entries from previous 4 commits reviewed + consolidated if needed).
- **AGENTS.md**: EP10 item in debt/status matrix (admin surface; follow-up of **denylist/revocation on block** listed with trigger — "first abuse incident with valid token > expiry" — and note that it's same family as refresh rotation); Current State line if README/AGENTS has the section (EP8 pattern: matrix item + `check-doc-sync` PASS).
- **ArchUnit:** front controller test (repo already has `check-boundaries` + ArchUnit — locate class): assertion that admin use cases live in `core/service` (application) and controller has no business logic (existing boundary gate pattern — mirror, don't invent new framework).
- **Epic gates:** `./mvnw verify` (complete); `check-boundaries`, `check-doc-sync`, `check-metrics-frozen` (zero new meters — assertion: frozen list unchanged), `check-living-spec` (+ `--self-test`) — Auth ≥ 100% traced, Admin still **non-gated** (no new `@spec-complete`).
- **Live proof** (rule zero; secrets redacted) — exact sequence in DoD: boot with `APP_ADMIN_EMAILS` → register admin → login (`role=ADMIN` in body) → `/me` → register victim → **block** 204 → victim login **403** → **unblock** 200-login → victim shorten → **lookup by code** → **force archive** 204 → `GET /{id}` 404 → victim owner list with `deletedAt`.
- DoD filled (all outputs pasted) + `git log --oneline` of 5 commits.

## 10.6 Epic final gates (closing checklist)

- [ ] 5 commits, each green on its own (`./mvnw verify` + bash gates) — CI runs pasted in DoD.
- [ ] `check-living-spec` PASS (Auth 100%; no new gated component).
- [ ] `check-metrics-frozen` PASS (list intact).
- [ ] `check-boundaries` + ArchUnit admin PASS.
- [ ] OpenAPI `/v3/api-docs` contains 6 endpoints + `role` (curl pasted).
- [ ] CHANGELOG/AGENTS.md/ADR 0011 consistent (`check-doc-sync` PASS).
- [ ] Complete live proof pasted in DoD.
- [ ] Denylist follow-up registered (matrix + commit 5 body).

---