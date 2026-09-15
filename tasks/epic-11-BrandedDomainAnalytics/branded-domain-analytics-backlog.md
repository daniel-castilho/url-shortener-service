# Branded Domain & Rich Analytics (Phase C) — Backlog

**Priority:** P1 — the product differentiator. Third epic of the "Bitly-like" turn.
**All stories:** Must.
**Companions:** `branded-domain-analytics-spec.md` · `branded-domain-analytics-implementation-sequence.md`

**Execution status:** ready from `main` (`c0f5b45`, `v0.12.0`).

---

## Epic outcome

Links can be shortened and resolved under a **verified custom domain** (`marca.co/promo`) via host-aware
redirect resolution, and the link detail returns **rich analytics** — temporal (day/hour), geo (country),
device, referrer, and unique-vs-raw clicks — all owner-guarded and without slowing the redirect.

---

## Story map

```text
CUSTOM DOMAIN
D1  CustomDomain model + collection + migration V8
D2  Claim/verify domain (POST /api/v1/domains) + DNS check job
D3  Host-aware redirect resolution (option B: short_url.domain field)
D4  PUT/PATCH: set the domain on a link (or create under a custom domain); owner-guarded

RICH ANALYTICS
A1  Extend ClickEvent/ClickEventDocument: referrer, device, country
A2  Enrich in worker (device via UA parse; geo via GeoIP, optional) — never in redirect
A3  click_daily rollup (migration V9) + scheduled aggregation
A4  GET /api/v1/urls/{id}/clicks (time series + device/geo/referrer breakdown), owner-guarded
A5  Unique-vs-raw via Redis HyperLogLog (optional, additive)

VERIFY
V1  Domain resolution + analytics query ITs; docs sync
```

---

## D1 — CustomDomain model + collection (V8)

**Goal:** represent a custom host.

### Work

- add `CustomDomain` (host, userId, status, verificationToken, createdAt) in `core/model`; entity + mapper;
- add `custom_domains` collection + V8 migration (host unique, userId index).

### Acceptance

- [ ] Model + entity + mapper; V8 creates `custom_domains` with a unique `host` index.
- [ ] `core/` stays framework-free.

---

## D2 — Claim/verify domain + DNS check

**Goal:** user claims a domain and proves ownership via DNS.

### Work

- `POST /api/v1/domains` (authenticated) to claim a host (owner-guarded; host uniqueness);
- generate a verification token; a scheduled DNS health-check verifies the record → `status = ACTIVE`.
- `GET /api/v1/domains` (list own) + `DELETE` (remove).

### Acceptance

- [ ] Host is claimed and unique; owner-only; DNS verification promotes to `ACTIVE`;
- [ ] missing/expired record → `FAILED`; re-trigger works.

---

## D3 — Host-aware redirect resolution

**Goal:** `marca.co/promo` resolves correctly.

### Work

- add `short_url.domain` (nullable) — a link created for a custom domain stores it;
- the redirect handler reads the `Host` header; if it matches a `CustomDomain`, resolve by `(host, code)`
  (or fall through to the default `GET /{id}` for the default host).

### Acceptance

- [ ] A link under a custom host resolves by `(host, code)`;
- [ ] Default host still works via `GET /{id}`; no conflict;
- [ ] Redirect stays fast (host lookup is cached/cheap) and does not block on analytics.

---

## D4 — Create/set a link under a custom domain

**Goal:** shorten into a custom domain, or set it on an existing link.

### Work

- the shorten request accepts an optional `domain` (host); validated against the user's verified `CustomDomain`;
- `PATCH /api/v1/urls/{id}` can set/clear the `domain`; owner-guarded.

### Acceptance

- [ ] A link can be shortened/set under an owned, verified domain; unverified/other's → 400/403;
- [ ] the redirect then resolves it under that host.

---

## A1 — Extend ClickEvent/ClickEventDocument

**Goal:** capture referrer + derive device + optional country.

### Work

- add `referrer` (from the `Referer` header at capture), `device` (derived), `country` (nullable, optional)
  to `ClickEvent`/`ClickEventDocument`.

### Acceptance

- [ ] The redirect captures `referrer` cheaply (no blocking);
- [ ] The document retains the new fields; worker enriches.

---

## A2 — Enrich in the worker (never in redirect)

**Goal:** device/geo are derived asynchronously.

### Work

- in `ClickBatchWorker`, derive `device` from User-Agent (simple parse) and, if `app.analytics.geo.enabled`,
  `country` via GeoIP; best-effort (must not fail the batch).

### Acceptance

- [ ] Device/geo derived in the worker; enrichment failure does not break the batch;
- [ ] no enrichment in the redirect path.

---

## A3 — click_daily rollup (V9) + scheduled aggregation

**Goal:** cheap temporal queries.

### Work

- add `click_daily` (shortCode, day, clicks, uniqueDays, breakdown); entity + repository;
- V9 migration: index `(shortCode, day)` on `click_daily`;
- a scheduled job aggregates raw `click_events` by `(shortCode, day)` (idempotent, bounded).

### Acceptance

- [ ] `click_daily` is populated by the scheduled job; idempotent;
- [ ] V9 index exists; no raw-event re-aggregation on every read.

---

## A4 — GET /api/v1/urls/{id}/clicks (time series + breakdown), owner-guarded

**Goal:** analytics someone reads in the morning.

### Work

- `GET /api/v1/urls/{id}/clicks?unit=day|hour&from=&to=` (owner-only) returns the time series from
  `click_daily` (day) or a derived hourly series;
- optionally return a device/geo/referrer breakdown;
- `401` unauthenticated; `403` non-owner; `404` unknown.

### Acceptance

- [ ] Time series + breakdown returned for the range; owner-guarded;
- [ ] OpenAPI documents the route.

---

## A5 — Unique-vs-raw via Redis HyperLogLog (optional, additive)

**Goal:** approximate unique visitors.

### Work

- maintain a Redis HLL key per `(shortCode, day)` (or shortCode over a range); add the unique count next
  to the raw count in the query.

### Acceptance

- [ ] Unique count returned alongside raw; additive, does not block the redirect.

---

## V1 — Tests + docs sync

**Goal:** prove domain resolution + analytics queries; sync docs.

### Work

- IT: claim/verify domain; `(host, code)` resolution; shorten/set under a custom domain; analytics time
  series + breakdown + owner guard; unique-vs-raw (if HLL).
- Sync `data-model-decisions`, `coding-standards`, `testing-playbook`, `README.md`, `AGENTS.md`, OpenAPI.

### Acceptance

- [ ] Domain + analytics ITs green; owner guard (403 for non-owner); `mvn verify` + boundary check pass;
- [ ] docs/OpenAPI reflect custom domain + rich analytics.

---

## Epic Definition of Done

- [ ] D1–D4 complete: custom domain model + V8, claim/verify + DNS check, host-aware redirect resolution,
      shorten/PATCH under a custom domain (all owner-guarded).
- [ ] A1–A5 complete: extended event + worker enrichment, `click_daily` rollup (V9), analytics query
      endpoint (owner-guarded), optional HLL unique count.
- [ ] V1 complete: domain + analytics ITs green; docs/AGENTS/OpenAPI synced.
- [ ] Redirect stays fast, host-resolution cached/cheap, and never blocks on analytics.
- [ ] `mvn test`, `mvn verify`, `check-boundaries.sh` pass; `core/` framework-free.
