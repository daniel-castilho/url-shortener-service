# URL Shortener Service — Operational Release Runbook

How to operate this service in production without having written the code. This runbook targets the
current platform: **on-premises bare metal**, with **MongoDB** and **Redis** running in Docker Compose
and the application either as a JVM jar (systemd) or its own container.

Sources of truth: `README.md`, `docker-compose.yaml`, `Dockerfile`, `src/main/resources/application.yaml`,
`docs/data-model-decisions.md`. Verify against the running artifact before making changes.

---

## 0. Topology and entry points

```
client ──► [NGINX/Caddy :443] ──► url-shortener-service :8080 (HTTP, no TLS)
                                    ├── MongoDB (urlshortener-mongo) :27017
                                    └── Redis    (redis)             :6379
```

- App routes: `POST /api/v1/urls` (shorten), `GET /{id}` (redirect), `/api/v1/auth/*`. All under
  internal port `:8080`. Auth is `Authorization: Bearer <token>` for vanity/short-create; anonymous
  shorten is also allowed.
- Health: `GET /actuator/health`. Metrics/Prometheus: `/actuator/prometheus`.
- Working directory for all commands: repository root.
- Either run from a built jar (`mvn package`) or via Docker (`Dockerfile`). There is **no Maven
  wrapper** (`./mvnw`).
- TLS termination is handled by a reverse proxy (NGINX or Caddy) — see §8.

---

## 1. Deploy a new version

### 1a. Build

```sh
mvn clean package                 # JVM jar  -> target/url-shortener-service-0.0.1-SNAPSHOT.jar
mvn clean package -Pnative        # GraalVM native image (requires GraalVM + native-image)
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

- [ ] `mvn clean package` succeeds (and `mvn verify` + `*IT` green when tests changed).
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

*Last updated: 2026-08-27 (Operational Excellence epic)*