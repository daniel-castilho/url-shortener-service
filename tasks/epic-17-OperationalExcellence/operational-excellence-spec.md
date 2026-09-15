# Operation & Observability — Technical Specification
## Tracing, structured logs, SLOs, load baselines, TLS, deploy & backups

**Status:** ready for implementation from current `main` (`0579f5d` / `v0.6.0`).
**Priority:** P1 — operational maturity / production readiness. Fifth epic.
**Companions:** `operational-excellence-backlog.md` · `operational-excellence-implementation-sequence.md`

---

## 1. Purpose

The service is functionally complete and has a real quality gate, but it is not yet
**operationally mature**. The redirect path is the critical hot path and the product is a public-facing
service on on-premises bare metal, yet there is:

- **no distributed tracing** (can't follow a request across cache → Mongo → redirect / analytics);
- **no structured (JSON) logs** — only plain text console/file (`logback-spring.xml`);
- **no SLOs or recorded latency baselines** (p50/p95/p99 are declared but not measured or watched);
- **no load/performance baseline** (k6 or similar) as a regression tripwire;
- **no explicit TLS/HTTPS termination** configuration and **no formalized deploy + backup/restore**
  for the critical MongoDB data and the `click_events` retention.

This epic makes the service **observable, measurable, and recoverable** — the "operational excellence"
layer that the twelve-factor and release-runbook docs describe as the target.

---

## 2. Scope

### In scope

- **Distributed tracing** with OpenTelemetry (OTel) wired into the existing Micrometer/Actuator
  observability.
- **Structured (JSON) logging** via a `json` logging profile (logstash-logback-encoder is already an
  acceptable, approved-style approach — confirm before adding a new coordinate).
- **SLOs + SLO-backed metrics**: define latency (p50/p95/p99) and availability (e.g. 99.9%) SLOs and
  expose the metrics needed to evaluate them.
- **Load/performance baseline** (k6 or Gatling) against the redirect path with thresholds-as-code as a
  regression tripwire.
- **TLS/HTTPS termination** — recommended via a reverse proxy (NGINX/Caddy) in front of the app
  (best for on-prem); document the recommended option and the app config that pairs with it.
- **Deploy formalization**: a documented `systemd` unit (or updated compose), a documented
  rollback path, and env-contract fail-fast at startup.
- **Backup/restore** for MongoDB (`mongodump`/`mongorestore`) on a schedule, plus **retention/purge**
  of `click_events` so storage doesn't grow unbounded.
- Docs sync (`twelve-factor.md`, `release-runbook.md`, `coding-standards.md`, `testing-playbook.md`,
  `README.md`, `AGENTS.md` debt items).

### Out of scope

- the redirect rate-limit, SSRF, actuator/Swagger hardening (done in v0.4/v0.5);
- link expiry / versioned migration (the documented epic 4 — separate);
- dashboard/Grafana data source provisioning (note it, but full dashboards are optional);
- changing the public API contract;
- any new Maven coordinate **without explicit approval** (OTel + encoder are the two that need it).

---

## 3. Architectural constraints

- `core/` stays framework-free. Metrics hooks already flow through the `MetricsPort`. Tracing and
  structured logging live in `infra/`.
- Adding OTel and a JSON encoder are **infra** concerns and are the only likely new dependencies.
  Per convention "no new Maven coordinate without approval", get explicit approval for BOTH, but keep
  the implementation behind the existing `infra` observability seam so the `core/` boundary is intact.
- The redirect hot path must NOT be made slower or blocking by observability. Tracing spans and
  logging are async/best-effort; never a synchronous external call in the redirect.

---

## 4. Tracing (OpenTelemetry)

### 4.1 Recommended approach

- Add Spring Boot Actuator + Micrometer Tracing with **OpenTelemetry** (`micrometer-tracing-bridge-otel`
  + `opentelemetry-exporter-otlp`) — the standard Spring Boot 3.x path.
- Export traces to an OTLP collector (or stdout for dev). For on-prem, an OTel Collector + a backend
  (Jaeger/Tempo/Grafana) is the target; local dev can use an in-memory/console span exporter.
- **Auto-instrument** the Spring Web + Data (Mongo) + Redis paths so spans follow
  `request → controller → service → repository (Mongo) / cache (Redis)` with sensible names.
- Keep `core/` import-free: spans are created by the framework or in `infra` adapters; the domain
  service does not import OTel.

### 4.2 Cost/benefit for on-prem

OTel + a collector is a meaningful operational addition. If the team prefers to keep the footprint
minimal, an acceptable **minimum viable** alternative is Micrometer tracing with **no OTLP exporter**
(defaulting to an in-memory/basic span exporter) plus the existing metrics — but the spec recommends
the full OTel path for a product-grade service. Record the decision; do not silently pick a middle
ground.

---

## 5. Structured logging

- Add a **`json` logging profile** (`logback-spring.xml` with a `springProfile` named `json`) that emits
  structured JSON lines (e.g. via `logstash-logback-encoder`) to stdout, so a log shipper can parse
  them.
- Keep the default console/file (plain) for local dev.
- **Never log secrets**: passwords, JWT, bearer tokens, full destinations with credentials. The
  existing `GlobalExceptionHandler` already logs request context (method + URI + exception) — preserve
  that and add no PII.
- Preserve existing log levels (`error`/`warn`/`info`/`debug`) and the "significant lifecycle" /
  "handled anomaly" semantics from `coding-standards.md`.

---

## 6. SLOs & metrics

### 6.1 Define SLOs

For the redirect path (the critical path), propose (adjust with the team):

| SLO | Target | Backing metric |
| --- | --- | --- |
| Redirect latency p95 | < 40 ms (or a tested baseline) | `redirect.latency` (p95) |
| Redirect availability | 99.9% | healthy redirect rate / error budget |
| Shorten latency p95 | < 150 ms | `shorten.latency` (p95) |
| Cache hit ratio | > 90% | `cache.hits` / (hits + misses) |

- Metrics already exist (`redirect.latency`, `shorten.latency`) — **wire the percentile recordings**
  (they are present in `MetricsService`; confirm they are actually recorded at runtime, per the earlier
  "dead metrics" lesson).
- Add low-cardinality, SLO-relevant metrics that are missing (e.g. an explicit error/5xx counter on the
  redirect path, `urls.expired.total` if TTL lands, and a 410/404 breakdown).

### 6.2 Guardrails

- Never use high-cardinality tags (user ID, IP, raw code) as dimensions — reuse the existing fixed-tag
  convention.
- Document the SLOs and how they are measured in the release-runbook.

---

## 7. Load / performance baseline

- Add a **k6** (or Gatling) scenario targeting the redirect path: `GET /{code}` for N concurrent users
  and R requests, with **thresholds-as-code** for p95 latency and error rate.
- Record the baseline (`scripts/performance-baseline.sh` or a `perf/` folder) so a regression is caught
  by comparing a run before/after a change.
- Run it against the local stack (`docker-compose up -d` + the app) behind a seeded dataset.
- CI integration: add it as a `continue-on-error` job first (like the reference project), promote to a
  gate once 2–3 runs establish realistic floors.

---

## 8. TLS & deployment

### 8.1 TLS termination (recommend reverse proxy)

For on-premises bare metal, the recommended pattern is a **reverse proxy (NGINX/Caddy/Traefik)** in
front of the app handling TLS and forwarding `http://` to the app on the internal port:

- App binds a **non-privileged internal port** (e.g. `8080`) with no TLS; proxy terminates TLS and
  forwards to it.
- The proxy sets `X-Forwarded-For`/`Forwarded` and the app's `ClientAddressResolver`/rate-limiter
  must trust the proxy via a **trusted-proxy CIDR** (already a pattern from the v0.4 rate-limit work).
- Document the proxy config and the `app`/`docker-compose` networking.

**Alternative:** app-level TLS (`server.ssl.*`) with a cert. This is fine but couples cert management to
the app and is harder to operate in a compose fleet; only choose it if a proxy is not available.

### 8.2 Deploy formalization

- Provide a documented **systemd unit** (or a `docker-compose` service) that runs the jar with the env
  contract, `Restart=on-failure`, and a defined working dir.
- Document a **rollback** path (previous artifact + stateless redeploy) and a **graceful-shutdown**
  verification (SIGTERM drains in-flight requests within the grace period).
- Add **fail-fast at startup**: a `ProdConfigValidator`-style check aborts on missing/weak required env
  vars (this is a recommended addition; the JWT provider already validates its secret).

---

## 9. Backup / restore & data retention

### 9.1 MongoDB backup

- Schedule `mongodump` of the `url_shortener` DB to a **durable, off-host** location; document restore
  via `mongorestore` and a verify step.
- The short URL mappings are the **single source of truth**; without a backup, a DB loss = data loss.

### 9.2 Retention / purge of `click_events`

- `click_events` grows unbounded; add a **retention purge** (e.g. delete events older than N days on a
  schedule) driven by the existing `(timestamp)` index. This is an operational task, not a per-request
  write.
- Keep the purge bounded and idempotent (query in small batches by `timestamp`, delete in chunks).

---

## 10. Verification commands

```bash
mvn test
mvn verify                                    # full gate
bash scripts/check-boundaries.sh              # boundary (unchanged)
scripts/performance-baseline.sh               # load baseline (needs Docker + app up)
# after a prod-like boot:
curl -s http://localhost:8080/actuator/prometheus | grep -E "redirect_latency|http_server_requests"
```

---

## 11. Documentation deliverables

- `docs/twelve-factor.md` — mark factors 5/11/12 improvements (build/release, logs, admin processes)
  as applied: JSON logging, tracing, backup, deploy.
- `docs/release-runbook.md` — add TLS termination, deploy/rollback, backup/restore, retention, and the
  SLO table; update the incident-response section for observability.
- `docs/coding-standards.md` — structured-logging rule, tracing rule, no-PII logging, metric-tag rule.
- `docs/testing-playbook.md` — add the load-baseline harness and any observability ITs.
- `README.md` — note tracing, JSON logging, SLOs, TLS proxy, backups.
- `AGENTS.md` — clear/close the observability debt item 12 (and note the new OTel/encoder approval).

The epic is **not** Done while tracing is absent, logs are only plain-text, there is no load baseline,
no documented TLS/deploy/backup path, or no retention for `click_events`.
