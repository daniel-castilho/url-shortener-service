# URL Shortener Service — Operational Release Runbook

How to operate this service in production without having written the code. This runbook targets the
current platform: **on-premises bare metal**, with **MongoDB** and **Redis** running in Docker Compose
and the application either as a JVM jar (systemd) or its own container.

Sources of truth: `README.md`, `docker-compose.yaml`, `Dockerfile`, `src/main/resources/application.yaml`,
`docs/data-model-decisions.md`. Verify against the running artifact before making changes.

---

## 0. Topology and entry points

```
client ──► [NGINX/Caddy :443] ──► url-shortener instances (HTTP, no TLS, stateless — ADR 0001)
                                     ├── url-shortener@1 :8080 ─┐
                                     ├── url-shortener@2 :8081 ─┼── nginx upstream (weights)
                                     └── ...                   │
                                                              ┌──┴─── shared attached resources
                                                              ▼
                                   MongoDB (urlshortener-mongo) :27017 / Redis :6379
```

- **Current production target:** single-host Docker Compose + Caddy on the deploy host
  (`deploy/compose/`, §1 → *Docker Compose deployment*), deployed by the `deploy.yml`
  workflow (§*Continuous Deploy*). The blue/green systemd topology below is the bare-metal
  path — still supported (`scripts/deploy.sh`), development/testing at this host.

  ```text
  browser ── HTTPS https://www.tyny.ca ──► caddy :80/:443  (only service with public ports)
                 │
                 ├── /, /login, /register, /links*, /admin/*, /assets/*, unknown UI routes
                 │        └──► /srv/frontend/current   (static SPA dist, FRONTEND_DIR mount)
                 ├── /api*, /actuator/{health/liveness,health/readiness,info}
                 │        └──► app:8080                (Java, host preserved)
                 ├── GET /[A-Za-z0-9_-]{1,64} (excl. SPA paths)
                 │        └──► app:8080                (302/404/410/429, never the SPA)
                 └── app:8080 ──► mongo:27017 + redis:6379 (loopback / internal net only)
  ```
- App routes: `POST /api/v1/urls` (shorten), `GET /{id}` (redirect), `/api/v1/auth/*`. All under
  internal ports `:8080+` (one per instance). Auth is `Authorization: Bearer <token>` for
  vanity/short-create; anonymous shorten is also allowed.
- Health: `GET /actuator/health`. Metrics/Prometheus: `/actuator/prometheus`.
- Working directory for all commands: repository root.
- Either run from a built jar (`./mvnw package`) or via Docker (`Dockerfile`). The repo bundles the
  **Maven wrapper** (`./mvnw`, 3.9.16) — no system Maven required.
- TLS termination is handled by a reverse proxy (NGINX or Caddy) — see §8.
- **Scale-out:** instances are stateless (12-factor §6/§8; per-IP rate limiting and the bloom/L2
  cache live in Redis, shared across instances — ADR 0002/0003). Add capacity by starting another
  instance and listing it in the nginx upstream (see §12).
- **Metrics-gated canary (Epic 25 / ADR 0012):** canary stages require a Prometheus 3.3.0
  instance scraping per-color `/actuator/prometheus` endpoints (blue `:8080`, green `:8081`)
  with `color` label and Operator BasicAuth. Install via `scripts/install-prometheus.sh`
  (systemd unit `prometheus.service`, loopback 127.0.0.1:9090, retention 30d + 8GiB,
  no admin API). The scrape password lives in
  `/etc/url-shortener/prometheus-scrape.password` (root:prometheus 0640 — group-readable
  by the service account only); the username is RENDERED into
  `/etc/prometheus/prometheus.yml` by the installer (Prometheus does not expand env vars
  in `basic_auth` fields; unprovisioned renders as `UNPROVISIONED` → scrapes 401,
  fail-closed). See `deploy/monitoring/prometheus.yml` (git-owned template) for the
  scrape config shape.

---

## 1. Deploy a new version (production)

Two supported production paths:

- **Bare metal (blue-green)** — the deploy script below, which downloads the exact tested
  artifact from the GitHub Release, validates the full artifact chain (JAR, SHA256SUMS,
  RELEASE-PROVENANCE.txt, RELEASE-EVIDENCE.json against the committed schema), and performs
  a canary cutover.
- **Docker Compose (current deploy host)** — single-host stack in `deploy/compose/`; see
  *Docker Compose deployment* below.

```sh
# 1. Ensure backing services are up
docker-compose up -d       # mongo + redis

# 2. Deploy (blue-green, metrics-gated canary 10/30/100 per ADR 0012)
#    - STEP 0: downloads JAR from GitHub Release + validates full artifact chain
#      BEFORE any host mutation (no runtime-conf --init, no service/nginx change)
#    - stages the SAME validated JAR into the idle color (single download; no re-fetch)
#    - canary stages: render → nginx -t → reload → smoke → METRICS_WINDOW_SECONDS window
#      (default 90s, contains former 30s dwell) → Prometheus canary gate
#    - gate signals: up==1, successful scrapes≥2 (sum_over_time), source freshness≤45s,
#      5xx<0.001, latency≥0.99, volume≥1; single evaluation (window is the bounded
#      wait); sticky FAIL; measured breach=fail-closed
#    - success: last-deploy.txt + drain old color + one-liner
sudo bash scripts/deploy.sh vX.Y.Z
```

Custom canary weights (must be strictly increasing integers 1–100 ending at 100;
each stage runs the full `METRICS_WINDOW_SECONDS` window + gate):
```sh
sudo bash scripts/deploy.sh vX.Y.Z --canary 5,25,50,100
```

**Local Maven builds and `target/*.jar` are NOT valid for production release deployment.**
They are for development/testing only. Production deployments MUST use artifacts
from the GitHub Release created by the CI pipeline (single-build, identity-verified
per ADR 0008 / Epic 21).

---

### Docker Compose deployment (current deploy host)

The current deploy host runs Docker Desktop (WSL2) with no application systemd units, so
production there is the single-host stack in `deploy/compose/` (`docker-compose.prod.yaml`):
Caddy publishes `:80`/`:443` (TLS, `tyny.ca` → `www.tyny.ca`), while Mongo, Redis, the app
and Prometheus are bound to `127.0.0.1` or kept on the internal `172.28.0.0/16` network.
Prometheus scrapes `/actuator/prometheus` with operator BasicAuth; daily verified Mongo
backups run from the user crontab. Full local guide: `deploy/compose/README.md`.

```sh
cd deploy/compose

# 1. First setup / after rotating OPERATOR_PASSWORD — fail-closed if .env is missing;
#    renders the git-ignored prometheus/operator_password (never echoes secrets)
bash bootstrap.sh

# 2. Start / refresh the stack — APP_IMAGE_TAG in .env pins the GHCR release semver
#    (ghcr.io/<owner>/<repo>:<semver>, pull_policy: always; unset tag = fail-closed)
docker compose -f docker-compose.prod.yaml up -d

# 3. Status and logs
docker compose -f docker-compose.prod.yaml ps
docker compose -f docker-compose.prod.yaml logs -f app

# 4. Rollback: re-dispatch "Deploy (production)" with the previous semver, or
#    do it by hand: sed -i 's/^APP_IMAGE_TAG=.*/APP_IMAGE_TAG=<previous-semver>/' .env && \
#    docker compose -f docker-compose.prod.yaml up -d
```

Post-deploy verification is one command (8 business legs with the Host mirror,
host-mirror negative, Prometheus target, and — when given — image identity):

```sh
bash scripts/smoke-compose.sh --expect-image ghcr.io/<owner>/<repo>:<semver>
```

Manual alternative for the Prometheus leg only:

```sh
curl -s 'http://127.0.0.1:9090/api/v1/targets?state=active' | grep -o '"health":"[a-z]*"'
```

Note the **host mirror**: `GET /{id}` only resolves when the `Host` header equals
`app.domain.default-host` (`www.tyny.ca`). Probing with the loopback `Host` returns the
**generic** `404` body (`URL Not Found` — deliberately indistinguishable from a missing
code, anti-enumeration) while the app log records
`Rejecting id=… on unbound host <ip>`. Public HTTPS requires the DNS prerequisite
documented in `deploy/compose/README.md` before `bash scripts/smoke.sh https://www.tyny.ca`
can pass.

#### Continuous Deploy — `deploy.yml` (Actions → production runner)

Deploys are dispatched, never pushed from CI:

1. **Trigger:** Actions → *Deploy (production)* → `workflow_dispatch` with `tag`
   (`X.Y.Z`, no `v`). Only repo write access can dispatch it; the workflow never runs
   on `pull_request` (public-repo hardening for the production runner).
2. **Gate:** the `production` environment holds the run until the required reviewer
   approves; `concurrency: deploy-production` serializes deploys (queued, never cancelled).
3. **Runner:** self-hosted with labels `self-hosted, prod-host`, registered on the deploy
   host (`DEPLOY_DIR` at the top of `deploy.yml` must point at the host's clean `main`
   checkout — the preflight fails closed on a dirty or switched-branch checkout).
4. **Steps:** validate semver → preflight (checkout clean/on `main`, `ff-only` pull,
   `.env` keys, `docker compose config`) → `docker pull` the GHCR image (fail-closed if
   the tag does not exist) → pin `APP_IMAGE_TAG` in the host `.env` →
   `compose up -d --no-deps app --wait` → `scripts/smoke-compose.sh --expect-image <ref>`.
5. **Rollback:** if anything after the pin fails, the run restores the previous
   `APP_IMAGE_TAG`, re-ups the app and re-smokes it (fail-closed: the run stays red even
   when the rollback succeeds). Preflight reports `rollback_supported=false` when the
   previous tag has no GHCR image (first CD deploy has no rollback target).

Production runs the GHCR release image pinned by the last dispatch (currently
`APP_IMAGE_TAG=0.18.0`, verified live on 2026-10-06: 11-leg smoke + image identity). The
manual fallbacks above only work with a semver that exists in GHCR — the retired local tag
`prod` (pre-CD image) is not pullable.

#### Edge routing & static frontend (SPA)

The Caddy edge (`deploy/compose/Caddyfile`) is the only service with public ports and
splits the canonical host between the static SPA and the Java API — both on
`https://www.tyny.ca`, so the frontend calls `/api` on its own origin
(`VITE_API_BASE_URL` stays empty in `url-shortener-web`; never ship a
`localhost`/direct-API base URL in the bundle). The routing law is owned by
`url-shortener-web` → `docs/deploy.md`; the edge implements it in this order:

| # | Request (Host: `www.tyny.ca`) | Goes to | Notes |
| - | ----------------------------- | ------- | ----- |
| 0 | `tyny.ca/*` (apex) | `308 → https://www.tyny.ca{uri}` | canonical host |
| 1 | `/api*` | Java `app:8080` | `Host`/`X-Forwarded-*` preserved; edge is the trusted proxy |
| 2 | `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info` | Java | public-by-policy allowlist |
| 3 | any other `/actuator*` | **404 at the edge** | metrics/health-details never published; Prometheus scrapes `app:8080` internally, operator endpoints via loopback/SSH |
| 4 | `GET/HEAD ^/[A-Za-z0-9_-]{1,64}$` excluding `/login /register /links /api /actuator` | Java | preserves `302/404/410/429`; **never** falls through to `index.html` |
| 5 | `/`, `/login`, `/register`, `/links`, `/links/*`, `/admin/*`, `/assets/*`, other multi-segment UI routes | SPA `dist/` | `try_files {path} /index.html` (deep links survive refresh) |

Single-segment paths outside the exclusion list are treated as short codes/aliases and
answered by Java (a non-existent one returns the backend `404`, deliberately **not**
`index.html`). New single-segment SPA routes must be added to the exclusion list **and**
to `ReservedWordsValidator` (keep the routing law's two lists in sync).
Rule 3 deviates from the web repo's law (which proxies `/actuator*` wholesale) — a
follow-up must sync `url-shortener-web/docs/deploy.md`.

**Static artifact layout** (host, never in Git; `FRONTEND_DIR` in
`deploy/compose/.env`, bind-mounted read-only into Caddy at `/srv/frontend`):

```text
$FRONTEND_DIR/
├── releases/<version>/      # dist/ contents + VERSION file
└── current -> releases/<version>   # atomic symlink; Caddy serves it live
```

Deploy and rollback are one script — no rebuild, no `compose up`, no Caddy reload, no
touch on backend data or `APP_IMAGE_TAG`. **Ownership of the artifact lifecycle lives in
`url-shortener-web`** (the frontend CD: `scripts/deploy-frontend.sh`,
`scripts/smoke-web.sh`, `.github/workflows/deploy-web.yml`); this backend repo only hosts
the edge config that serves the result:

```sh
bash url-shortener-web/scripts/deploy-frontend.sh --placeholder  # edge prep (minimal page)
bash url-shortener-web/scripts/deploy-frontend.sh vX.Y.Z         # gh release download + sha256 verify + flip
bash url-shortener-web/scripts/deploy-frontend.sh --rollback vX.Y.Z  # flip back to an extracted release
bash url-shortener-web/scripts/deploy-frontend.sh --current      # what is live (readlink + VERSION)
```

(While the move is in flight both repos may carry the script; the web copy is canonical.)

- **Artifact:** GitHub Release of `url-shortener-web` (tag `vX.Y.Z` → its `release.yml`
  builds `dist/`, publishes `url-shortener-web-<tag>.tar.gz` + `SHA256SUMS` + SBOM).
  The script fails closed unless `sha256sum -c SHA256SUMS` passes.
- **Health/version:** `readlink $FRONTEND_DIR/current` + `cat .../VERSION`; edge check =
  `curl -k -H 'Host: www.tyny.ca' https://127.0.0.1/` → `200 text/html` (no `-k` once
  DNS/TLS is cut over).
- **Edge-config changes** (Caddyfile/compose): normal PR + CI; applying on the host is
  `docker compose -f docker-compose.prod.yaml up -d caddy` (recreates when mounts change —
  seconds of edge downtime, harmless while DNS still parks the domain). Validate first with
  `docker run --rm -v "$PWD/deploy/compose/Caddyfile:/etc/caddy/Caddyfile:ro" caddy:2-alpine caddy validate`.
  Rollback = revert the PR and recreate.

**Owners / approvals:**

| Step | Who approves |
| ---- | ------------ |
| Backend tag + deploy (`deploy.yml` dispatch) | owner (GitHub `production` environment review) |
| Edge config PR (Caddyfile/compose/runbook) | owner (admin merge, 6 required checks) |
| Frontend tag `vX.Y.Z` in `url-shortener-web` | owner |
| Frontend CD (`deploy-web.yml` dispatch VX.Y.Z → `production` review → `deploy-frontend.sh` + `smoke-web.sh` on the `prod-host-web` runner) | owner (GitHub `production` environment review; deploy is machine-verified, rollback fail-closed) |
| DNS, port-forward, firewall, ACME email | **owner only** (never from CI) |

**DNS / TLS prerequisite (records only — no cutover without owner approval):**

```text
tyny.ca      A     <OWNER-PROVIDED-PUBLIC-IP>    # replace the GoDaddy parking records; TTL 300 during cutover
www.tyny.ca  CNAME tyny.ca            # keep as-is (resolves through the apex A)
AAAA         —                        # do NOT add: the host has no global IPv6
```

Sequence once the records and the 80/443 port-forward are in place (owner track): Caddy
serves `/.well-known/acme-challenge` on `:80` (automatic HTTP-01 for both hostnames) →
Let's Encrypt validates → certificate stored in the `caddy-data` volume → renewal is
automatic (~60/90-day cycle). After DNS lands, restart Caddy once
(`docker compose -f docker-compose.prod.yaml restart caddy`) and watch
`docker logs urlshortener-caddy` for `certificate obtained successfully`; if Let's
Encrypt reports `too many failed authorizations`, wait out the per-identifier rate limit
(≤1 h) and restart again.

**Read-only verification legs (approved scope — no data writes):**

```sh
H=(-H 'Host: www.tyny.ca'); B=http://127.0.0.1:8080
curl -sk "${H[@]}" https://127.0.0.1/                       # 200 text/html (SPA/placeholder)
curl -sk "${H[@]}" https://127.0.0.1/login                  # 200 index.html (deep link)
curl -sk "${H[@]}" https://127.0.0.1/links/whatever         # 200 index.html (fallback)
curl -sk "${H[@]}" https://127.0.0.1/api/v1/urls            # 401/404 JSON from Java
curl -sk "${H[@]}" https://127.0.0.1/aaaaaaaa               # 404 JSON from Java (never HTML)
curl -sk "${H[@]}" https://127.0.0.1/actuator/health/liveness   # 200
curl -sk "${H[@]}" https://127.0.0.1/actuator/prometheus        # 404 (denied at the edge)
curl -s  -H 'Host: tyny.ca'     http://127.0.0.1/           # 308 → https://www.tyny.ca
ss -ltn | grep -E ':(80|443|8080)'                          # 80/443 Caddy, 8080 loopback only
```

No `GET /{real code}` in this scope: a redirect writes a `click_event` (90-day
retention) — the `302` leg is already covered by the loopback smoke. Synthetic-data
checks require explicit owner authorization.

---

### Legacy single-instance deployment (development / testing only)

These methods are for local development, testing, or non-production environments.
They do NOT perform artifact validation, canary cutover, or traffic shifting.

**As a systemd service (bare metal dev):**

```sh
# 1. Copy the jar
sudo cp target/url-shortener-service-*.jar /opt/url-shortener/url-shortener.jar

# 2. Create env file (chmod 600, owned by urlshortener:urlshortener)
sudo mkdir -p /etc/url-shortener
sudo cp deploy/url-shortener.env.example /etc/url-shortener/url-shortener.env
# EDIT /etc/url-shortener/url-shortener.env with production values

# 3. Install systemd unit
sudo cp deploy/url-shortener.service /etc/systemd/system/url-shortener.service

# 4. Enable and start
sudo systemctl daemon-reload
sudo systemctl enable --now url-shortener
```

**As a container (development / testing):**

```sh
docker run -d --name urls \
  --network url-shortener-service_url-shortener-net \
  -p 8080:8080 \
  -e MONGODB_URI=mongodb://urlshortener-mongo:27017/url_shortener \
  -e REDIS_HOST=redis -e REDIS_PORT=6379 \
  -e APP_JWT_SECRET="$APP_JWT_SECRET" \
  -e APP_PUBLIC_BASE_URL="https://short.example.com" \
  url-shortener-service:VNEW
```

`APP_PUBLIC_BASE_URL` is the canonical public origin of the default short domain: every `shortUrl`
in API responses is built from it (custom-domain links use `https://<domain>` instead). It is
**required** in the `prod` profile — `ProdConfigValidator` aborts boot if missing or not `https://`.
Behind the edge, the request-derived fallback would otherwise leak the bind origin
(`http://127.0.0.1:8080/...`). See `docs/backend-frontend-contract.md` §1.

Post-deploy verification (applies to both paths):

```sh
curl -s http://localhost:8080/actuator/health/liveness          # 200 {"status":"UP"}
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com/very/long/path"}'   # 200, returns shortUrl
```

---

## 2. Roll back

**Via rollback script (blue-green, one command):**

```sh
# Reads deploy/runtime/last-deploy.txt (written by deploy.sh before first weight flip)
# Starts previous color, renders 100% weight to it, reloads nginx, runs smoke, prints incident one-liner
sudo bash scripts/rollback.sh
```

**Systemd / JVM process (legacy single-instance):**

```sh
sudo systemctl stop url-shortener
sudo mv /opt/url-shortener/url-shortener.jar /opt/url-shortener/url-shortener.jar.new
sudo mv /opt/url-shortener/url-shortener.jar.prev /opt/url-shortener/url-shortener.jar
sudo systemctl start url-shortener
```

**Container:** stop and remove the new container, then run the previous image tag. No traffic-shift
controller is required on a single host — restart the previous artifact.

A DB-level rollback is **not needed** for a code rollback (schema changes are additive by design —
see `data-model-decisions.md`).

Rollback is safe as long as the previous runnable artifact (jar or image) is retained.

---

## 3. Rotate secrets

All secrets live in the environment (`APP_JWT_SECRET`). Short codes do **not** use a salt.
Rotation procedure for the JWT signing secret:

```sh
NEW_SECRET="$(openssl rand -base64 48)"
# restart the service with the new secret:
#   systemd: systemctl restart url-shortener
#   docker:  docker stop urls && docker run ... -e APP_JWT_SECRET="$NEW_SECRET" ...
```

- Old JWTs remain valid until `app.jwt.expiration-ms` elapses (default 24 h); clients re-authenticate
  transparently. For a fast revoke, shorten the expiry and force re-login.
- Never put the production secret in Git, images or `application.yaml` — the bundled default is a
  dev-only example and the provider warns when it's used.
- Do not introduce a code-generation salt. Existing codes are opaque Base62 strings stored as `_id`;
  they are not reversible encodings of a counter.

---

## 4. When health is DOWN

```sh
curl -s http://localhost:8080/actuator/health | jq .
# {"status":"DOWN","components":{"mongo":{...},"redis":{...},"diskSpace":{...}}}
```

1. Identify the failing component from the response.
2. **`mongo` DOWN** — is the container running? `docker ps`; `docker logs urlshortener-mongo`.
   Is it reachable? `docker exec urlshortener-mongo mongosh --eval "db.runCommand({ping:1})"`.
   If the volume/data was lost, re-`docker-compose up -d` — but note this is data loss (see §6).
3. **`redis` DOWN** — `docker ps`; `docker logs redis`. Redis is a **best-effort cache**; the app
   should degrade to DB lookups (rate limiter fails open; cache misses fall through). Auth uses Redis
   for the rate limiter — confirm it tolerates a Redis outage rather than failing auth (fail-open where
   designed).
4. While a dependency is down, requests may 5xx. Once it recovers, health returns UP automatically —
   no restart needed (unless the app is crash-looping).

---

## 5. Incident response

**Container/JVM crash-looping**

```sh
docker ps                                        # state + health
docker logs --tail 200 url-shortener-service     # or journalctl -u url-shortener for systemd
```

- **Startup abort** mentioning a missing env var (`APP_JWT_SECRET` too short, `MONGODB_URI` missing):
  the configured env contract is incomplete. Fix the env, restart. (Enforced by `ProdConfigValidator`.)
- **`OOMKilled`**: check `docker inspect` `State.OOMKilled`; the compose file does not set a memory
  limit by default — investigate a leak before adding limits.
- **Port already in use**: confirm only one instance binds `:8080`.

**Requests return 5xx while health is UP**

- Check the app log for the exception class + URI (`GlobalExceptionHandler` logs method + URI). Common
  causes: Mongo/Redis connection refused, quota/rate-limit, a malformed short code.
- Confirm the short code exists: query Mongo directly
  (`docker exec urlshortener-mongo mongosh url_shortener --eval 'db.short_urls.findOne({_id:"<code>"})'`).

**MongoDB out of disk / slow**

- `df -h` on the host; MongoDB data dir is the compose volume. The read path (redirect) must stay fast
  — a full/slow disk degrades every redirect.

**Malformed / brute-forced short codes**

- The redirect path is rate-limited to counter enumeration. If you see a flood of 404s, check the rate
  limiter counters and the app access log; optionally tighten `rate-limiter.window` and add a firewall
  rule blocking hostile IPs. Never log the requested code as a secret — it's a public identifier.

**High latency / SLO breach**

- Check Prometheus: `redirect_latency_seconds_bucket{le="0.2"}`, `http_server_requests_seconds{uri="/{id}",status="2xx"}`.
- Compare against SLOs: redirect p95 < 40ms, availability 99.9%, shorten p95 < 150ms.
- If cache hit ratio < 90%, investigate Redis connectivity or eviction.

**Graceful shutdown verification** (after any change to shutdown/config)

```sh
bash scripts/verify-graceful-shutdown.sh
```

---

## 5b. Dependency-outage playbooks (validated in the Epic 7 drill, 2026-09-11)

Contracted behaviour lives in `docs/reliability.md` §1; these are the operator commands that were
actually executed against the isolated infra (`app:18080 / Mongo:27018 / Redis:6380`).

**Redis down (fail-open)**

```sh
# 1. confirm the outage
CURL="curl -s -o /dev/null -w '%{http_code}\n'"
$CURL http://localhost:8080/actuator/health/readiness   # DOWN (redis bucket)
docker stop redis                                       # or the outage finds you

# 2. client effect while down: redirects STILL 302 (rate-limit fail-open,
#    cache falls through to Mongo); latency grows; no 4xx/5xx spike
$CURL http://localhost:8080/<code>

# 3. recovery
docker start redis
docker exec redis redis-cli ping                          # PONG
$CURL http://localhost:8080/actuator/health/readiness   # UP
```
Drill result: 5,730 checks, **0% failed, 100% 302** across a 45s 150rps run with Redis stopped at
+20s; `http_req_duration` p95 744ms (`cache`/`rl` falls back to Mongo per ADR 0005), steady baseline
p95 back at ~10ms after restart. Matches matrix line "Redis down — rate limiter … fail-open" +
"cache L2 … answers from Mongo".

**MongoDB down, hot cache (partially degraded)**

```sh
docker stop urlshortener-mongo
for c in <warm-code>; do $CURL http://localhost:8080/$c; done   # 302: served from L1+L2 cache
```
Codes already resident in Caffeine L1 / Redis L2 keep answering **302**; only cache-miss/bloom-negative
codes degrade (see next).

**MongoDB down, cold cache (fail-closed, circuit-breaker)**

```sh
# app must be running and warm BEFORE the outage (schema migrator is fail-fast at boot)
docker exec <redis> redis-cli FLUSHALL      # drop L2/bloom so reads really hit Mongo
sleep 6                                     # L1 TTL (app.cache.l1-ttl)
docker stop urlshortener-mongo
# load the cold path: first ~5 calls wait the Mongo driver server-selection timeout (~30s),
# then databaseCb opens and calls fast-fail 503;
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/<cold-code>   # 503 after CB open
```
Drill result: 60s run, 4,504 reqs, `http_req_failed` **100%**, `http_req_duration` p50 3.6ms /
p95 6.9ms / p99 27s — the p99 tail is the CB sampling window, the p50 is the fast-fail OPEN state;
`databaseCb` transitions confirmed in the app log. Matches matrix "MongoDB down … fast failures
surface as 503".

**Recovery**

```sh
docker start urlshortener-mongo
# no restart needed: CB goes HALF_OPEN after 20s, probe calls pass, service self-heals
sleep 25
$CURL http://localhost:8080/<code>   # 302 again (verified)
```

**Restore mapping data (DR rollback of short_urls)**

```sh
# backup (host needs mongodump in PATH; the drill ran it via the mongo container:
#   docker exec urlshortener-mongo-isolated mongodump --uri=mongodb://127.0.0.1:27017/url_shortener \
#     --db=url_shortener --out=/tmp/epic7-backup --gzip && docker cp <container>:/tmp/epic7-backup .)
bash scripts/backup-mongodb.sh            # -> /var/backups/url-shortener/<timestamp>/

# simulate loss
docker exec urls-mongo mongosh url_shortener --eval 'db.short_urls.drop()'   # 404 on cold reads
# restore
bash scripts/restore-mongodb.sh /var/backups/url-shortener/<timestamp>/      # 302 again
```
Drill result: drop → cold-read 404 → `mongorestore` → **302** restored; pre-backup codes intact,
codes created after the dump stay absent (expected, RPO = last backup). `click_events` "restore
failures" are non-issues: that collection was never dropped, so existing `_id`s are skipped.

---

## 6. Routine operations & data durability

| Task                        | Command                                                       |
| --------------------------- | ------------------------------------------------------------- |
| Stack status                | `docker ps` (or `systemctl status url-shortener`)            |
| App logs (container)        | `docker logs -f url-shortener-service`                       |
| App logs (systemd)          | `journalctl -u url-shortener -f`                             |
| App logs (JSON profile)     | `journalctl -u url-shortener -f -o json | jq .`            |
| Mongo backups (dump)        | `bash scripts/backup-mongodb.sh` (output to `/var/backups/...`) |
| Mongo restore (drill)       | `bash scripts/restore-mongodb.sh /var/backups/url-shortener/20260827-120000` |
| Redis persistence           | Redis is configured with `--appendonly yes`; back up the AOF/dir volume |
| Host reboot recovery        | Containers use `restart: unless-stopped`; systemd `Restart=on-failure`; verify with `docker ps` after boot |
| Docker Desktop restart broke bind mounts (services exit `127`) | `cd deploy/compose && docker compose -f docker-compose.prod.yaml up -d` — re-registers the file mounts (`mongo-init.js`, `Caddyfile`, Prometheus/OTel configs); named volumes untouched. See `deploy/compose/README.md` §Troubleshooting |

**Data durability (operator responsibility):**

- MongoDB data is the **single source of truth** for mappings. Back it up on a schedule
  (`scripts/backup-mongodb.sh` + off-host copy) and document a restore drill.
- Redis is a **cache + rate limiter + analytics queue**. Persistence (`appendonly yes`)
  mitigates loss, but it is not the source of truth for mappings; treat a Redis reset as acceptable.
- **Retention purge** for `click_events` runs daily at 02:00 UTC (configurable via
  `app.analytics.retention-cron`), deleting events older than `app.analytics.retention-days`
  (default 90 days) in batches. Metrics: `analytics.retention.purged.total`.

---

## 7. Operational checklist before a release

- [ ] `./mvnw clean package` succeeds (and `./mvnw verify` + `*IT` green when tests changed).
- [ ] `bash scripts/check-boundaries.sh` passes (architecture boundaries intact).
- [ ] `bash scripts/check-living-spec.sh` passes (requirement traceability intact).
- [ ] `APP_JWT_SECRET` is set to a strong random value (≥32 chars); the default is not used.
- [ ] `APP_PUBLIC_BASE_URL` is set to the canonical public origin (e.g. `https://short.example.com`) — boot aborts in `prod` without it.
- [ ] `APP_DOMAIN_DEFAULT_HOST` equals the host of `APP_PUBLIC_BASE_URL` — the redirect serves
      default-host links only for that host (a mismatch serves `404` on every redirect).
- [ ] If custom domains are served: the **Caddy edge** (`deploy/proxy/Caddyfile`) is deployed and
      `EDGE_ASK_TOKEN` is set to the same value on the edge (rendered into the ask URL) and on
      the app — it gates on-demand TLS issuance per customer domain (unset = fail closed, no
      custom-domain certificate can be provisioned). See `docs/custom-domain-edge.md`.
- [ ] `MONGODB_URI` / `REDIS_HOST` / `REDIS_PORT` point at the real services.
- [ ] `rate-limiter.trusted-proxy-cidrs` matches the reverse proxy network CIDR.
- [ ] `management.otlp.tracing.endpoint` points at the OTel Collector.
- [ ] `app.analytics.retention-days` is set (default 90).
- [ ] MongoDB backup taken (or confirmed recent) before schema-changing deployments.
- [ ] Health probe returns UP after deploy; a smoke shorten + redirect works.
- [ ] Previous artifact retained for rollback.
- [ ] Secrets never appear in logs or Git.
- [ ] Tag is annotated (`git tag -a vX.Y.Z -m "..."`); `## [Unreleased]` in `CHANGELOG.md` is **empty** at the tag commit (promoted in the same commit).
- [ ] `Release Finalizer` ran and the release is **published** with `RELEASE-EVIDENCE.json` + `RELEASE-EVIDENCE.md` attached (if only a draft exists, the finalizer failed or is still running — investigate before using the tag).
- [ ] DoD for the release cites the evidence report and originating run URL instead of manually copied hashes/timestamps/image IDs.
- [ ] Schema migrations since the tag recorded in `last-deploy.txt` are **expand-only** (no destructive drops/renames) — the migrator is fail-fast, but a destructive migration would break the old color still serving during the cutover.
- [ ] MongoDB backup is recent (< 26h); restore drill (`scripts/ci-restore-drill.sh`) passed in the release pipeline.

### 7a. Rollback triggers (no discussion)

Roll back immediately when any trigger fires — do not debug first; roll back, then debug
(ADR 0007: the old color is intact by construction):

- **Smoke probe fails at any canary stage** — automatic: `scripts/deploy.sh` aborts to the old
  color at 100% and exits non-zero naming the failed step. If the abort fired, the rollback is
  already done; file the incident.
- **5xx ratio > 1% of requests for 2 minutes** after reaching the 100% bump.
- **p95 latency > 2× the published baseline** (docs/load-test-baseline.md) for 5 minutes.

### 7b. Rollback rehearsal (monthly)

An untested rollback is a hope. Once a month, on the lowest-risk prod-like host available (the
UAT compose stack when up, else staging):

1. Deploy the newest tag **behind** the active one (the idle color), without weight flip.
2. Run `scripts/rollback.sh` back to the active one.
3. Run `scripts/smoke.sh` — all legs green.
4. Record the duration in the table below (the record is the deliverable — same pattern as
   `ci-restore-drill.sh`).

| Date | Host | Tag in/out | Duration | Operator |
|------|------|------------|----------|----------|
| _(first entry pending)_ | | | | |

---

## Release artifacts & promotion

The CI `release.yml` pipeline produces a GitHub Release on every tag `v*`. **Artifact identity
and single-build promotion (Epic 21, ADR 0008):** every job is pinned to the trigger tag — 
`bash scripts/verify-release-artifact.sh --peel` resolves `refs/tags/<tag>^{commit}` and fails unless it
equals that job's checkout HEAD, **and verifies the tag is an annotated tag (not lightweight)**,
and one and only one `./mvnw verify -Drevision=<semver>` run produces the release candidate.

1. **gates** job: full `./mvnw verify -Drevision=<semver>` (the **only** build in the pipeline)
   + all bash gates + promtool/amtool + CHANGELOG gate. Captures exactly one candidate, writes
   `RELEASE-PROVENANCE.txt` (KEY=VALUE: `repository`, `tag`, `semver`, `commit`, `run_id`,
   `run_attempt`, `jar`, `sha256`) and `SHA256SUMS`, self-verifies them, and uploads all three as
   the `release-candidate` artifact (30-day retention).
2. **k6-gate** (needs gates): downloads the *same-run* `release-candidate`, verifies the
   provenance/hash, then `k6 run load-tests/mixed.js` against that exact JAR (thresholds
   `p95 < 200ms`, `error < 0.1%`).
3. **runtime-smoke** (needs gates): downloads + verifies the candidate, then `scripts/smoke.sh` +
   `scripts/verify-graceful-shutdown.sh` against it.
4. **restore-drill** (needs gates): downloads + verifies the candidate and passes its path via the
   `JAR` env var to `scripts/ci-restore-drill.sh` (RTO ≤ 300s, RPO = last backup).
5. **release** (needs all): downloads + verifies the candidate, packages it **without rebuilding**
   via the single-stage `Dockerfile.release` (copies the candidate as `app.jar`; no Maven), proves
   the image-embedded JAR SHA-256 equals the candidate's (extract + hash-compare, fail-closed),
   non-root gate + Trivy HIGH/CRITICAL SHA-pinned + CycloneDX SBOM on that image, records the
   image digest/id, **pushes that same image to GHCR**
   (`ghcr.io/<owner>/<repo>:<semver>`, `packages: write`, semver tag only — only after every scan
   gate passes), and creates the GitHub Release **as a draft** with assets:
   - `url-shortener-service-<semver>.jar` (the exact tested candidate; the only JAR ever published)
   - `SHA256SUMS` (sha256 of the jar, relative-path format for `sha256sum -c` and `deploy.sh` grep)
   - `RELEASE-PROVENANCE.txt` (full provenance: tag, semver, source commit, run id/attempt, jar, sha256)
   - `sbom-url-shortener-<semver>.json` (CycloneDX SBOM; subject = the verified candidate image)

Artifact promotion is **single-build and identity-checked at every hop**: the jar uploaded by
`gates` is byte-for-byte what `k6-gate`, `runtime-smoke`, `restore-drill`, and `release` verify
and exercise, and each hop cross-checks repository, tag, source commit, run id, semver, filename
and SHA-256. There is never a fallback build. `deploy.sh <tag>` downloads the jar from the
Release, verifies the sha256 against `SHA256SUMS`, and stages it — a local rebuild is never a
deploy source. The **same rule holds for containers**: the Compose production stack pulls only the
GHCR image the `release` job published (`deploy/compose/`, `APP_IMAGE_TAG` = release semver,
`pull_policy: always`), and never builds locally.

### Draft → finalizer → publish (Epic 22 — machine-generated release evidence)

The Create Release step makes the release **a draft only**; publication is owned by the
**Release Finalizer** workflow (`release-finalizer.yml`, triggered by `workflow_run` when the
`Release` workflow completes successfully, resolved from the default branch):

1. Downloads the five **same-run receipts** (`receipt-{gates,k6-gate,runtime-smoke,restore-drill,
   release}`, 90-day retention) by the originating run id into `receipts/`.
2. Verifies the annotated tag peels to the originating commit
   (`scripts/verify-release-artifact.sh --tag-resolves`), then checks out the tag so the finalizer
   scripts run **from the tag commit**.
3. Runs `scripts/finalize-release-evidence.sh --origin-run-id <id>`, which:
   - rejects non-`Release`/non-push/in-progress/failed/cross-run/mismatched-repo events (`EV-TRIGGER`);
   - re-fetches the run and job list from the Actions API and requires **5/5 jobs success**;
   - validates the release is still a draft, the 5 receipts bind to one tag/commit/run and agree on
     the candidate SHA-256, and the draft assets match the receipts (jar, `sha256sum -c`
     `SHA256SUMS`, `RELEASE-PROVENANCE.txt`, CycloneDX SBOM whose subject = the recorded image id
     and embedded JAR = the candidate);
   - generates and validates `RELEASE-EVIDENCE.json` (canonical) + `RELEASE-EVIDENCE.md`
     (rendered) **from observed facts only**;
   - attaches both evidence files to the draft, then publishes (`--draft=false`) — publish is the
     **last** action; the automated draft body is replaced by the rendered evidence report.
   Failures exit non-zero with named `EV-*` codes and leave the release published nowhere.
   Already-finalized publishing is idempotent (no-op, exit 0).

**Release lifecycle:** a tag push leaves a draft behind until the finalizer has re-verified all
evidence. Any `EV-*` failure means the tag did not produce a verified release — fix forward with a
new tag (the old release stays a draft, never visible). Do **not** manually flip a draft release to
published: the empty/promotional body and unreported asset state would make the next finalizer run
fail-closed.

**Retrieving the report:** every published release carries `RELEASE-EVIDENCE.json` (machine record)
and `RELEASE-EVIDENCE.md` (rendered summary) as assets, naming the originating run and its jobs.
When closing an epic review or a release, cite the evidence report and the originating run URL
(`<repo>/actions/runs/<run-id>`); do **not** copy per-release hashes, timestamps, image IDs, or job
outcomes into narrative text — the report is generated from observed facts and is the source of truth.

Tag immutability: a bad release is fixed forward with `vX.Y.Z+1`; a tag is never moved or deleted
(moving a tag invalidates every recorded sha256, the `commit` provenance field, and the Release
that references it). Auth is `GITHUB_TOKEN` only. The `latest` Docker tag is **not** used as
release identity (the release image is tagged with the semantic version only).

---

## Incident: deploy failed

When `scripts/deploy.sh` aborts (fail-closed: old color restored to 100%, new color drained/stopped, exit non-zero naming the offending step):

1. **Read the abort line** — it names the exact step (download, sha256, readiness, `nginx -t`, smoke).
2. **Inspect the new color's log** (systemd journal: `journalctl -u url-shortener-<color>`) for the root cause.
3. **Run the smoke probe manually** against the old color (still at 100%): `scripts/smoke.sh http://<front>` — must be all green.
4. **Rollback if needed** — `scripts/rollback.sh` (reads `last-deploy.txt`, flips weights back, prints incident one-liner). This is a weight flip, no rebuild.
5. **Post-mortem** — if the failure was infrastructure (Mongo/Redis down, network), follow the playbooks in §5b. If code, fix forward with a new tag (`vX.Y.Z+1`) and re-run the pipeline.

**Do not** attempt to re-deploy the same tag after a failed deploy — the pipeline is fail-closed by design; a fix requires a new tag.

---

## 8. TLS termination (reverse proxy)

### 8.1 NGINX

Configuration at `deploy/proxy/nginx.conf`. The proxy:
- Terminates TLS on `:443` (cert/key at `/etc/nginx/certs/`)
- Forwards to app on `127.0.0.1:8080` (HTTP)
- Sets `X-Forwarded-For`, `X-Forwarded-Proto`, etc.
- App must trust the proxy via `rate-limiter.trusted-proxy-cidrs`

```sh
# Install and start
sudo cp deploy/proxy/nginx.conf /etc/nginx/nginx.conf
sudo nginx -t && sudo systemctl reload nginx
```

### 8.2 Caddy (simpler, auto-HTTPS)

Configuration at `deploy/proxy/Caddyfile`. Replace `short.example.com` with your domain.

```sh
# Run directly or as a service
caddy run --config deploy/proxy/Caddyfile
```

### 8.3 Compose edge (current deploy host)

The live edge is `deploy/compose/Caddyfile` — TLS termination plus the SPA/API route
table of §1 → *Edge routing & static frontend (SPA)*, which also holds the DNS records,
the HTTP-01 issuance sequence and the read-only TLS verification legs.
`deploy/proxy/*` remains the bare-metal alternative.

---

## 9. SLOs & observability

| SLO | Target | Backing metric |
|-----|--------|----------------|
| Redirect latency p95 | < 40 ms | `redirect.latency` (p95) |
| Redirect availability | 99.9% | `http_server_requests_seconds{uri="/{id}"}` 2xx rate |
| Shorten latency p95 | < 150 ms | `shorten.latency` (p95) |
| Cache hit ratio | > 90% | `cache.hits` / (`cache.hits` + `cache.misses`) |

Metrics exposed at `/actuator/prometheus`. Grafana dashboards in `dashboards/`.
Recording rules and alerts in `deploy/monitoring/`.

---

## 10. Performance baseline

```sh
bash scripts/performance-baseline.sh 1m 200 20
```

Runs k6 load tests against the redirect path with thresholds-as-code (p95 < 200ms, error rate < 0.1%).
Results stored in `load-tests/results/`. Compare against the baseline in `docs/load-test-baseline.md`.

---

## 11. Click events retention

The `ClickEventsRetentionPurge` scheduled job runs daily at 02:00 UTC (configurable via
`app.analytics.retention-cron`) and deletes events older than `app.analytics.retention-days`
(default 90 days) in batches of 1000, with a 5-minute max run time.

Metrics:
- `analytics.retention.purged.total` — total events deleted
- `analytics.retention.runs.total` — purge executions
- `analytics.retention.errors.total` — purge errors

---

## 12. Multi-instance operation (scale-out, ADR 0001)

Instances are **stateless**: JWT auth, shared Redis (rate limit, bloom, L2 cache, analytics
stream) and shared MongoDB mean any instance can serve any request. The per-IP rate-limit
budget is **global** (Redis token bucket — ADR 0002); the only per-instance state is the
tiny Caffeine L1 (≤5s staleness — ADR 0003).

### 12.1 Add an instance (scale-out)

```sh
# 1. Install the TEMPLATE unit (once) — instantiate per instance
sudo cp deploy/url-shortener@.service /etc/systemd/system/url-shortener@.service
sudo systemctl daemon-reload

# 2. Start instance 2 (binds :8081 automatically: -Dserver.port=808%i -> 8080,8081,...)
sudo systemctl enable --now url-shortener@2
#    optional per-instance env: /etc/url-shortener/url-shortener@2.env (port override wins there)

# 3. Add the peer to the nginx upstream (deploy/proxy/nginx.conf as reference)
#      upstream url_shortener_backend {
#          server 127.0.0.1:8080 weight=100 max_fails=2 fail_timeout=10s;
#          server 127.0.0.1:8081 weight=100 max_fails=2 fail_timeout=10s;
#      }
sudo nginx -t && sudo systemctl reload nginx

# 4. Verify: both instances healthy, LB round-robins
curl -s http://localhost:8080/actuator/health/liveness
curl -s http://localhost:8081/actuator/health/liveness
sudo journalctl -u url-shortener@2 -f --no-pager | head
```

### 12.2 Canary release via weight flip (legacy / development only)

> **This section describes a manual development/testing workflow.**
> For production releases, use `scripts/deploy.sh <tag>` (see §1) which
> automates artifact validation, staging, health checks, and traffic shifting.

This method deploys a locally-built JAR to one instance and shifts traffic
manually — useful for local testing or non-production environments.

```sh
# 1. Roll instance 2 to the NEW artifact (instance 1 keeps serving old)
sudo systemctl stop url-shortener@2
sudo cp target/url-shortener-service-*.jar /opt/url-shortener/url-shortener.jar   # local build
sudo systemctl start url-shortener@2
curl -s http://localhost:8081/actuator/health/readiness    # wait UP

# 2. Flip weights in nginx upstream (10 -> 30 -> 100), reloading between steps
#      server 127.0.0.1:8080 weight=90;   server 127.0.0.1:8081 weight=10;
sudo nginx -t && sudo systemctl reload nginx
#    watch: curl -s :443 metrics / Grafana; error budget burn OK? proceed; else flip back.

# 3. Finish: instance 1 gets the new jar too, restore equal weights
sudo systemctl stop url-shortener@1 && sudo cp ... && sudo systemctl start url-shortener@1
```

An unhealthy peer is dropped automatically after 2 failures in 10s
(`max_fails=2 fail_timeout=10s`) — a down instance never blocks the flip.

**Key differences from production `deploy.sh`:**
- Uses local `target/*.jar` (no Release artifact validation, no SHA256 check)
- No automated readiness wait with budget, no smoke probe, no dwell enforcement
- No `last-deploy.txt` record for rollback automation
- Manual nginx weight editing instead of `deploy.sh` render/reload/smoke automation

### 12.3 Docker image artifact

```sh
docker build -t url-shortener:sha-$(git rev-parse --short HEAD) .
docker images | grep url-shortener    # tag by source sha; reference build (2026-09-11,
                                      # sha-583832b): 302MB = JRE-alpine base (198MB)
                                      # + fat jar (~77MB). <150MB requires a jlink custom
                                      # runtime (not adopted — see epic-6-dod.md).
```

Shared-resource note: each instance adds one Mongo connection pool and one Redisson client
to the backing services — watch connection counts as N grows.
---

## Governance reference (T5 — Dargent recorded, AGENTS.md authoritative)

**Reference policy (Dargent documentation):** English-only repository content; doc/CHANGELOG
synchronization with code changes; focused Conventional Commits using `feat/`, `fix/`, and
`chore/` branch prefixes; small, one-concern pull requests with green, unskipped CI when a PR
is used; annotated semver tags created only after Definition of Done; and evidence tied to the
tagged commit.

**Dargent E3.5 exception (recorded as documented):** the reference process preserves direct
pushes to the default branch and does not require a PR-only flow or required status checks; it
describes protecting branches by blocking force-pushes and branch deletions. The E3.5 backlog
item is **open** — the GitHub branch-protection setting was not verified as enabled. The
repository prescribes no squash, rebase, or merge-commit strategy.

**Reconciliation with this service (binding):** `AGENTS.md` is authoritative over the Dargent
reference. In particular — pushes and annotated tags require **explicit human authorization**;
no Dargent direct-push exception, PR-only rule, branch-prefix rule, or merge strategy is
imposed on `url-shortener-service` by this epic. Release evidence is tied to the tagged commit,
matching the identity chain in §"Release artifacts & promotion".

---

*Last updated: 2026-10-02 (Epic 24 — production deploy hardening: artifact validation gate,
  canary argument safety, runbook reconciliation; deploy.sh validate-release integration,
  canary arg constraints, runbook §1/§12.2 updated; deploy hardening #2 — validation precedes
  any host mutation, same validated JAR staged via --output-jar, self-test exercises the real
  CLI argument parser, deploy self-test wired into CI)*
