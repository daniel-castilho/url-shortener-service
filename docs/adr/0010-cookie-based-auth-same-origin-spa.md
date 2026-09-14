# ADR 0010 — Cookie-based auth for the same-origin SPA (additive, HttpOnly)

- **Status:** Accepted
- **Date:** 2026-09-14
- **Context:** The API is consumed by a same-origin SPA that currently boots in the same session as
  the login flow (identity lives in client-side state only). When the SPA navigates/reloads in the
  same tab via a same-origin request, the session is lost and the user must log in again. The fix
  must NOT break the existing bearer-token contract: mobile/CLI/third-party clients and the docs
  publish bearer tokens from the JSON bodies. The cookie transport is **additive** — the JSON body
  dual-write stays forever.
- **Decision:**
  1. **Dual-write, permanent** — login, register and refresh responses carry BOTH the JSON body
     (`token`, `refreshToken`, `userId`, `email`, `name` — unchanged, byte-identical) AND two
     HttpOnly cookies. Never document the cookie pair as "transitional"; the SPA reads its identity
     from the JSON body exactly as today and the browser replays the cookies automatically on
     same-origin requests.
  2. **CSRF control = SameSite=Lax, keep `csrf.disable()`** — the cookie flow only serves
     same-origin navigation, so `SameSite=Lax` is the active CSRF control. `csrf.disable()`
     stays because the cross-origin bearer API must keep working for mobile/CLI clients without
     cookies. **Revisit trigger:** any future cross-origin SPA that must send cookies with
     credentials (i.e. loses SameSite protection) → move to double-submit CSRF token and specify
     the header in OpenAPI. When that happens this ADR is amended; until then no change.
  3. **Secure + HttpOnly flags** — both cookies are `HttpOnly; Secure; SameSite=Lax`. Same-origin
     SPA over `https://` sends them normally; over `http://localhost` they also work (localhost is
     treated as a secure context by browsers). `http://<LAN-IP>` will NOT send Secure cookies —
     documented side effect, not a bug.
  4. **Paths** — `access_token` gets `Path=/` (authenticates the whole origin, including `GET
     /{id}` if a future SPA-only mode needs it); `refresh_token` gets the minimized
     `Path=/api/v1/auth/refresh` so the refresh secret is replayed only to the refresh endpoint.
     The asymmetry is intentional.
  5. **Refresh does not rotate in this ticket** — the refresh handler keeps today's stateless
     behavior: it issues a new access JWT and re-sets the refresh cookie **with the same value**
     (Max-Age slides). No refresh-token rotation or reuse detection (roadmap, separate ADR).
  6. **No denylist / no TTL change** — logout clears both cookies (Max-Age=0, exact same paths) and
     returns 204; the JWT remains valid until its natural expiry (today's behavior, unchanged).
     Token TTLs are unchanged; the cookie Max-Age mirrors the JWT TTLs (86400 s access;
     604800 s refresh).
  7. **No CORS changes** — the API has no CORS config today (cookies are same-origin only); nothing
     is added.
- **Endpoint surface:** `login`/`register`/`refresh` set the pair; `GET /api/v1/auth/me` returns
  `{userId, email, name}` for any authenticated principal (bearer or cookie) and — crucially — the
  SecurityConfig matcher for `GET /api/v1/auth/me` is `authenticated()` and sits **before** the
  `permitAll()` for `/api/v1/auth/**`; `POST /api/v1/auth/logout` clears the pair and returns 204 to
  anonymous callers too (idempotent). A `Cache-Control: no-store` header protects all four cookie
  responses.
- **Filter order (codified in `JwtAuthenticationFilter`):** Bearer header wins; otherwise the
  `access_token` cookie authenticates; otherwise anonymous. When both are present, Bearer wins —
  this keeps the documented bearer API authoritative and tested.
- **Consequences:**
  - The same-origin SPA survives full navigations/reloads: the browser replays `access_token` (and
    `refresh_token` to the refresh endpoint) automatically; the SPA can still prefer sessionStorage.
  - One shared secret-contract: cookie `access_token` and JSON `token` are the same JWT bytes;
    cookie `refresh_token` and JSON `refreshToken` are the same bytes. Nothing new is minted.
  - The JWT filter gained a cheap cookie fallback — no additional network call, no state.
  - Bearer clients are unaffected; OpenAPI/docs keep describing the bearer JSON flow and now also
    document the cookie pair on login/register/refresh.
- **Rejected:**
  - **sessionStorage-only identity (status quo)** — loses the session on tab reload/navigation
    within the same session; the whole point of the ticket.
  - **Use of an opaque session identifier / server-side session store** — adds state, requires a
    distributed store for horizontal scale, and diverges from the stateless-JWT deployment model
    (ADR 0001).
  - **Double-submit CSRF token now** — no cross-origin-with-credentials consumer exists; SameSite is
    sufficient today, per decision 2's revisit trigger.
  - **Refresh-token rotation now** — reuse detection requires a denylist/watchlist state that is out
    of scope (decision 5); flagged as follow-up in the commit message.
- **Links:** `infra/adapter/input/rest/AuthController.java`, `infra/security/JwtAuthenticationFilter.java`,
  `infra/config/SecurityConfig.java`, `infra/security/package-info.java` (REQ-AUTH-005..009),
  `src/test/java/ca/tyny/urlshortener/AuthCookieIT.java`, `CHANGELOG.md`.