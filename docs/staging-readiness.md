# Staging Readiness Checklist (Epic 14 Request 3)

The frontend staging environment validates the running backend **before** driving the UI.
This checklist is the backend's own guarantee; the frontend consumes the same contract
(`docs/backend-frontend-contract.md`).

## Mandatory gates

| # | Check | Command | Pass = |
| :--- | :--- | :--- | :--- |
| 1 | Backend reachable | `curl -fsS <API>/actuator/health/liveness` | `200` |
| 2 | Dependencies healthy | `curl -fsS <API>/actuator/health/readiness` | `200` (`UP`) |
| 3 | Version present | `curl -fsS <API>/actuator/info` | JSON with `build.version` |
| 4 | Auth works | `POST <API>/api/v1/auth/register` (unique e-mail, ≥6-char password) | `200` + `token`, `refreshToken`, `role` |
| 5 | Shorten works | `POST <API>/api/v1/urls` (bearer `token` from #4) | `200` + `{id, shortUrl}` |
| 6 | Redirect works | `GET <shortUrl>` (or `GET <API>/<id>` with `useRefererHost`/default host) | `302` + `Location` |

> `<API>` is the staging origin (e.g. `https://staging.tyny.ca`). All endpoints above are
> reachable without HTTP auth; 4–6 use the API only.

## Version contract (check 3)

- `build.version` mirrors the deployed semver (the `>-Drevision=<semver>` build). The frontend
  feature gate may read it, but treat it as **advisory**: the API itself is the contract.
- `build.artifact` is always `url-shortener-service`.
- No other actuator endpoint is public — `/metrics`, `/prometheus`, `/health` details,
  `/env` etc. are operator/ADMIN-gated (`ProductionLockdownIT` asserts this).

## Admin / role checks (ADR 0011)

When the staging env sets `APP_ADMIN_EMAILS`:

| # | Check | Command | Pass = |
| :--- | :--- | :--- | :--- |
| A1 | Admin role surfaces | login with an e-mail from the list | response `role: ADMIN` |
| A2 | User role surfaces | login with a non-listed e-mail | response `role: USER` |
| A3 | Admin endpoints gated | any admin call with a `USER` token | `403` |
| A4 | Blocked account | block via admin API, then login | `403 "Account blocked."` |

## Cookie & refresh contract (ADR 0010, Fase A)

| # | Check | Pass = |
| :--- | :--- | :--- |
| C1 | Login sets both cookies | `Set-Cookie: access_token` + `refresh_token` (HttpOnly, Secure, SameSite=Lax) |
| C2 | Refresh via cookie only | `POST /api/v1/auth/refresh` with `refresh_token` cookie, **no body** → `200`, new `access_token` cookie |
| C3 | Refresh failure clears session | refresh with an invalid/expired token → `401` **and** `Set-Cookie` clearing both cookies with `Max-Age=0` (exact paths) |
| C4 | `me`/`login` don't clear | a `401` from `/me` (anonymous) or bad-credentials `login` returns **no** cookie-clearing headers |
| C5 | Logout idempotent | `POST /api/v1/auth/logout` (no auth) → `204`, both cookies cleared |

## Disposable-stack notes

- Frontend/local E2E: `docs/e2e-backend.md` + `docker-compose.e2e.yaml` +
  `scripts/seed-e2e.sh` provide the disposable backend; there is **no** reset endpoint — tear
  the compose stack down with `-v` and re-seed.
- The redirect hot path must stay same-origin or go through a reverse proxy that preserves the
  default host (this project's edge: `deploy/proxy/`).
- Rate limits are on by default; raise `RATE_LIMITER_*` in the disposable stack if E2E needs
  burst headroom (see e2e-backend doc).