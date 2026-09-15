# Branded Domain & Rich Analytics (Phase C) — Implementation Sequence

**Companions:** `branded-domain-analytics-spec.md` · `branded-domain-analytics-backlog.md`
**Rule:** complete each step's acceptance and verification before starting the next. Do not invent
out-of-scope work.

---

## Global execution rules

1. Work in small, reviewable vertical commits.
2. Read the referenced story acceptance before coding.
3. **Never enrich analytics in the redirect path.** Only cheap fields (shortCode, timestamp, userAgent, ip,
   referrer) are captured on the redirect; device/geo are derived in the worker.
4. The redirect must stay fast: host resolution is cached/cheap; no blocking on analytics.
5. Owner guard applies to domain management and the analytics query.
6. Add tests with the production change, not at the end.
7. `core/` stays framework-free; custom-domain and analytics types are pure domain.
8. **Any new Maven coordinate (e.g. MaxMind GeoIP2) requires explicit approval.** The device parse and DNS
   check should not need one; GeoIP is optional and off by default.
9. After each step, update task status and docs; do not silently alter the spec.

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

## Step 0 — Baseline, design lock, dependency gate
### Stories: (context)

### Actions

1. Confirm HEAD (`c0f5b45`) and fast tests green.
2. Confirm the redirect captures only `shortCode/timestamp/userAgent/ip` (no `referrer`), no device/geo,
   no `click_daily`, and the redirect resolves by path only (`GET /{id}`, no `Host`).
3. Record locked decisions: custom-domain resolution **option B** (`short_url.domain`), analytics enrichment
   in the worker only, `click_daily` rollup, GeoIP off by default (approval required if on).
4. **Get approval** before adding a GeoIP Maven coordinate (MaxMind `geoip2`) — if not approved, keep
   `app.analytics.geo.enabled=false` and don't add the dependency.

### Done when

- baseline understood; decisions recorded; dependency approach approved (GeoIP optional/off).

### Verify

```bash
mvn test
```

---

## Step 1 — CustomDomain model + migration V8
### Stories: D1

### Actions

1. Add `CustomDomain` (host, userId, status, verificationToken, createdAt) in `core/model`; entity +
   mapper.
2. Add V8 migration: `custom_domains` collection, unique `host` index, `userId` index.
3. Add `MongoCollections.CUSTOM_DOMAINS`.

### Done when

- model + entity + mapper; V8 creates `custom_domains`; `core/` framework-free.

### Verify

```bash
mvn test
mvn test -Dtest='*Migration*IT' -DfailIfNoTests=false
```

---

## Step 2 — Claim/verify domain + DNS check job
### Stories: D2

### Actions

1. `POST /api/v1/domains` (authenticated), owner-guarded, host uniqueness; generates a verification token.
2. A scheduled DNS health-check verifies the record → `status = ACTIVE`; missing/expired → `FAILED`.
3. `GET /api/v1/domains` (own) + `DELETE`.

### Done when

- host claimed/unique/owner-only; DNS verify promotes to ACTIVE; failure → FAILED; re-trigger works.

### Verify

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 3 — Host-aware redirect resolution (option B)
### Stories: D3

### Actions

1. Add `short_url.domain` (nullable) + entity/mapper + V8 migration field.
2. In the redirect handler, read the `Host` header; if it matches a `CustomDomain`, resolve by `(host,
   code)`; else fall through to the default `GET /{id}`.

### Done when

- a link under a custom host resolves by `(host, code)`; default host still works; redirect stays fast.

### Verify

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 4 — Create/set a link under a custom domain
### Stories: D4

### Actions

1. The shorten request accepts an optional `domain` (validated against the user's verified `CustomDomain`).
2. `PATCH /api/v1/urls/{id}` can set/clear `domain`; owner-guarded.

### Done when

- a link can be shortened/set under an owned, verified domain; unverified/other's → 400/403; redirect then
  resolves under that host.

### Verify

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 5 — Extend ClickEvent/Document (referrer, device, country)
### Stories: A1

### Actions

1. Add `referrer` (captured from the `Referer` header on the redirect), `device`, `country` (nullable) to
   `ClickEvent`/`ClickEventDocument`.

### Done when

- the redirect captures `referrer` cheaply; document retains the new fields; worker enriches.

### Verify

```bash
mvn test
```

---

## Step 6 — Enrich in the worker
### Stories: A2

### Actions

1. In `ClickBatchWorker`, derive `device` from User-Agent (simple parse) and, if `app.analytics.geo.enabled`,
   `country` via GeoIP; best-effort (never fail the batch).

### Done when

- device/geo derived in the worker; enrichment failure does not break the batch; no enrichment in the
  redirect path.

### Verify

```bash
mvn test
mvn test -Dtest='*Analytics*IT' -DfailIfNoTests=false
```

---

## Step 7 — click_daily rollup (V9) + scheduled aggregation
### Stories: A3

### Actions

1. Add `click_daily` (shortCode, day, clicks, uniqueDays, breakdown) + entity/repository.
2. V9 migration: index `(shortCode, day)`; `click_events` already has a `timestamp` index.
3. A scheduled job aggregates raw `click_events` by `(shortCode, day)`, idempotent/bounded.

### Done when

- `click_daily` populated by the scheduled job; idempotent; V9 index exists.

### Verify

```bash
mvn test
mvn test -Dtest='*Analytics*IT' -DfailIfNoTests=false
```

---

## Step 8 — Analytics query endpoint (owner-guarded)
### Stories: A4

### Actions

1. `GET /api/v1/urls/{id}/clicks?unit=day|hour&from=&to=` (owner-only) returns the time series from
   `click_daily` (day) or a derived hourly series; optionally a device/geo/referrer breakdown.
2. `401` unauthenticated; `403` non-owner; `404` unknown. OpenAPI documented.

### Done when

- time series + breakdown returned for the range; owner-guarded; OpenAPI updated.

### Verify

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 9 — Unique-vs-raw via Redis HyperLogLog (optional)
### Stories: A5

### Actions

1. Maintain a Redis HLL key per `(shortCode, day)` (or per shortCode over a range); return the unique count
   alongside the raw count.

### Done when

- unique count returned alongside raw; additive; does not block the redirect.

### Verify

```bash
mvn test
mvn test -Dtest='*Analytics*IT' -DfailIfNoTests=false
```

---

## Step 10 — Tests + docs sync
### Stories: V1

### Actions

1. Add ITs: claim/verify domain; `(host, code)` resolution; shorten/PATCH under a custom domain; analytics
   time series + breakdown + owner guard; unique-vs-raw (if HLL).
2. Sync `data-model-decisions`, `coding-standards`, `testing-playbook`, `README.md`, `AGENTS.md` (new debt
   items), OpenAPI.

### Done when

- domain + analytics ITs green; owner guard (403); `mvn verify` + boundary check pass; docs/OpenAPI synced.

### Verify

```bash
mvn verify
bash scripts/check-boundaries.sh
```

---

## Final smoke / acceptance path

1. Claim a domain and verify DNS → `status = ACTIVE`.
2. Shorten a link under that domain → `marca.co/promo` resolves via `(host, code)`; default host still works.
3. Open a short link several times with different user agents / referrers → the `click_daily` rollup is
   populated; `GET /api/v1/urls/{id}/clicks?unit=day` returns a time series + device/referrer breakdown.
4. Non-owner tries to read analytics → 403; unauthenticated → 401.
5. Unique-vs-raw count (if HLL) returned next to the raw count.
6. Redirect stays fast (host resolution cheap, no analytics blocking); enrichment happens in the worker.
7. `mvn verify` + `check-boundaries.sh` pass; no claim that blocks the redirect.

---

_Pre-implementation sequence. Preserve deviations and final evidence as an as-built record after delivery._
