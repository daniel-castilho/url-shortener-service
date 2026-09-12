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

---

## 1. Deploy a new version

### 1a. Build

```sh
./mvnw clean package                 # JVM jar  -> target/url-shortener-service-0.0.1-SNAPSHOT.jar
./mvnw clean package -Pnative        # GraalVM native image (requires GraalVM + native-image)
```

### 1b. Start the backing services (once, or if not running)

```sh
docker-compose up -d       # mongo + redis
```

### 1c. Run / deploy the application

**As a systemd service (recommended for bare metal):**

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
  url-shortener-service:VNEW
```

Post-deploy verification:

```sh
curl -s http://localhost:8080/actuator/health/liveness          # 200 {"status":"UP"}
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com/very/long/path"}'   # 200, returns shortUrl
```

---

## 2. Roll back

- **Systemd / JVM process:** stop the new version and start the previous jar (keep the previous jar
  archived at `/opt/url-shortener/url-shortener.jar.prev`).
  Because it's in-process and stateless, a rollback is an immediate process swap with no data change:
  ```sh
  sudo systemctl stop url-shortener
  sudo mv /opt/url-shortener/url-shortener.jar /opt/url-shortener/url-shortener.jar.new
  sudo mv /opt/url-shortener/url-shortener.jar.prev /opt/url-shortener/url-shortener.jar
  sudo systemctl start url-shortener
  ```
- **Container:** stop and remove the new container, then run the previous image tag. No traffic-shift
  controller is required on a single host — restart the previous artifact.
- A DB-level rollback is **not needed** for a code rollback (schema changes are additive by design —
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
- [ ] `APP_JWT_SECRET` is set to a strong random value (≥32 chars); the default is not used.
- [ ] `MONGODB_URI` / `REDIS_HOST` / `REDIS_PORT` point at the real services.
- [ ] `rate-limiter.trusted-proxy-cidrs` matches the reverse proxy network CIDR.
- [ ] `management.otlp.tracing.endpoint` points at the OTel Collector.
- [ ] `app.analytics.retention-days` is set (default 90).
- [ ] MongoDB backup taken (or confirmed recent) before schema-changing deployments.
- [ ] Health probe returns UP after deploy; a smoke shorten + redirect works.
- [ ] Previous artifact retained for rollback.
- [ ] Secrets never appear in logs or Git.

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

### 12.2 Canary release via weight flip (zero downtime)

Deploy the new jar to **one** instance, then shift traffic gradually — `10 -> 30 -> 100`:

```sh
# 1. Roll instance 2 to the NEW artifact (instance 1 keeps serving old)
sudo systemctl stop url-shortener@2
sudo cp target/url-shortener-service-*.jar /opt/url-shortener/url-shortener.jar   # new
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

*Last updated: 2026-09-11 (Epic 6 — Scalable: multi-instance topology §0/§12, ADRs 0001–0004)*
