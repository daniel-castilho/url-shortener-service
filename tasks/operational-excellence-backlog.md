# Operation & Observability — Backlog
## Tracing, structured logs, SLOs, load baselines, TLS, deploy & backups

**Priority:** P1 — operational maturity / production readiness. Fifth epic.
**All stories:** Must.
**Companions:** `operational-excellence-spec.md` · `operational-excellence-implementation-sequence.md`

**Execution status:** ready from `main` (`0579f5d` / `v0.6.0`). Observability *foundation* (Micrometer
counters + timers, Prometheus endpoint) already exists; this epic adds the missing operational layer.

---

## Epic outcome

The service is observable, measurable and recoverable: distributed tracing, structured (JSON) logging,
defined and monitored SLOs, a load/performance baseline as a regression tripwire, documented TLS
termination + deploy/rollback, and scheduled MongoDB backup/restore + `click_events` retention.

---

## Story map

```text
OBSERVABILITY
O1  Distributed tracing (OpenTelemetry)
O2  Structured (JSON) logging profile
O3  Confirm latency metrics are actually recorded (no dead metrics)
O4  SLOs + SLO-backed metrics + a 5xx/redirect breakdown counter

PERFORMANCE & RELIABILITY
P1  Load/performance baseline (k6) with thresholds-as-code
P2  Graceful-shutdown verification + docs

DEPLOYMENT & TLS
D1  TLS termination (reverse proxy) + trusted-proxy config for rate limiter
D2  Deploy formalization (systemd/compose) + rollback + fail-fast startup

DATA & RETENTION
R1  MongoDB backup/restore schedule
R2  `click_events` retention purge

DELIVERY
V1  Docs sync (twelve-factor, release-runbook, coding-standards, testing-playbook, README, AGENTS)
```

---

## O1 — Distributed tracing (OpenTelemetry)

**Goal:** follow a request across controller → service → repository (Mongo) / cache (Redis).

### Work

- add Micrometer Tracing (OTel) and an OTLP exporter (or a minimal in-memory/basic span exporter for dev);
- enable auto-instrumentation of Spring Web + Data (Mongo/Redis);
- keep `core/` import-free (tracing in `infra` only).

### Acceptance

- [ ] A redirect request produces a trace spanning controller → service → Mongo/Redis.
- [ ] `core/` has no OTel/Micrometer-tracing import.
- [ ] Exporter is configurable (OTLP target / dev in-memory).

---

## O2 — Structured (JSON) logging profile

**Goal:** parseable logs for a shipper, without breaking local dev.

### Work

- add a `json` logback profile (logstash-logback-encoder) emitting JSON to stdout;
- keep the default console/file plain for dev;
- never log secrets / PII.

### Acceptance

- [ ] `-Dspring.profiles.active=json` emits structured JSON logs.
- [ ] Local default profile still works (plain).
- [ ] No secret/PII in any log field.

---

## O3 — Confirm latency metrics are actually recorded

**Goal:** the declared p50/p95/p99 timers are real (not "dead metrics").

### Work

- audit `MetricsService` and the call sites; ensure `redirect.latency` and `shorten.latency` are recorded
  at runtime (they were previously defined but possibly not wired);
- add any missing invocation.

### Acceptance

- [ ] `redirect.latency` and `shorten.latency` are emitted at runtime (verified via Prometheus).
- [ ] No metric is registered but never recorded.

---

## O4 — SLOs + SLO-backed metrics

**Goal:** measurable SLOs and the metrics to evaluate them.

### Work

- define SLOs (e.g. redirect p95 < 40 ms, availability 99.9%, shorten p95 < 150 ms, cache hit > 90%);
- add a low-cardinality redirect error/5xx counter and a status-breakdown (2xx/4xx/5xx) counter;
- document the SLOs and how they're measured in the release-runbook.

### Acceptance

- [ ] SLO table documented; backing metrics exist.
- [ ] No high-cardinality tag (user/IP/raw code).
- [ ] A redirect 5xx/expired (if TTL) / not-found breakdown is observable.

---

## P1 — Load/performance baseline (k6)

**Goal:** a regression tripwire for the redirect path.

### Work

- add a k6 scenario: `GET /{code}` with N VUs / R iterations, thresholds-as-code (p95 latency, error rate);
- add `scripts/performance-baseline.sh`;
- CI: add as a `continue-on-error` job first, promote to gate after calibrating floors.

### Acceptance

- [ ] A run targets the redirect path with thresholds; failures are detected.
- [ ] Baseline is reproducible against the local compose stack with a seeded dataset.
- [ ] Promotable to a gate after 2–3 calibrated runs.

---

## P2 — Graceful-shutdown verification

**Goal:** SIGTERM drains in-flight requests within the grace period.

### Work

- document/verify graceful shutdown (drain, then stop);
- add a verification script/step.

### Acceptance

- [ ] SIGTERM drains in-flight requests (200) and rejects new ones within the grace period.
- [ ] Documented in the runbook.

---

## D1 — TLS termination (reverse proxy)

**Goal:** HTTPS in front of the app via a proxy; rate limiter trusts the proxy.

### Work

- document/configure a reverse proxy (NGINX/Caddy) terminating TLS and forwarding to the app's internal port;
- configure the trusted-proxy CIDR for the ClientAddressResolver/rate limiter.

### Acceptance

- [ ] Public endpoint is served over HTTPS; app receives forwarded traffic internally.
- [ ] Rate limiter trusts only the proxy (no `X-Forwarded-For` spoof from the internet).

---

## D2 — Deploy formalization + rollback + fail-fast startup

**Goal:** standard, rollbackable deployment; fail-fast on bad config.

### Work

- formalize a systemd unit / compose service with `Restart=on-failure` and the env contract;
- document a rollback path (stateless redeploy w/ previous artifact);
- add a `ProdConfigValidator`-style fail-fast startup check for required env vars.

### Acceptance

- [ ] Documented deploy + rollback that works on bare metal.
- [ ] Startup fails fast on missing/weak env contract.
- [ ] Graceful shutdown verified.

---

## R1 — MongoDB backup/restore

**Goal:** the source-of-truth data is recoverable.

### Work

- schedule `mongodump` of the `url_shortener` DB to an off-host durable location;
- document `mongorestore` + a verify step.

### Acceptance

- [ ] A scheduled backup exists and is documented.
- [ ] Restore drill produces a consistent DB (verified).

---

## R2 — `click_events` retention purge

**Goal:** storage doesn't grow unbounded.

### Work

- prefix a scheduled purge: delete events older than N days in small bounded batches by `timestamp`;
- keep it idempotent and background (never per-request).

### Acceptance

- [ ] Purge runs on a schedule, bounded and idempotent.
- [ ] It does not block the redirect or write path.

---

## V1 — Documentation sync

**Goal:** docs reflect the applied operational layer.

### Work

- `twelve-factor.md` (5/11/12 applied: build/release, logs, admin processes);
- `release-runbook.md` (TLS, deploy/rollback, backup/restore, retention, SLOs, incident-response);
- `coding-standards.md` (structured logging, tracing, no-PII, metric-tag rule);
- `testing-playbook.md` (load harness, observability ITs);
- `README.md` (tracing, JSON logs, SLOs, TLS proxy, backups);
- `AGENTS.md` (close/clear observability debt item 12; note OTel/encoder approval).

### Acceptance

- [ ] All listed docs updated; no stale "no tracing / plain logs only" claim.

---

## Epic Definition of Done

- [ ] O1–O4 complete: tracing, structured logs, real latency metrics, SLOs + breakdown metrics.
- [ ] P1–P2 complete: load baseline + graceful-shutdown verification.
- [ ] D1–D2 complete: TLS via proxy, deploy/rollback, fail-fast startup.
- [ ] R1–R2 complete: MongoDB backup/restore + `click_events` retention.
- [ ] V1 complete: docs and `AGENTS.md` synced (item 12 closed).
- [ ] `mvn test`, `mvn verify` and `check-boundaries.sh` pass.
- [ ] No secret/PII in logs/metrics; no high-cardinality metric tag.
- [ ] The redirect hot path is not degraded by observability (no blocking call).
