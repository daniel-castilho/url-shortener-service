# Disposable E2E Backend (Epic 14 Request 2)

A one-command, throw-away backend for **frontend E2E and staging smoke tests**. Brings up
MongoDB + Redis + the URL Shortener API exactly like production would run it (containerized,
same image), so a frontend engineer never needs a local JVM/Maven install.

## Quick start

```sh
docker compose -f docker-compose.e2e.yaml up --build
# wait for the app healthcheck (docker compose ps shows Healthy), then
bash scripts/seed-e2e.sh
```

- API: `http://localhost:8080`
- Liveness: `GET /actuator/health/liveness`
- Readiness: `GET /actuator/health/readiness` (Mongo + Redis + circuit breakers)
- Swagger UI (dev only): `APP_SECURITY_SWAGGER_ENABLED=true docker compose -f docker-compose.e2e.yaml up`

## Making a product ADMIN (ADR 0011)

Roles are resolved from e-mail (`APP_ADMIN_EMAILS`). Grant ADMIN to a seeded account:

```sh
APP_ADMIN_EMAILS=admin@example.com docker compose -f docker-compose.e2e.yaml up --build
bash scripts/seed-e2e.sh   # now also seeds the admin account
```

Login with `admin@example.com / password123` returns the `role: ADMIN` claim.

## Rate limiting

Per-IP budgets are **on by default** (same as prod). Frontend E2E that fires many login/shorten
calls in parallel will hit 429 unless you relax them:

```sh
RATE_LIMITER_LIMIT=100000 RATE_LIMITER_AUTH_LIMIT=100000 \
  docker compose -f docker-compose.e2e.yaml up
```

`RATE_LIMITER_ENABLED=false` turns every scope off (single-egress 429 meter then never fires);
use it only for UI layout tests, never for quota/anti-abuse assertions.

## Version / build identity (Request 3)

`GET /actuator/info` is public and answers `build.version` (the Maven `<revision>` baked in by
`spring-boot-maven-plugin:build-info`). The frontend can compare it against the expected backend
semver in staging readiness checks (see `docs/staging-readiness.md`).

## Cookie mode

The supported topology is **same-origin** (ADR 0010): the frontend must be served from the same
host/port as the API, or through a local reverse proxy that keeps `Host` intact. There is **no
CORS**. For a local frontend dev server on a different port, proxy `/api` (and `/actuator`) to
`http://localhost:8080` from the dev-server config instead of enabling CORS.

### Test matrix covered (backend-side)

- `AuthCookieIT` — cookie attributes, Bearer-vs-cookie precedence, refresh rotation, refresh
  failure clears both cookies, logout idempotency.
- `AuthRateLimitIT` — AUTH scope isolation and 429.
- `ProductionLockdownIT` — public liveness/readiness/info; gated actuator tiers.
- `ShortenFlowIT`, `RedirectRateLimitIT`, `ExpiredUrlIT` — main API behavioural contract.

## Teardown

```sh
docker compose -f docker-compose.e2e.yaml down -v   # -v drops the seeded Mongo volume
```

A fresh seed on a clean volume is the canonical "reset" — there is intentionally **no
delete-user/wipe endpoint** in the API (frontend teams reset state by re-creating the stack).