# Custom-Domain Edge — TLS Provisioning, Routing and Behavior

**Question this document answers:** the API returns `https://<custom-domain>/<id>` for links bound
to a verified custom domain — who provisions and renews the TLS certificate for each customer
domain in production, who routes that traffic, and what happens at each lifecycle stage?

**Answer:** the supported production edge is **Caddy with on-demand TLS**, gated per handshake by
the application's ACTIVE-domain registry through the `ask` endpoint
(`GET /internal/edge/domain-ask`). Caddy provisions and renews certificates automatically via
ACME (or the internal CA in dev); NGINX remains supported **only for single-host deployments**
(it has no sane automated per-domain ACME flow on-prem).

---

## 1. Architecture

```
customer browser                edge (Caddy)                       app (URL shortener)
     |                               |                                   |
     |  https://go.acme.io/QwEYIVE    |                                   |
     |------------------------------->|  SNI: go.acme.io                 |
     |                                |  (cert not cached yet)            |
     |                                |--- ask -------------------------->|
     |                                |  GET /internal/edge/domain-ask   |
     |                                |      ?domain=go.acme.io&token=…  |
     |                                |<-- 200 (ACTIVE in registry) -----|
     |                                |  ACME cert issued for go.acme.io |
     |                                |  (cached; renewed automatically)  |
     |<-------------------------------|                                   |
     |            302 Location: https://example.com/cd-custom           |
     |                                |-------- reverse_proxy ---------->|
     |                                |  Host: go.acme.io (preserved)    |
     |                                |  app serves host-bound link (302) |
```

- **Edge config:** `deploy/proxy/Caddyfile` — a static block for the configured public host and a
  catch-all `https://` block with `tls { on_demand }`; the global `on_demand_tls { ask … }`
  points at the app with the shared `EDGE_ASK_TOKEN`.
- **Ask endpoint:** `EdgeDomainAskController` (`/internal/edge/domain-ask`, permitAll in the
  security chain — the constant-time-compared shared token IS the authentication, fail-closed when
  unset). Authorization = the configured public host (`app.domain.default-host`) **or** an ACTIVE
  custom domain in the registry (`CustomDomainRegistryPort`, the same Redis-backed snapshot the
  redirect hot path uses). Caddy sends the host in the `domain` query parameter (a `host` alias is
  accepted for operators/curl).
- **Provisioning/renewal:** first handshake for an unknown SNI → ask → 200 → ACME issuance
  (Let's Encrypt via the `email` in the Caddyfile; HTTP-01/TLS-ALPN-01 both work because the
  domain resolves to the edge). Certificates are cached in Caddy's storage and **renewed
  automatically**. No operator action per domain, ever.

## 2. Per-domain lifecycle (behavior definition)

| Stage | Data plane | `shortUrl` in API responses | Edge (TLS) |
| :--- | :--- | :--- | :--- |
| Unclaimed | binding a link to the host is rejected (not claimed) | never produced for this host | ask → 404, handshake refused |
| PENDING (claimed, TXT token not yet verified) | binding still rejected (`DomainBindingValidator` requires ACTIVE) | never produced | ask → 404, handshake refused |
| ACTIVE (TXT verified) + DNS not yet pointed at the edge | links may bind; API returns `https://<domain>/<id>` | produced — but **not yet clickable**: TLS cannot be provisioned until the customer's CNAME/A actually reaches the edge (the ACME challenge must resolve to it). This is the customer's documented setup step; inherent to any TLS-terminating multi-tenant edge. | first request after DNS propagation → certificate provisioned automatically |
| ACTIVE + DNS pointed | host-bound links served (`302`) | produced and servable | certificate cached, renewed automatically |
| Verification lost / claim removed (registry `markInactive`) | **app refuses to serve anything on that host — 404** (host matching, REQ-SHORT-004) | existing stored responses still show the URL (historical data); new bindings rejected | ask → 404: no NEW issuance. A cached certificate may keep terminating TLS until expiry (see §3), but the app answers 404 behind it |

**The invariant that answers the review question:** the API only ever returns `https://<domain>`
for domains that are ACTIVE in the registry — and ACTIVE is *exactly* the set the edge is
authorized to provision certificates for (same registry, same check). The residual unservable
window is the customer's DNS step (documented in the claim/verify response and §2), not a
backend-generated promise we cannot keep.

## 3. Measured behavior (dev validation, internal CA) — including a finding

Validated locally (see §5 evidence): default host routing, on-demand issuance for an ACTIVE
domain, refusal of unknown hosts, token fail-closed on issuance, and revocation.

**Finding (important, now part of the contract):** Caddy consults the `ask` endpoint at
**issuance** time; a **cached** certificate keeps terminating TLS on later handshakes even after
the ask endpoint starts denying (measured: wrong edge token + cached cert → handshake still
succeeds; new host with wrong token → refused, ask denial logged by the app). Consequences:

- Revocation is enforced primarily by the **data plane**: a revoked (non-ACTIVE) host gets 404
  from the app for every link, measured end-to-end through the edge (TLS succeeds via the cached
  cert, app answers 404). No click is ever served for a revoked domain.
- Operators who need TLS itself to stop can flush the certificate from Caddy's storage
  (`caddy storage delete` or `X-Caddy-Storage` tooling) — optional, defense in depth already holds.
- The token protects **issuance** (an unauthorized edge must not be able to mint certificates for
  our domains); it is re-checked for every new certificate.

## 4. NGINX vs Caddy (decision)

- `deploy/proxy/nginx.conf` stays for **single-host** deployments (one public host, static
  certificate files, no custom domains).
- Custom domains require the **Caddy** edge: it is the only supported on-prem path with
  automatic per-domain ACME issuance and renewal. The Caddyfile and this document are the
  deployment contract for that mode. (An NGINX + per-domain certbot scripting path was considered
  and rejected: manual, per-domain, no renewal story for multi-tenant SNI.)

## 5. Dev validation evidence (2026-10-03, redacted)

Local Caddy 2 (on-demand TLS + internal CA — the only difference from production is the CA) with
the ask endpoint wired to the real app (token redacted; no secrets captured):

```
ask ACTIVE domain        → 200        (authorized)
ask default host          → 200        (authorized)
ask unknown host          → 404        (not ours)
ask wrong token           → 401        (not the configured edge)

E1 default host via Caddy            → HTTP/2 302 + location: https://example.com/cd-default
E2 custom domain via Caddy            → HTTP/2 302 + location: https://example.com/cd-custom
   cert issuer (on-demand issuance)  → issuer=CN = Caddy Local Authority - ECC Intermediate
E3 unknown host through the edge     → TLS handshake refused (curl exit 35; ask said 404)
E4b new host, WRONG edge token       → refused (curl exit 35); app logged the ask denial
E5 revoked domain through the edge    → TLS OK (cached cert) but app answers HTTP/2 404
                                       (data-plane defense in depth; ask already 404)
```

Raw redacted outputs: `tasks/public-url-alias-fix/dev-edge-evidence.md` (first edge run) and the
Edge ITs (`EdgeDomainAskIT` — ACTIVE/PENDING/default/unknown/token/revocation against the real
registry). Production parity: ACME instead of the internal CA; the `ask` contract is identical.

## 6. Operational checklist (Caddy edge)

- [ ] Render `EDGE_ASK_TOKEN` into the Caddyfile ask URL **and** set the same value on the app
      (`EDGE_ASK_TOKEN` env). Empty token = the ask endpoint fails closed (no custom-domain cert
      can ever be provisioned) — set it only when the on-demand edge is deployed.
- [ ] `app.domain.default-host` = the configured public host (both Caddy block and app).
- [ ] Caddy `email` set (ACME account/notifications).
- [ ] `rate-limiter.trusted-proxy-cidrs` covers the edge address (per-IP budgets stay correct).
- [ ] Customer onboarding step documented: claim → verify TXT → point CNAME/A at the edge → first
      click provisions TLS automatically.
- [ ] Verify after deploy: `curl -s "http://<app>/internal/edge/domain-ask?domain=<default>&token=<token>"` → 200.
