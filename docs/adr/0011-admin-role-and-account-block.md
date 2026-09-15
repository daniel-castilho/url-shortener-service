# ADR 0011 — Product ADMIN role (env-list bootstrap) and account blocking

- **Status:** Accepted
- **Date:** 2026-09-15
- **Context:** The product is consumed by a same-origin SPA (ADR 0010) and has a single operator. Today
  the token carries no role, so the SPA cannot hide an `/admin` surface without guessing, and abuse is
  handled only by Mongo + curl (there is no way to block an abusive account; the AUTH throttle is per IP,
  not per account). The security chain already reserves a `ROLE_ADMIN` slot on the actuator tiers but no
  mechanism grants it. The desired surface is a data-plane administration API (users, links, block,
  force-archive) behind the same JWT/cookie stack as the product — not the SPA scraping `/actuator`
  (which stays opscured behind operator BasicAuth).
- **Decision:**
  1. **ADMIN bootstrap = email list in env.** `app.admin-emails` (yaml list; env `APP_ADMIN_EMAILS`,
     comma-separated; empty list = no admin). On token issuance, `role = ADMIN` when the user's email is
     in the list, otherwise `USER`. The `role` claim lives **only in the access token** — the refresh
     token carries no claim (minimal surface). A token **without** the claim maps to `USER` (legacy-token
     compatibility, tested). A DB `role` field and a "first user = admin" mechanism are rejected: a DB flag
     would require Mongo + curl to bootstrap the first admin (the exact flow this epic eliminates), and
     "first user" is ambiguous in UAT, surprises after a DB reset and is untestable in isolation. The
     `role` shown in the admin **listing** is the live truth from the env list (current status), not the
     claim (which may be stale until next login).
  2. **Occupy the reserved `ROLE_ADMIN` slot.** The JWT `role` claim feeds the filter authorities
     (`ROLE_ADMIN`/`ROLE_USER`), and the Spring Security actuator tiers that already read `hasRole("ADMIN")`
     become satisfiable by the product admin. Accepted consequence: the single operator keeps `ROLE_OPERATOR`
     via BasicAuth; other users stay locked out of those tiers. No second role name is invented.
  3. **Account block = `blocked` field on `User` (data, not config).** New field on the `User` record and
     the Mongo entity/mapper; absent on an existing document ⇒ `false`; **expand-only, no V-migration**
     (this repo's `V*` scripts are for indexes/collections only). Blocked **login/refresh → HTTP 403**
     (not 401): the credential is valid, the access is denied — answering 401 to a blocked user would be a
     lie in the UI. Order is contracted: validate the credential **first** (invalid → 401), then the block
     (→ 403 `"Account blocked."`). The AUTH-scope throttle still runs **before** the use case (429 consumes
     the bucket before a block 403). **Write-path check:** a blocked user calling `POST /api/v1/urls` with a
     session (Bearer or cookie) gets 403 before anything is generated/persisted/quota-metered; the anonymous
     path does no extra lookup (documented limit: block is by account, not by IP — anonymous shorten
     continues). **Self-block → 400** (without a logged-in UI an admin could not unblock themselves;
     recovery would regress to Mongo + curl). Reads for a blocked user keep working (v1).
  4. **No revocation in v1.** Access JWTs of a blocked user remain valid until their natural expiry — the
     same semantics as logout. A denylist/revocation-on-block is a **registered follow-up** (AGENTS.md debt
     matrix, same family as refresh-token rotation); its named trigger is the first abuse incident with a
     valid token outliving the block.
- **Consequences:**
  - The SPA's `/admin` gating maps directly to `role === "ADMIN"` from the login/`/me` bodies; the role is
    additive on all four auth responses.
  - An ADMIN principal can also satisfy `hasRole("ADMIN")` tiers on `/actuator` (bare index and the
    remaining ADMIN-only endpoints) — acceptable for a single-operator product where the operator already
    holds OPERATOR credentials.
  - Admin data-plane services (`/api/v1/admin/**`) are protected by `authenticated()` in the chain plus an
    **application-layer** `role == ADMIN` enforce in each admin use case (throws `ForbiddenException` → 403).
  - The new `blocked` field ships without a schema migration; the Mongo mapper defaults the field to
    `false` on read and writes are targeted (`setBlocked` uses `updateOne`, never a full-document rewrite).
  - The gate stays frozen: the admin surface registers **zero** new Micrometer meters and the Admin
    component is **not** living-spec-gated (new components never start `@spec-complete`).
- **Revisit triggers:**
  - A future cross-origin SPA that must send cookies with credentials (loses SameSite=Lax) → double-submit
    CSRF token (R2 of ADR 0010) — the `/admin` routes are the first cookie-money path that would force it.
  - First abuse incident where a blocked user keeps acting with a pre-block token → implement
    denylist/revocation-on-block (a watchlist keyed by token id/sub + block time; same family as refresh
    rotation); refresh rotation is a pre-existing debt and remains out of scope here.
  - Reducing the access-TTL to shrink the read window for a blocked user → requires the SPA refresh loop
    in cookie mode (a client change) and is budgeted as a separate work item, not a surprise.
- **Rejected:**
  - **DB `role` field** — circular bootstrap: the ticket exists to remove Mongo + curl for admin work.
  - **First user = admin** — ambiguous in UAT, surprises after DB reset, untestable in isolation.
  - **Second role name / separate cookie for admin** — the `ROLE_ADMIN` slot already exists and the access
    cookie `Path=/` already covers `/admin`.
  - **Blocking anonymous shorten** — the AUTH throttle is per IP; extending account blocks to IPs is out of
    scope (documented limit).
  - **Start the Admin component spec-complete** — new components never start gated; the Admin living spec
    is future work.
- **Links:** `infra/config/properties/AdminProperties.java` (`app.admin-emails` / `APP_ADMIN_EMAILS`),
  `infra/security/JwtTokenProvider.java` + `JwtAuthenticationFilter.java`, `core/service/UserService.java`
  (`AuthResult.role`, block enforce on login/refresh), `core/service/UrlShortenerService.java` (write-path
  check), `infra/adapter/input/rest/auth/{AuthController,AuthResponse,MeResponse}.java`,
  `infra/adapter/input/rest/admin/AdminController.java`, `infra/security/package-info.java`
  (REQ-AUTH-011..013), `CHANGELOG.md`, AGENTS.md debt matrix (EP10 + denylist follow-up).