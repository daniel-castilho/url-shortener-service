# Dev-Edge Validation Evidence — canonical shortUrl + alias cap

**Branch:** `fix/public-url-and-alias-limit` · **Date:** 2026-10-03 · **Runner:** dev workstation
(local Docker, no staging/production touched).

**Scenario:** the service running locally behind a TLS-terminating edge equivalent to the
production topology — nginx container with a self-signed cert for `dev.short.local`, forwarding
the same header set as `deploy/proxy/nginx.conf` (`Host`, `X-Real-IP`, `X-Forwarded-For`,
`X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Forwarded-Port`) — the recipe documented in
`docs/backend-frontend-contract.md` §1/§7.

**App environment (dev profile):**

```
SERVER_PORT=18080
MONGODB_URI=mongodb://localhost:27017/url_shortener_dev_edge   # local container
REDIS_HOST=localhost REDIS_PORT=6379                            # local container
APP_PUBLIC_BASE_URL=https://dev.short.local
APP_DOMAIN_DEFAULT_HOST=dev.short.local
RATE_LIMITER_TRUSTED_PROXY_CIDRS=127.0.0.0/8,172.16.0.0/12     # edge is trusted
```

Edge: `nginx:alpine` (host network), `listen 8443 ssl`, self-signed cert
(`CN=dev.short.local`), `proxy_pass http://127.0.0.1:18080`. Client:
`curl --resolve dev.short.local:8443:127.0.0.1 -k`.

**Redaction:** every cookie value, token and user id in the raw session was replaced with
`<REDACTED>` before capture; no secret, cookie value or JWT appears below.

---

## 1. Shorten through the edge returns the canonical public URL

```
$ curl -k --resolve dev.short.local:8443:127.0.0.1 -X POST https://dev.short.local:8443/api/v1/urls \
    -H 'Content-Type: application/json' -d '{"originalUrl":"https://example.com/dev-edge-test-2"}'

{"id":"h47Pc9Z","shortUrl":"https://dev.short.local/h47Pc9Z"}
HTTP 200
```

Not `http://127.0.0.1:18080/h47Pc9Z` (the bind origin — the pre-fix behavior) and not
`http://localhost/...` (the pre-fix hardcoded list/detail base): the configured public origin.

## 2. Redirect 302 through the edge

```
$ GET https://dev.short.local:8443/h47Pc9Z
HTTP/1.1 302
Location: https://example.com/dev-edge-test-2
```

**Finding (fixed by configuration):** with the default `APP_DOMAIN_DEFAULT_HOST=localhost`, the
redirect answered `404` — the redirect serves a default-host link only for the configured host
(host-bound matching, REQ-SHORT-004/007). Setting `APP_DOMAIN_DEFAULT_HOST=dev.short.local`
(the host of `APP_PUBLIC_BASE_URL`) makes the topology consistent. The coupling is now documented
in the contract §1.2, the runbook checklist and the `application.yaml` comment.

## 3. Authentication through the edge (cookies, values redacted)

```
$ POST /api/v1/auth/register {"name":"Dev Edge","email":"devedge@test.local", ...}   → 200
$ POST /api/v1/auth/login
HTTP/1.1 200
Set-Cookie: access_token=<REDACTED>; Path=/; Max-Age=86400; Secure; HttpOnly; SameSite=Lax
Set-Cookie: refresh_token=<REDACTED>; Path=/api/v1/auth/refresh; Max-Age=604800; Secure; HttpOnly; SameSite=Lax
```

Cookie-only session rehydration (`/me` with no Bearer):

```
$ GET /api/v1/auth/me            (cookies only)   → 200
{"userId":"<REDACTED>","email":"devedge@test.local","role":"USER","name":"Dev Edge"}
$ GET /api/v1/auth/me            (anonymous)      → 401
{"timestamp":"...","status":401,"error":"Unauthorized","path":"/api/v1/auth/me"}
$ POST /api/v1/auth/logout                        → 204
Set-Cookie: <cleared> Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax
Set-Cookie: <cleared> Path=/api/v1/auth/refresh; Max-Age=0; Secure; HttpOnly; SameSite=Lax
```

## 4. List, detail and PATCH agree on the canonical URL

```
$ POST  /api/v1/urls (authenticated)   → 200  {"id":"ZA29FAp","shortUrl":"https://dev.short.local/ZA29FAp"}
$ GET   /api/v1/urls?limit=5            → 200  items[0].shortUrl = "https://dev.short.local/ZA29FAp"
                                              nextCursor=null, hasMore=false
$ GET   /api/v1/urls/ZA29FAp            → 200  shortUrl = "https://dev.short.local/ZA29FAp"
$ PATCH /api/v1/urls/ZA29FAp {"title":"dev-edge-patched"}
                                       → 200  shortUrl = "https://dev.short.local/ZA29FAp", title updated
```

All four surfaces return byte-identical `shortUrl` values for the same link.

## 5. Alias cap enforced at the edge of the system

```
$ POST /api/v1/urls  customAlias = 65 chars   → 400
{"status":400,"error":"Validation Failed",
 "validationErrors":{"customAlias":"Custom alias must be at most 64 characters"}}

$ POST /api/v1/urls  customAlias = 64 chars   → 200
{"id":"aaaa…(64)","shortUrl":"https://dev.short.local/aaaa…(64)"}
```

## 6. Rate-limit contract visible through the edge (bonus)

Burst of 12 logins (AUTH scope, default 10/min):

```
200 200 200 200 200 200 200 200 200 200 429 429
HTTP/1.1 429
Retry-After: 6
RateLimit-Limit: *
RateLimit-Remaining: 0
RateLimit-Reset: 6
```

---

## Coverage vs. the requested checks

| Requested check | Result |
| :--- | :--- |
| Public shorten URL through edge | ✓ evidence 1 |
| List/detail canonical URL | ✓ evidence 4 |
| Redirect 302 | ✓ evidence 2 (plus the default-host coupling finding, fixed by config + documented) |
| Authentication (cookies + /me) | ✓ evidence 3 (values redacted) |
| Alias limits 64/65 | ✓ evidence 5 |
| No tokens/cookies/secrets in evidence | ✓ all values redacted |

**Cleanup:** app process terminated, nginx container removed, dev database
(`url_shortener_dev_edge`) disposable (local dev container). No staging or production system was
accessed.
