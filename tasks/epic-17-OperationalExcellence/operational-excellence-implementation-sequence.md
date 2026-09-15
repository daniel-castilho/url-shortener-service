# Operation & Observability — Implementation Sequence
## Tracing, structured logs, SLOs, load baselines, TLS, deploy & backups

**Companions:** `operational-excellence-spec.md` · `operational-excellence-backlog.md`
**Rule:** complete each step's acceptance and verification before starting the next. Do not invent
out-of-scope work.

---

## Global execution rules

1. Work in small, reviewable vertical commits.
2. Read the referenced story acceptance before coding.
3. Add tests with the production change, not at the end.
4. **Never degrade the redirect hot path** — tracing/logging must be async/best-effort; no blocking
   external call in the redirect.
5. **No new Maven coordinate without explicit approval** (OpenTelemetry + logstash-logback-encoder are
   the only candidates — confirm both before adding).
6. English only in code, comments, logs, tests and docs.
7. After each step, update task status and docs; do not silently alter the spec.

### Fast verification (throughout)

```bash
mvn test
```

### Integration verification

```bash
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

### Full gate

```bash
mvn verify
```

---

## Step 0 — Baseline, design lock, dependency approval
### Stories: (context)

### Actions

1. Confirm HEAD (`0579f5d`) and fast tests green.
2. Confirm what observability already exists (Micrometer counters/timers + Prometheus + logback plain)
   and what is missing (tracing, JSON logs, SLOs, load baseline, TLS, backup, retention).
3. Record locked decisions in `docs/data-model-decisions.md` / `docs/twelve-factor.md`:
   - tracing via OpenTelemetry (or minimal exporter), JSON log profile, proxy-based TLS, scheduled
     backup, retention purge.
4. **Get explicit approval** for the Maven coordinates (OpenTelemetry + logstash-logback-encoder)
   before Step 1 and Step 2.

### Done when

- baseline understood; decisions recorded; dependencies approved;
- no unresolved "or/if available" choice remains.

### Verify

```bash
mvn test
```

---

## Step 1 — Distributed tracing (OpenTelemetry)
### Stories: O1

### Actions

1. Add Micrometer Tracing + OTel bridge + an exporter (OTLP for prod; in-memory/basic for dev).
2. Enable auto-instrumentation of Spring Web + Data (Mongo/Redis).
3. Keep `core/` import-free.
4. Add a test that an endpoint produces a span (or a tracing smoke IT).

### Done when

- a redirect creates a trace spanning controller → service → Mongo/Redis;
- `core/` has no tracing import;
- exporter is configurable.

### Verify

```bash
mvn test
mvn test -Dtest='*Tracing*IT' -DfailIfNoTests=false
```

> **Dependency note:** this requires the approved OTel coordinates. If not approved, stop and record a
> minimal alternative (Micrometer tracing with no OTLP exporter) or defer.

---

## Step 2 — Structured (JSON) logging
### Stories: O2

### Actions

1. Add a `json` logback profile (logstash-logback-encoder) emitting JSON to stdout.
2. Keep the default plain console/file for local dev.
3. Ensure no secret/PII is logged in the JSON fields.

### Done when

- `-Dspring.profiles.active=json` emits structured JSON;
- local default still plain;
- no secret in any log field.

### Verify

```bash
mvn test
```

> **Dependency note:** requires the approved logstash-logback-encoder coordinate.

---

## Step 3 — Confirm real latency metrics + add SLO/breakdown metrics
### Stories: O3, O4

### Actions

1. Audit `MetricsService` and call sites; ensure `redirect.latency` / `shorten.latency` are recorded at
   runtime (fix any dead metric).
2. Add a low-cardinality redirect error/5xx counter + a status breakdown (2xx/4xx/5xx) counter.
3. Define and document SLOs (redirect p95, availability, shorten p95, cache hit ratio).

### Done when

- latency metrics are emitted at runtime (verified via Prometheus);
- SLO table documented with backing metrics;
- no high-cardinality tag.

### Verify

```bash
mvn verify
curl -s http://localhost:8080/actuator/prometheus | grep -E "redirect_latency|redirect.*errors"
```

---

## Step 4 — Load/performance baseline (k6)
### Stories: P1

### Actions

1. Add a k6 scenario for `GET /{code}` with thresholds-as-code (p95, error rate).
2. Add `scripts/performance-baseline.sh`.
3. Add a `continue-on-error` CI job; document floors.

### Done when

- baseline run targets the redirect path and detects threshold breaches;
- reproducible against the compose stack with a seeded dataset.

### Verify

```bash
scripts/performance-baseline.sh    # needs Docker + app up
```

---

## Step 5 — TLS termination (proxy) + trusted-proxy config
### Stories: D1

### Actions

1. Document/configure a reverse proxy (NGINX/Caddy) terminating TLS and forwarding to the app's
   internal port.
2. Configure the trusted-proxy CIDR for `ClientAddressResolver`/rate limiter.

### Done when

- public endpoint is HTTPS; app receives forwarded traffic internally;
- rate limiter trusts only the proxy.

### Verify

```bash
mvn test -Dtest='*RateLimit*IT' -DfailIfNoTests=false   # confirm no X-Forwarded-For spoof
```

---

## Step 6 — Deploy formalization + rollback + fail-fast
### Stories: D2, P2

### Actions

1. Formalize a systemd unit / compose service with `Restart=on-failure` and env contract.
2. Document rollback (stateless redeploy w/ previous artifact) + graceful-shutdown verification.
3. Add a `ProdConfigValidator`-style fail-fast startup check for required env vars.

### Done when

- documented deploy + rollback works on bare metal;
- startup fails fast on bad env contract;
- graceful shutdown drains in-flight requests.

### Verify

```bash
mvn test
# boot prod-like with a missing env -> expect abort; with grace period -> SIGTERM drains
```

---

## Step 7 — Backup/restore + click_events retention
### Stories: R1, R2

### Actions

1. Add a scheduled `mongodump` of `url_shortener` to a durable off-host location; document `mongorestore`
   + verify.
2. Add a scheduled, bounded, idempotent retention purge of `click_events` (delete older than N days in
   batches by `timestamp`).

### Done when

- a backup schedule + restore drill are documented and verified;
- retention purge runs, bounded, without blocking the write path.

### Verify

```bash
# manual: mongodump/mongorestore smoke; run the purge once against a dev DB
```

---

## Step 8 — Documentation sync
### Stories: V1

### Actions

1. `twelve-factor.md` — factors 5/11/12 applied (build/release, logs, admin processes).
2. `release-runbook.md` — TLS, deploy/rollback, backup/restore, retention, SLOs, incident-response.
3. `coding-standards.md` — structured logging, tracing (no blocking), no-PII, metric-tag rules.
4. `testing-playbook.md` — load harness + observability ITs.
5. `README.md` — tracing, JSON logs, SLOs, TLS proxy, backups.
6. `AGENTS.md` — close debt item 12; note OTel/encoder approval.

### Done when

- all listed docs updated; no stale "no tracing / plain logs only" claim.

### Verify

```bash
mvn verify
```

---

## Final smoke / acceptance path

1. Activate the `json` profile → logs are structured JSON (no secrets).
2. Make a shorten + redirect → a trace spans controller → service → Mongo/Redis (via the exporter).
3. Prometheus shows `redirect.latency` (p50/p95/p99) and a 5xx/status breakdown recorded at runtime.
4. Run the load baseline → thresholds pass; a deliberate degradation (e.g. the cache off) breaches a
   threshold.
5. HTTPS term via proxy → public endpoint is `https`; rate limiter trusts only the proxy.
6. Boot without a required env var → startup aborts (fail-fast); normal boot serves.
7. `mongodump` + `mongorestore` → verified consistent; retention purge runs bounded.
8. `mvn verify` + `check-boundaries.sh` pass; no secret/high-cardinality tag in logs/metrics.

---

_Pre-implementation sequence. Preserve deviations and final evidence as an as-built record after delivery._
