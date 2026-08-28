# AI Software Engineer Prompt — Branded Domain & Rich Analytics (Phase C)

**Status:** ready for implementation from current `main` (`c0f5b45`, `v0.12.0`).
**Priority:** P1 — the product differentiator. Third epic of the "Bitly-like" turn.
**Target:** let links be shortened or resolved under a **verified custom domain** (`marca.co/promo`) and
let the link detail return **rich analytics** (time series, geo, device, referrer, unique-vs-raw) — all
owner-guarded and without slowing the redirect.

You implement the complete **Branded Domain & Rich Analytics** epic. Phase A (read path) and Phase B
(links-as-resource) are done. This is the biggest product jump.

---

## Sources of truth — read in this order

1. `AGENTS.md` (rules 5, 7, 10; "no blocked redirect" principle)
2. `docs/data-model-decisions.md` (custom-domain + analytics)
3. `docs/coding-standards.md` (host-aware redirect; enrichment in worker, not redirect)
4. `docs/testing-playbook.md`
5. `tasks/branded-domain-analytics-spec.md`
6. `tasks/branded-domain-analytics-backlog.md`
7. `tasks/branded-domain-analytics-implementation-sequence.md`
8. `core/model/ShortUrl`, `core/model/ClickEvent`, `infra/adapter/output/persistence/entity/ClickEventDocument`,
   `core/ports/outgoing/*`, `MongoClickEventRepository`, `ClickBatchWorker`, `UrlController` (redirect),
   `infra/.../migration/*`, `infra/config/SecurityConfig`, `MongoCollections`

If documentation disagrees with executable configuration, stop, report and resolve in the same change.

---

## Goal

Today the redirect resolves by **path only** (`GET /{id}`, no `Host`), the click event has only
`shortCode/timestamp/userAgent/ip` (no `referrer`, no device/geo), and there is no rolled-up analytics.
This epic adds a **custom domain** with DNS verification and **host-aware redirect** resolution, plus
**rich analytics** (temporal, geo, device, referrer, unique-vs-raw) queried on the link detail.

---

## Locked technical decisions

1. **Custom-domain resolution = option B.** A `short_url.domain` field stores the host a link was created
   for; the redirect reads the `Host` header and resolves by `(host, code)`; the default host falls through
   to `GET /{id}`. A `CustomDomain` has `host`, `userId`, `status` (PENDING/VERIFIED/ACTIVE/FAILED),
   `verificationToken`, `createdAt`.
2. **DNS verification.** On claim, generate a verification token (TXT/CNAME); a scheduled DNS health check
   promotes to `ACTIVE`; missing/expired → `FAILED`; re-trigger works.
3. **Enrich analytics in the worker, NEVER in the redirect.** The redirect captures only cheap fields
   (`shortCode`, `timestamp`, `userAgent`, `ip`, `referrer`). `device` (simple User-Agent parse) and
   `country` (GeoIP, optional) are derived in `ClickBatchWorker`. Enrichment must not fail the batch.
4. **`click_daily` rollup (migration V9).** A scheduled, idempotent, bounded job aggregates raw
   `click_events` by `(shortCode, day)`; index `(shortCode, day)`. Raw `click_events` kept for detail/retention.
5. **Analytics query endpoint** `GET /api/v1/urls/{id}/clicks?unit=day|hour&from=&to=` (owner-only) → time
   series + device/geo/referrer breakdown. `401`/`403`/`404` as appropriate.
6. **Unique-vs-raw via Redis HyperLogLog** (optional, additive). The authoritative count is the rollup; HLL
   must not block the redirect.
7. **Owner guard** applies to domain management and the analytics query (application-level, not just route).
8. **GeoIP is optional, off by default** (`app.analytics.geo.enabled=false`). **Any new Maven coordinate
   (e.g. MaxMind `geoip2`) requires explicit approval.** The device parse and DNS check need no new dep.
9. **No blocking in the redirect.** Host resolution is cached/cheap; analytics is async.

---

## Non-negotiable engineering rules

- Keep `core/` framework-free; custom-domain and analytics types are pure domain.
- Never enrich or block analytics in the redirect path; only capture the cheap fields.
- Host-aware resolution must not add a slow lookup on the redirect (cache the custom-domain map / host).
- Owner guard enforced at the application layer (403 for non-owner), not just the route rule.
- `click_daily` rollup is idempotent and bounded; don't re-aggregate raw events per read.
- The redirect stays fast; no synchronous external call in the redirect.
- English only in code, comments, logs, tests and docs.
- Do not push unless the human explicitly asks.
- Do not expand into: QR/link-in-bio/dashboards UI, API keys/webhooks/bulk (Phase D), read/write split or
  multi-region (Phase E), orgs/RBAC/SSO/billing (Phase F).

---

## Required behaviour summary

### Custom domain
- `CustomDomain` model + `custom_domains` collection (V8, unique `host` index).
- Claim/verify domain + DNS health-check job; `POST/GET/DELETE /api/v1/domains` (authenticated, owner-only).
- Host-aware redirect resolution (option B) via `short_url.domain`; default host via `GET /{id}`.
- Shorten/PATCH a link under an owned, verified domain.

### Rich analytics
- Extend the event/document with `referrer`, `device`, `country` (nullable).
- Enrich device (UA parse) + country (GeoIP, optional/off) in the worker.
- `click_daily` rollup (V9) + scheduled aggregation; raw events kept.
- `GET /api/v1/urls/{id}/clicks?unit=day|hour&from=&to=` (owner-only) → time series + breakdown.
- Optional HyperLogLog unique-vs-raw count (additive).

---

## Scope exclusions

Do not implement: QR/link-in-bio/dashboards, API keys/webhooks/bulk (Phase D), read/write split or
multi-region (Phase E), orgs/RBAC/SSO/billing (Phase F), or any blocking call in the redirect.

---

## Definition of Done

### Custom domain
- [ ] `CustomDomain` model + V8 migration (unique `host`, `userId`).
- [ ] Claim/verify + DNS check job; `POST/GET/DELETE /api/v1/domains` owner-guarded.
- [ ] Host-aware redirect resolution (option B); default host via `GET /{id}`; redirect stays fast.
- [ ] Shorten/PATCH under an owned, verified domain (400/403 for unverified/other's).

### Rich analytics
- [ ] `ClickEvent`/`ClickEventDocument` extended (`referrer`, `device`, `country`); redirect captures
      `referrer` cheaply.
- [ ] Worker enriches device/geo (GeoIP optional/off); never in the redirect; never fails the batch.
- [ ] `click_daily` rollup (V9) + scheduled, idempotent, bounded aggregation.
- [ ] `GET /api/v1/urls/{id}/clicks` (time series + breakdown) owner-guarded; OpenAPI updated.
- [ ] Unique-vs-raw via HLL (optional) returned; does not block redirect.

### Verification & delivery
- [ ] Domain + analytics ITs green; owner guard (403); `mvn test`, `mvn verify`, `check-boundaries.sh` pass.
- [ ] Docs (`data-model-decisions`, `coding-standards`, `testing-playbook`, `README`) and `AGENTS.md` synced;
      no "redirect blocks on analytics" claim.
- [ ] Redirect host-resolution cached/cheap; no synchronous external call.

Start at **Step 0** of `branded-domain-analytics-implementation-sequence.md`. Stop immediately if the
baseline is red, a locked decision conflicts with the approved dependency graph (e.g. GeoIP coordinate not
approved — keep it off), or repository state contradicts the spec.
