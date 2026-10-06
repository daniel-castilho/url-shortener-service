# Docker Compose production deployment

Single-host stack for the URL Shortener Service, `prod` Spring profile:

```
internet ──:80/:443──> caddy ──app:8080──> mongo:27017 (127.0.0.1 only)
                                     └───> redis:6379 (127.0.0.1 only)
                                     └───> otel-collector:4318 (traces, debug exporter)
   prometheus:9090 ──scrape──> app:8080/actuator/prometheus (operator BasicAuth)
```

Only Caddy publishes ports on the public interface. Mongo, Redis, the app and Prometheus
are bound to `127.0.0.1` (backups, local debugging) or kept on the internal network
(`172.28.0.0/16`, which is also the rate limiter's trusted-proxy CIDR).

## Files

| File | Purpose |
| --- | --- |
| `docker-compose.prod.yaml` | The stack (build context = repository root) |
| `Caddyfile` | TLS termination, `tyny.ca` → `www.tyny.ca`, Host passthrough |
| `.env.example` | Committed template — copy to `.env` and fill in real values |
| `.env` | **Secrets — chmod 600, git-ignored.** Never commit or copy into images |
| `bootstrap.sh` | Idempotent host bootstrap: verifies `.env`, renders `prometheus/operator_password` |
| `mongo-init.js` | Creates the app's `readWrite` DB user on first boot |
| `prometheus/prometheus.yml` | Scrape config (`operator_password` holds the BasicAuth secret) |
| `otel/otel-collector-config.yml` | OTLP receiver → tail sampling → `debug` exporter |
| `backup.env` | `MONGODB_URI` for `scripts/backup-mongodb.sh` (git-ignored) |

## First-time setup

```bash
cd deploy/compose
cp .env.example .env && chmod 600 .env   # fill in every CHANGE_ME value
bash bootstrap.sh                        # fail-closed: renders operator_password
docker compose -f docker-compose.prod.yaml up -d --build
```

`bootstrap.sh` is idempotent — re-run it after rotating `OPERATOR_PASSWORD`, then restart
Prometheus so it picks up the new file:

```bash
docker compose -f docker-compose.prod.yaml kill -s SIGHUP prometheus
```

## Day-to-day commands

```bash
# run from this directory
bash bootstrap.sh                               # re-render operator_password (safe to re-run)
docker compose -f docker-compose.prod.yaml up -d --build   # first deploy / rebuild
docker compose -f docker-compose.prod.yaml ps              # health of every service
docker compose -f docker-compose.prod.yaml logs -f app     # application logs
docker compose -f docker-compose.prod.yaml restart app     # restart after a config change
docker compose -f docker-compose.prod.yaml down            # stop (volumes are kept)
```

Rolling back to a previous build: `git checkout <ref> && docker compose -f
docker-compose.prod.yaml up -d --build`.

## Required environment (`prod` profile)

`ProdConfigValidator` aborts the boot (fail-closed) unless all of these are set in `.env`:
`APP_JWT_SECRET` (≥32 chars, non-default), `MONGODB_URI` (non-localhost host),
`REDIS_HOST` (non-localhost), `APP_PUBLIC_BASE_URL` (`https://…`), `OPERATOR_USERNAME`,
`OPERATOR_PASSWORD` (≥16 chars) and `APP_SECURITY_SWAGGER_ENABLED=false` (Swagger is
401/forbidden in prod by design). `APP_ADMIN_EMAILS` grants the product ADMIN role —
set your e-mail there.

Regenerate any secret with `openssl rand -base64 48` and restart the app container.

## Verification

```bash
# from the repository root — all 8 legs must PASS (liveness, readiness, info,
# shorten, redirect, HEAD, 404, 410)
bash scripts/smoke.sh https://www.tyny.ca

# operator-only observability endpoints
curl -u "$OPERATOR_USERNAME:$OPERATOR_PASSWORD" http://127.0.0.1:8080/actuator/prometheus | head
curl -s http://127.0.0.1:9090/api/v1/targets?state=active | grep -o '"health":"[a-z]*"'
```

Note: the redirect path enforces a **host mirror** — a link only resolves when the
`Host` header equals `app.domain.default-host` (`www.tyny.ca`). Requests to
`http://127.0.0.1:8080/<code>` return `404 Rejecting id=… on unbound host`, which is
correct behaviour, not a bug.

## TLS / DNS prerequisite

Caddy provisions certificates through Let's Encrypt HTTP-01, so **`tyny.ca` and
`www.tyny.ca` must point at this server's public IP** before HTTPS can work. Until DNS
is corrected, `docker logs urlshortener-caddy` shows ACME failures and the site is only
reachable with `curl -k`. After changing DNS:

```bash
docker compose -f docker-compose.prod.yaml restart caddy
docker logs -f urlshortener-caddy   # look for "certificate obtained successfully"
```

If Let's Encrypt reports `too many failed authorizations`, wait up to 1 hour (rate limit
per identifier) and restart Caddy again. Replace the placeholder ACME e-mail
(`ops@tyny.ca`) in `Caddyfile` first.

## Backups

A user crontab entry (no root needed) runs `scripts/backup-mongodb.sh` daily at **03:30**
into `/home/daniel/projects/urlshortener/backups` (30-day retention, `manifest.json` with
per-collection row counts; `mongodump` runs inside a throwaway `mongo:6.0` container).

```bash
crontab -l                                          # inspect the entry
ls -la /home/daniel/projects/urlshortener/backups   # latest dump + manifest
bash scripts/restore-mongodb.sh --verify <dir>      # verify a restore (see script usage)
```

## Monitoring

* Prometheus UI: `http://127.0.0.1:9090` (SLO recording rules + burn-rate alerts mounted
  from `deploy/monitoring/`).
* Traces: received by the OTel collector and logged (`debug` exporter) — swap the
  exporters block in `otel/otel-collector-config.yml` for a Tempo/Jaeger backend later.
* Grafana dashboards live in `dashboards/` (Grafana is not part of this stack).
