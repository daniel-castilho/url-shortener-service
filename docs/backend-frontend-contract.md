# Backend ↔ Frontend Contract

**Scope:** the integration surface a frontend (or any API client) depends on: canonical URLs,
alias rules, endpoints and status codes, pagination, throttling headers, cookie authentication,
trusted proxies and Swagger access.

**Baseline:** contract audited at commit `7b68caa` (main, 2026-10-02); the deviations found there
are fixed in this branch and marked **[fixed here]**. Everything else documents the behavior that
was already live and covered by the cited tests.

**Sources of truth for this document:** `src/main/java/.../infra/adapter/input/rest/**`
(controllers, DTOs), `src/main/resources/application.yaml`, and the cited integration tests
(all green in CI). When this document and the OpenAPI spec disagree, the **code + tests** win —
file an issue and fix the doc.

---

## 1. Canonical URLs (`shortUrl`)

Every response that carries a `shortUrl` field — `POST /api/v1/urls`, `GET /api/v1/urls` (list),
`GET /api/v1/urls/{id}`, `PATCH /api/v1/urls/{id}` and the admin inspection endpoints — builds it
with the same resolver (`ShortLinkBaseUrlResolver`), so **all of them always agree** on the origin
for the same link.

### 1.1 Origin resolution (in order)

1. **Custom-domain binding wins:** a link created with a verified `domain` resolves to
   `https://<domain>/<id>` — always HTTPS, regardless of configuration (short links are
   HTTPS-only by product rule). The edge that makes these URLs actually servable is part of the
   contract: **Caddy on-demand TLS gated by the app's ACTIVE-domain registry**
   (`/internal/edge/domain-ask`, `EDGE_ASK_TOKEN`) provisions and renews one certificate per
   customer domain automatically — lifecycle, revocation semantics and the NGINX/Caddy decision
   are specified in `docs/custom-domain-edge.md`.
2. **Configured public origin:** `app.shortener.public-base-url` (env `APP_PUBLIC_BASE_URL`),
   e.g. `https://short.example.com` → `shortUrl = https://short.example.com/<id>`.
   This is the **required** setting in production (`ProdConfigValidator` aborts boot without it,
   and requires the `https://` scheme). **[fixed here]** — before this branch, list/detail/PATCH
   and admin responses hardcoded `http://localhost`, and the shorten response derived the origin
   from the bind address (`http://127.0.0.1:8080/...` behind a proxy).
   **Operational coupling (verified in the dev-edge run):** `app.domain.default-host`
   (`APP_DOMAIN_DEFAULT_HOST`) must equal the host of the public base URL — the redirect serves a
   default-host link only for that host (`GET /{id}` returns 404 otherwise, by design — host-bound
   redirect matching, REQ-SHORT-004/007).
3. **Request-derived fallback (unset config):** the origin of the incoming request
   (`scheme://host:port` of what the client actually dialed). Only correct when the client
   reaches the service directly (local dev). Testcontainers ITs exercise this fallback and assert
   the full `http://localhost:<port>/<id>` form.

### 1.2 Forwarded headers policy — no implicit trust

The backend does **not** trust `X-Forwarded-*` for URL building. Any client could otherwise
forge the public origin. If request-derived origins behind an edge are preferred over the explicit
config, the operator must enable forwarded-header processing **with a proxy-scoped trust
boundary**:

```yaml
server:
  forward-headers-strategy: native      # Tomcat RemoteIpValve
  tomcat:
    remoteip-internal-proxies: "^10\\.20\\.30\\.(\\d{1,3})$"   # the edge CIDR only
```

Never use `framework` (Spring's `ForwardedHeaderFilter` trusts any caller) and never widen the
internal-proxies regex beyond the actual edge.

### 1.3 Frontend guidance

- Prefer the `shortUrl` field as-is; it is canonical on every endpoint now.
- Do **not** reconstruct URLs from the API host — behind an edge they differ by design.
- The link id is always also present (`id` field) if the frontend must build relative UI paths
  (`/{id}` detail views, QR codes, share text): concatenate with the origin from `shortUrl`.

**Tests:** `PublicBaseUrlIT` (configured origin on shorten/list/detail/PATCH, custom domain wins,
anonymous callers, request-derived fallback), `ShortenFlowIT` (fallback, full scheme+host),
`LinkResourceIT` (detail matches shorten), `ShortLinkBaseUrlResolverTest` (resolution order,
normalization, fail-fast on schemeless config), `ProdConfigValidatorIT` (prod requires
`https://` public origin).

---

## 2. Custom alias (vanity) rules

| Rule | Value | Enforced at |
| :--- | :--- | :--- |
| Alphabet | `^[a-zA-Z0-9-_]*$` (letters, digits, `-`, `_`) | DTO (`ShortenRequest`) → 400 |
| **Max length** | **64 characters** (plan-independent) | DTO `@Size` → 400; business layer `AliasPolicy` → 400; OpenAPI `maxLength: 64` **[fixed here — no max existed]** |
| Min length | plan-based: FREE 8, SILVER 5, GOLD 4, DIAMOND 3 | business layer (`QuotaService`) → 402 |
| Reserved words | `api`, `auth`, `health`, `admin`, `swagger`, `metrics`, `actuator`, `v1`…`v3`, `login`, `register`, `refresh`, `logout`, `dashboard`, `profile`, `billing`, `settings`, `users`, `urls`, `static`, `public`, `assets`, `css`, `js`, `images`, `img`, `favicon`, `robots`, `sitemap` | business layer (`ReservedWordsValidator`) → 400 |
| Authentication | required (anonymous alias request → 400) | business layer |
| Uniqueness | `_id` insert; concurrent creators → exactly one `201/200`, loser gets 409 | persistence (`409` = alias exists — the only 409 meaning; URLs are never deduplicated) |

**Mirror rule (frontend and edge):** the 64-character cap is authoritative in the backend, but the
frontend input and the edge (nginx `location` guard or WAF rule) must apply the **same** limit so
users fail fast instead of discovering a 400 after submission. Keep the three layers in sync via
this document; there is deliberately no per-plan maximum.

**Tests:** `UrlControllerTest` (65 → 400 without touching the use case; 64 passes validation),
`UrlShortenerServiceTest` (65 rejected before persistence; 64 accepted — traced to
`REQ-SHORT-002`), `PublicBaseUrlIT` (end-to-end 64 accepted / 65 rejected), `QuotaServiceTest`,
`SubscriptionPlanTest` (plan minimums).

---

## 3. Endpoints and status codes

Base path `/api/v1`. Content type JSON (requests and responses). Errors are always
`{ "status": 4xx/5xx, "error": "<Type>", "message": "<human readable>", "timestamp": "..." }`;
bean-validation failures add a `validationErrors: { <field>: <message> }` map (400).

### 3.1 Auth (`/api/v1/auth`)

| Endpoint | Success | Failure |
| :--- | :--- | :--- |
| `POST /register` | `200` + token pair + cookies | `400` e-mail in use / weak input; `429` AUTH rate limit |
| `POST /login` | `200` + token pair + cookies | `401` bad credentials; `403 "Account blocked."` (after credential validation); `429` |
| `POST /refresh` | `200` + **new access** token/cookie; refresh value **unchanged**, Max-Age slid | `401` missing, invalid or expired refresh token (cookies cleared, Max-Age=0); `403` blocked; `429` |
| `GET /me` | `200 {userId, email, role, name}` | `401` anonymous |
| `POST /logout` | `204` always (idempotent, clears both cookies) | — |

- Bearer header and cookie are both accepted everywhere; when both are present, **Bearer wins**.
- All auth responses set `Cache-Control: no-store`.
- Register/login/refresh JSON bodies **also carry `token` + `refreshToken`** (dual transport,
  ADR 0010): cookie-mode frontends must not persist these (see §6).

### 3.2 URLs and links (`/api/v1/urls`)

| Endpoint | Success | Failure |
| :--- | :--- | :--- |
| `POST /` | `200 {id, shortUrl}` (anonymous allowed) | `400` invalid URL/alias; `402` quota/plan; `409` alias exists; `403` blocked account; `429` SHORTEN rate limit |
| `GET /` (list) | `200 {items, nextCursor, hasMore}` | `400` malformed cursor; `401` |
| `GET /{id}` | `200` full link view | `401`; `403` non-owner; `404` unknown |
| `PATCH /{id}` | `200` updated view (supplied fields only) | `400`; `401`; `403`; `404`; `409` archived (immutable) |
| `DELETE /{id}` | `204` (idempotent archive) | `401`; `403`; `404` |
| `GET /{id}/clicks` | `200` time series + breakdown | `400` bad unit/range; `401`; `403`; `404` |

### 3.3 Redirect (`GET /{id}`)

`302` + `Location: <originalUrl>` when the link exists and is live; `404` unknown/archived;
`410` expired; `429` over the per-IP REDIRECT budget. The redirect never blocks on analytics.
The response has **no body** — do not try to parse one.

### 3.4 Admin (`/api/v1/admin/**`, ADMIN role only)

User listing (`GET /users`, cursor-paginated, `q` e-mail prefix filter), block/unblock
(`POST /users/{id}/block`, `/unblock` — idempotent `204`, self-block `400`), user links
(`GET /users/{userId}/urls`), global lookup by code (`GET /urls?code=`), force archive
(`DELETE /urls/{id}`, idempotent `204`). All views use the same canonical `shortUrl` resolver.

**Tests:** `AuthCookieIT` (15), `AuthControllerTest` (WebMvc slice), `LinkResourceIT` (25),
`AdminBootstrapIT` / `AdminUsersIT` / `AdminInspectIT` / `AdminArchiveIT`, `ShortenFlowIT`,
`ExpiredUrlIT`, `RedirectRateLimitIT`.

---

## 4. Pagination (`LinkListResponse`)

```json
{ "items": [ShortUrlResponse...], "nextCursor": "eyJ...", "hasMore": true }
```

- Cursor is an **opaque, Base64url-encoded** `<epochMillis>:<id>` key (createdAt DESC, `_id` DESC
  tie-break). Pass it back verbatim as `?cursor=`; never decode or build cursors client-side.
- `nextCursor` is **`null`** when there are no more pages (`hasMore: false`). A non-null
  `nextCursor` may still return an empty page if entries were deleted in between — treat it as
  normal termination.
- `limit` defaults to 20, capped at 100 (larger values are clamped, not rejected).
- A malformed cursor is a `400` — do not retry it; restart from the first page.

---

## 5. Rate limiting (429)

Every 429 carries (single egress, both in `UrlController` and `AuthController`):

| Header | Value |
| :--- | :--- |
| `Retry-After` | seconds until the bucket resets (integer) |
| `RateLimit-Limit` | `*` (per-IP budgets differ per scope; no single limit is disclosed) |
| `RateLimit-Remaining` | `0` |
| `RateLimit-Reset` | seconds (same value as `Retry-After`) |

- **The 429 body is empty.** Do not attempt to parse it; distinguish 429 by the status code.
- Scopes are **independent buckets**: SHORTEN (`POST /api/v1/urls`), REDIRECT (`GET /{id}`) and
  AUTH (login/refresh). Exhausting one never affects the others.
- Budgets are per client IP (see §7) and shared across instances (Redis).
- Frontend guidance: honor `Retry-After`, back off, never retry in a tight loop.

**Tests:** `RedirectRateLimitIT`, `AuthRateLimitIT`, `UrlControllerRateLimitingTest`,
`UrlControllerTest` (headers asserted).

---

## 6. Cookie authentication (ADR 0010)

| Cookie | Attributes |
| :--- | :--- |
| `access_token` | `HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=86400` (24 h default JWT TTL) |
| `refresh_token` | `HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth/refresh; Max-Age=604800` (7 d) |

- **No `Domain` attribute** → host-only cookies: they are only sent back to the exact host that
  issued them. Serving the frontend from the same origin as the API is the supported topology.
- `Secure` means the cookies require HTTPS in every environment, including local testing.
- The refresh cookie is path-scoped to `/api/v1/auth/refresh` — it is never attached to other
  requests, minimizing exposure.
- **The JSON body still contains `token` and `refreshToken`** on register/login/refresh
  (`AuthResponse`: `token`, `refreshToken`, `userId`, `email`, `role`, `name`). This is
  intentional (dual transport keeps Bearer-only clients working). A cookie-mode frontend must
  **not** store these values (no localStorage/sessionStorage); ignore the fields and rely on the
  cookies.
- `POST /refresh` with neither a body token nor the cookie is **401** (not 400). On success the
  access token rotates; the refresh token value is **unchanged** (sliding Max-Age only).
- **Refresh failure is terminal and clears the session:** any `401` from `POST /api/v1/auth/refresh`
  (missing, invalid, expired or malformed refresh token — body or cookie) answers `Set-Cookie` for
  **both** cookies with `Max-Age=0` and the exact original paths, mirroring `logout`. This is
  **scoped to the refresh path only** — a `401` from `login` or `me` never touches cookies, so an
  isolated expired access token on `/me` is not a logout signal. Cookie-mode frontends should treat
  a refresh `401` as "hard logout" (clear local state, navigate to `/login`) and retry the original
  request once after a successful refresh. The contract keeps `200` + dual-write (JSON + cookies)
  on success — there is no `204` no-body variant.
- `POST /logout` is idempotent and needs no authentication: it always clears both cookies with
  `Max-Age=0` and the exact original paths, answering `204`.

**Session cycle (cookie mode):** login → cookies set → call APIs (cookies attached automatically)
→ on 401 or from `/me`, try `POST /api/v1/auth/refresh` (no body needed, cookie attached
automatically) → on 401 again, **hard logout** (server cleared the cookies) → re-login →
`POST /logout` to end.

**Tests:** `AuthCookieIT` (exact attributes, Bearer-wins, refresh rotation, refresh-failure clears
both cookies, logout idempotency, minimized path, Secure flag, no-store).

---

## 7. Trusted proxies and client IPs

- Rate limiting resolves the client IP from `X-Forwarded-For` (left-most entry) **only** when the
  direct peer matches `rate-limiter.trusted-proxy-cidrs` (default `127.0.0.0/8`, `::1/128`;
  env `RATE_LIMITER_TRUSTED_PROXY_CIDRS`). A direct client forging the header is identified by
  its socket address — the header is ignored.
- **Production must** set `RATE_LIMITER_TRUSTED_PROXY_CIDRS` to the edge's address/CIDR
  (`ProdConfigValidator` enforces it). Otherwise every request appears to come from the edge and
  the per-IP budget degrades to one global bucket.
- The edge (`deploy/proxy/nginx.conf`) forwards `Host`, `X-Real-IP`, `X-Forwarded-For`,
  `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Forwarded-Port`. Of these the backend consumes
  `X-Forwarded-For` (rate limiting, per §7) and `Host` (custom-domain redirect matching); the
  rest are ignored unless forwarded-header processing is explicitly enabled (§1.2).

---

## 8. OpenAPI / Swagger access

- The full contract is annotated with OpenAPI 3 (`@Operation`, `@ApiResponse`, `@Schema`) —
  including `customAlias` with `maxLength: 64` **[fixed here]** and the nullable `nextCursor`
  semantics.
- **Swagger UI and the raw spec are disabled by default** (`app.security.swagger.enabled: false`,
  gated in `SecurityConfig`). Enable in dev with:

  ```sh
  APP_SECURITY_SWAGGER_ENABLED=true ./mvnw spring-boot:run
  # UI:   http://localhost:8080/swagger-ui.html
  # Spec: http://localhost:8080/v3/api-docs
  ```

- **Keep it disabled in production.** The workflow is: generate `openapi.json` from
  `GET /v3/api-docs` in dev, commit it with the frontend, generate the typed client from it.
- No CORS is configured — the API is **same-origin**. If a cross-origin frontend is ever
  required, CORS must be added (with `Access-Control-Expose-Headers` for `Retry-After` and
  `RateLimit-*`, otherwise browsers hide them from JS).

### 8.1 Build / version endpoint

`GET /actuator/info` is **public** and exposes the release identity (Request 3):

```json
"build": { "artifact": "url-shortener-service", "name": "url-shortener-service",
           "version": "0.X.Y", "time": "2026-10-03T21:11:30.425Z" }
```

- Populated by `spring-boot-maven-plugin:build-info` at build time; `version` reflects the
  `<revision>` resolved by the Maven wrapper. In the release image the version equals the tagged
  semver (`release.yml` passes `-Drevision=<semver>`).
- The frontend can read `build.version` to decide whether feature flags / API expectations match
  the running backend during staging checks. No other actuator endpoint is public (see
  `docs/security-notes.md` / `ProductionLockdownIT`).

---

## 9. Compatibility notes

- `7b68caa` (and the whole Epic 25 canary work before it) changed **no** `src/main` file — the
  HTTP contract is stable.
- Earlier additive changes a frontend must tolerate: `role` on auth responses (`USER`/`ADMIN`,
  legacy tokens → `USER`), `403 "Account blocked."` for blocked accounts, admin endpoints,
  cookie transport alongside Bearer (Bearer keeps precedence).
- No field has ever been removed or renamed; `nextCursor` has always been nullable-on-empty.
- This document is updated by the documentation change-impact gate
  (`docs/documentation-impact-map.json` → rule `API-CONTRACT`) whenever `infra/adapter/input/rest`
  changes — a PR that changes the REST surface without updating this file fails CI.
