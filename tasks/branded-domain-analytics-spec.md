# Branded Domain & Rich Analytics (Phase C) — Technical Specification

**Status:** ready for implementation from current `main` (`c0f5b45`, `v0.12.0`).
**Priority:** P1 — the product differentiator. Third epic of the "Bitly-like" turn.
**Companions:** `branded-domain-analytics-backlog.md` · `branded-domain-analytics-implementation-sequence.md`

---

## 1. Purpose

Phase B made links a resource (list/get/PATCH/archive). Phase C is where the service stops competing on
`bit.ly/abc` and starts competing on **`marca.co/promo`** — plus analytics someone opens in the morning.
Two capabilities:

1. **Custom/branded domain** — shorten and resolve under a domain you own (`links.marca.co/…`), with DNS
   verification and host-aware redirect resolution.
2. **Rich analytics** — temporal series (hour/day), geo (country), device, referrer, and **unique vs raw**
   clicks, queried via the link detail.

This is the biggest product jump and the foundation for Phase D (API keys/webhooks) and Phase E (read
scale).

---

## 2. Scope

### In scope

**Custom domain**
- `CustomDomain` model: `host` (e.g. `links.marca.co`), owner `userId`, DNS status, `createdAt`.
- `POST /api/v1/domains` (create/claim a domain) + DNS **verification** (CNAME/TXT) + a health check job.
- Redirect resolves **`Host` + path**, not just path: `links.marca.co/promo` → lookup by `(host, code)`.
- A default/generated short URL stays on the default host; custom-domain links resolve under the custom host.
- Owner-only domain management.

**Rich analytics**
- **Enrich** the click event: capture `referrer`, derive `device` (mobile/desktop/tablet) from the
  User-Agent, and (optionally) `country` from the IP via GeoIP.
- **Rollup** to a `click_daily` collection (per shortCode per day) for cheap temporal queries; keep raw
  `click_events`.
- `GET /api/v1/urls/{id}/clicks?unit=day|hour&from=&to=` → time series; plus device/geo/referrer breakdown.
- **Unique vs raw** clicks via a HyperLogLog (Redis) approximation for unique visitors.

### Out of scope

- QRs / link-in-bio / dashboards UI;
- API keys, webhooks, bulk create (Phase D);
- read/write service split, multi-region (Phase E);
- orgs/RBAC/SSO/billing (Phase F);
- per-link custom slug changes (that's a custom-alias feature, already partly present).

---

## 3. Architectural constraints

- `core/` stays framework-free: `CustomDomain`, `ClickEvent` extension, and the domain query/rollup ports
  are pure domain types.
- The **redirect path stays fast and independent**. It may add a **host-aware** lookup (custom domain →
  code) but must NOT block on analytics (still fire-and-forget, never synchronous).
- The `referrer` and device/geo enrichment happen in the **worker** (write path), **not** in the redirect.
  The redirect only captures the minimal fields (shortCode, timestamp, userAgent, ip, referrer) cheaply.
- `HyperLogLog` (Redis) is an **optional** enhancement for unique counts; the authoritative count is the
  rollup. Do not let the HLL slow/persist into the redirect path.

---

## 4. Custom domain

### 4.1 Model

```text
CustomDomain
  host: String            # e.g. "links.marca.co" (lowercase, normalized)
  userId: String          # owner
  status: String          # PENDING / VERIFIED / ACTIVE / FAILED
  verificationToken: String  # DNS TXT/CNAME target
  createdAt: Instant
```

Persist in a `custom_domains` collection (migration V8). Index on `host` (unique) and `userId`.

### 4.2 DNS verification

- On claim, generate a **verification token** (a random value) to set as a TXT record (or instruct a CNAME
  to the default host).
- A scheduled **DNS health check** verifies the record; on success, set `status = ACTIVE`.
- If the record is missing/expired, `status = FAILED`; the user can re-trigger.

### 4.3 Host-aware redirect resolution

Today `GET /{id}` resolves by code only. For custom domains, add resolution by **`(host, code)`**:

- The redirect handler reads the request `Host` header.
- If the host matches a `CustomDomain` of a user, resolve the code against **that user's** short URLs
  (or a `(host, code)` mapping).
- Implementation options (pick one and document):
  - **A (simple):** a `CustomDomain` stores the owner → the domain short links are looked up by
    `(userId, code)`; a code shortened under a custom host is effectively the owner's link.
  - **B (explicit):** a `short_url.domain` field stores the host it was created under; resolution is
    `(host, code)` → exact link.
- **Recommendation: B** for clarity (a link is "shortened for a domain"). A link with no custom domain →
    resolves under the default host via `GET /{id}` as today.

The default host (`localhost`/the server host) stays supported; `GET /{id}` remains the fallback.

---

## 5. Rich analytics

### 5.1 Enrich the event

Extend the click event (and `ClickEventDocument`) with:

```text
shortCode   String
timestamp   Instant
userAgent   String
ip          String          # already captured
referrer    String          # NEW — capture the Referer header (sanitized; never PII beyond the value)
device      String          # NEW — derived from User-Agent: MOBILE / DESKTOP / TABLET (simple parse)
country     String          # NEW — optional; from IP via GeoIP (MaxMind), configurable, nullable
```

- The redirect captures `shortCode`, `timestamp`, `userAgent`, `ip`, and `referrer` (cheap, no blocking).
- The **worker** derives `device` (simple User-Agent parse, no heavy lib) and, if enabled, `country`
  (GeoIP). Enrichment is best-effort and must not fail the batch.

### 5.2 Rollup (click_daily)

For cheap temporal queries, add a `click_daily` aggregation:

```text
click_daily
  shortCode   String
  day         LocalDate     # YYYY-MM-DD (UTC)
  clicks      long          # raw count
  uniqueDays  long          # (optional) days with ≥1 unique visitor
  device_referrer_geo_stats  # optional sub-structure for breakdown
```

- A scheduled job aggregates raw `click_events` by `(shortCode, day)` into `click_daily`. Idempotent and
  bounded. Keep raw `click_events` for detail and retention.
- Migration V9: index `(shortCode, day)` on `click_daily`.

### 5.3 Query endpoint

```http
GET /api/v1/urls/{id}/clicks?unit=day|hour&from=<ISO>&to=<ISO>
Authorization: Bearer <token>     (owner-only)
```

- Returns the time series (buckets) for the range, from `click_daily` (day) or a derived hourly series.
- Optionally return a breakdown by `device` / `country` / `referrer` (from the rollup or a lightweight
  aggregation over `click_events`).

### 5.4 Unique vs raw (HyperLogLog, optional)

- Maintain a Redis HLL key per `(shortCode, day)` (or per shortCode over a range) to estimate **unique
  visitors** cheaply (by IP or a hash of IP+UA). Add the unique count alongside the raw count in the query.
- This is optional and additive; the authoritative count is the rollup. Do not block the redirect on HLL.

---

## 6. Migration & config

- **V8:** `custom_domains` collection + `host` unique index; `short_url` gets a `domain` field (nullable)
  if option B.
- **V9:** `click_daily` collection + `(shortCode, day)` index.
- Config: enable/disable GeoIP (`app.analytics.geo.enabled`, default `false` for on-prem billing reasons),
  GeoIP DB path, DNS-verify interval, rollup cron.

---

## 7. Verification commands

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false      # domain resolution + analytics rollup/query ITs
mvn verify
bash scripts/check-boundaries.sh                 # core stays framework-free
```

---

## 8. Documentation deliverables

- `docs/data-model-decisions.md` — `CustomDomain` + `(host, code)` resolution (option B), `click_daily`
  rollup, event enrichment (device/geo/referrer), unique-via-HLL.
- `docs/coding-standards.md` — host-aware redirect rule; analytics enrichment in worker (not redirect);
  no blocking in redirect.
- `docs/testing-playbook.md` — add domain-resolution + analytics-query ITs to the suite map/gaps.
- `README.md` / `AGENTS.md` — document custom-domain & rich-analytics; note the GeoIP/DSN decision and
  debt updates.
- OpenAPI: new `/api/v1/domains` and `/api/v1/urls/{id}/clicks` routes documented.

The epic is **not** Done while a link cannot resolve under a verified custom domain, or while the link
detail cannot return a temporal/geo/device/referrer time series.
