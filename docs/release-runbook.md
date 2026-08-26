# URL Shortener Service — Operational Release Runbook

How to operate this service in production without having written the code. This runbook targets the
current platform: **on-premises bare metal**, with **MongoDB** and **Redis** running in Docker Compose
and the application either as a JVM jar (systemd) or its own container.

Sources of truth: `README.md`, `docker-compose.yaml`, `Dockerfile`, `src/main/resources/application.yaml`,
`docs/data-model-decisions.md`. Verify against the running artifact before making changes.

---

## 0. Topology and entry points

```
operator/host :8080 ──► url-shortener-service (app)
                              ├── MongoDB (urlshortener-mongo) :27017
                              └── Redis    (redis)             :6379
```

- App routes: `POST /api/v1/urls` (shorten), `GET /{id}` (redirect), `/api/v1/auth/*`. All under
  `:8080`. Auth is `Authorization: Bearer <token>` for vanity/short-create; anonymous shorten is also
  allowed.
- Health: `GET /actuator/health`. Metrics/Prometheus: `/actuator/prometheus`.
- Working directory for all commands: repository root.
- Either run from a built jar (`mvn package`) or via Docker (`Dockerfile`). There is **no Maven
  wrapper** (`./mvnw`).

---

## 1. Deploy a new version

### 1a. Build

```sh
mvn clean package                 # JVM jar  -> target/url-shortener-service-0.0.1-SNAPSHOT.jar
# or, for a container image:
docker build -t url-shortener-service:VNEW .
```

> **Native build is currently broken** (`native` profile `mainClass` points to
> `.infra.Application`, which doesn't exist). Use the JVM jar until fixed. See `AGENTS.md` debt item 11.

### 1b. Start the backing services (once, or if not running)

```sh
docker-compose up -d       # mongo + redis
```

### 1c. Run / deploy the application

**As a JVM process (recommended for a single host):**

```sh
export MONGODB_URI=mongodb://localhost:27017/url_shortener
export REDIS_HOST=localhost REDIS_PORT=6379
export APP_JWT_SECRET="$(openssl rand -base64 48)"
java -jar target/url-shortener-service-*.jar
```

For a long-lived service, install a systemd unit (or a tiny supervisor script) that runs the jar,
with `Restart=on-failure` and the env vars above. Example unit (`/etc/systemd/system/urls.service`):

```ini
[Unit]
Description=URL Shortener Service
After=docker.service
Requires=docker.service

[Service]
User=urls
WorkingDirectory=/opt/url-shortener
Environment=MONGODB_URI=mongodb://localhost:27017/url_shortener
Environment=REDIS_HOST=localhost
Environment=REDIS_PORT=6379
Environment=APP_JWT_SECRET=<from-secret-store>
ExecStart=/usr/bin/java -jar /opt/url-shortener/url-shortener-service.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

**As a container:**

```sh
docker run -d --name urls \
  --network url-shortener-url-shortener-net \
  -p 8080:8080 \
  -e MONGODB_URI=mongodb://urlshortener-mongo:27017/url_shortener \
  -e REDIS_HOST=redis -e REDIS_PORT=6379 \
  -e APP_JWT_SECRET="$APP_JWT_SECRET" \
  url-shortener-service:VNEW
```

Post-deploy verification:

```sh
curl -s http://localhost:8080/actuator/health          # 200 {"status":"UP"}
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com/very/long/path"}'   # 200, returns shortUrl
```

---

## 2. Roll back

- **JVM process:** stop the new version and start the previous jar (keep the previous jar archived).
  Because it's in-process and stateless, a rollback is an immediate process swap with no data change.
- **Container:** stop and remove the new container, then run the previous image tag. No traffic-shift
  controller is required on a single host — restart the previous artifact.
- A DB-level rollback is **not needed** for a code rollback (schema changes are additive by design —
  see `data-model-decisions`).

Rollback is safe as long as the previous runnable artifact (jar or image) is retained.

---

## 3. Rotate secrets

All secrets live in the environment (`APP_JWT_SECRET`). Short codes do **not** use a salt.
Rotation procedure for the JWT signing secret:

```sh
NEW_SECRET="$(openssl rand -base64 48)"
# restart the service with the new secret:
#   systemd: systemctl restart urls
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
docker logs --tail 200 url-shortener-service     # or journalctl -u urls for systemd
```

- **Startup abort** mentioning a missing env var (`APP_JWT_SECRET` too short, `MONGODB_URI` missing):
  the configured env contract is incomplete. Fix the env, restart.
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

**Graceful shutdown verification** (after any change to shutdown/config)

```sh
# Send SIGTERM to the running process and confirm it drains in-flight requests before exit,
# respecting the configured grace period.
```

---

## 6. Routine operations & data durability

| Task                        | Command                                                       |
| --------------------------- | ------------------------------------------------------------- |
| Stack status                | `docker ps` (or `systemctl status urls`)                     |
| App logs (container)        | `docker logs -f url-shortener-service`                       |
| App logs (systemd)          | `journalctl -u urls -f`                                       |
| Mongo backups (dump)        | `docker exec urlshortener-mongo mongodump --out /dump` (then copy to durable storage) |
| Redis persistence           | Redis is configured with `--appendonly yes`; back up the AOF/dir volume |
| Host reboot recovery        | Containers use `restart: unless-stopped`; verify with `docker ps` after boot |

**Data durability (operator responsibility):**

- MongoDB data is the **single source of truth** for mappings. Back it up on a schedule
  (`mongodump` + off-host copy) and document a restore drill.
- Redis is a **cache + rate limiter + (future) analytics queue**. Persistence (`appendonly yes`)
  mitigates loss, but it is not the source of truth for mappings; treat a Redis reset as acceptable.
- Retention: implement a purge for `click_events` (and rely on TTL for expired links) so storage does
  not grow unbounded.

---

## 7. Operational checklist before a release

- [ ] `mvn clean package` succeeds (and `mvn verify` + `*IT` green when tests changed).
- [ ] `APP_JWT_SECRET` is set to a strong random value; the default is not used.
- [ ] `MONGODB_URI` / `REDIS_HOST` / `REDIS_PORT` point at the real services.
- [ ] MongoDB backup taken (or confirmed recent) before schema-changing deployments.
- [ ] Health probe returns UP after deploy; a smoke shorten + redirect works.
- [ ] Previous artifact retained for rollback.
- [ ] Secrets never appear in logs or Git.
