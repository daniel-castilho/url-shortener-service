# Dev-Edge Validation Evidence — custom-domain on-demand TLS

**Branch:** `feat/custom-domain-edge-ondemand-tls` · **Date:** 2026-10-03 · **Runner:** dev
workstation (local Docker; no staging/production touched).

**Scenario:** the app behind a Caddy 2 edge configured exactly like production
(`deploy/proxy/Caddyfile` semantics: static block for the public host + catch-all with
`tls on_demand`, global `on_demand_tls ask` → `/internal/edge/domain-ask`), with **one difference:
`local_certs`** — Caddy's internal CA instead of ACME, so no public DNS or Let's Encrypt
rate-limits are needed locally. The ask contract is identical in production.

**App environment (dev profile):** `APP_PUBLIC_BASE_URL=https://dev.short.local`,
`APP_DOMAIN_DEFAULT_HOST=dev.short.local`, `EDGE_ASK_TOKEN=<REDACTED>` (dummy; set, never
printed), edge trusted (`RATE_LIMITER_TRUSTED_PROXY_CIDRS` loopback + docker bridge).

**Seeding:** user via API; `go.acme.dev` ACTIVE in `custom_domains` (Mongo) + registry (Redis set)
— mirroring a TXT-verified claim; a link bound to it (`shortUrl: https://go.acme.dev/QwEYIVE`)
and a default-host link (`https://dev.short.local/RKaGswo`).

**Redaction:** the shared token is `<REDACTED>` everywhere; no JWTs, cookie values or passwords
appear (the test account's throwaway password is not part of any output).

---

## 0. Ask endpoint (direct, token redacted)

```
ask go.acme.dev (ACTIVE)   → 200
ask dev.short.local (default host) → 200
ask unknown.acme.dev      → 404
ask go.acme.dev, wrong token → 401
```

## 1. Default host through the edge (static block)

```
$ curl -k --resolve dev.short.local:9443:127.0.0.1 https://dev.short.local:9443/RKaGswo
HTTP/2 302
location: https://example.com/cd-default
```

## 2. Custom domain through the edge — certificate provisioned ON DEMAND

```
$ curl -k --resolve go.acme.dev:9443:127.0.0.1 https://go.acme.dev:9443/QwEYIVE
HTTP/2 302
location: https://example.com/cd-custom
```

Issuer of the certificate served for `go.acme.dev` (captured after the first handshake):

```
subject=
issuer=CN = Caddy Local Authority - ECC Intermediate
```

(A certificate existed only because Caddy asked the app, got 200 for the ACTIVE domain, and issued
it on the spot — in production the issuer is the ACME CA configured via `email`.)

## 3. Unknown host — refused by the ask gate

```
$ curl -k --resolve unknown.acme.dev:9443:127.0.0.1 https://unknown.acme.dev:9443/x
curl exit: 35            # TLS handshake refused (ask answered 404 — not ours)
```

## 4. Fail-closed token (issuance)

Edge reloaded with a WRONG token in the ask URL; a second ACTIVE domain (`go2.acme.dev`) was
seeded for a fresh issuance attempt:

```
$ curl -k --resolve go2.acme.dev:9443:127.0.0.1 https://go2.acme.dev:9443/QwEYIVE
curl exit: 35            # refused — the app logged "missing/wrong token — denying"
ask go2.acme.dev, correct token (direct) → 200 (authorized; only the edge was misconfigured)
```

**Measured Caddy semantics:** a previously issued (cached) certificate keeps terminating TLS after
the token goes wrong — ask gates **issuance**, not every handshake of cached certs. Revocation is
enforced by the data plane (next block) — see `docs/custom-domain-edge.md` §3.

## 5. Revoked domain — defense in depth end-to-end

`go.acme.dev` removed from the ACTIVE registry (revoke), good token restored on the edge:

```
ask go.acme.dev (direct)   → 404
$ curl -k --resolve go.acme.dev:9443:127.0.0.1 https://go.acme.dev:9443/QwEYIVE
HTTP/2 404                 # TLS succeeds (cached cert) but the APP refuses the link
curl exit: 0
```

The revoked domain cannot serve a single link even though TLS still terminates: the app's
host-bound matching (REQ-SHORT-004) 404s everything on a non-ACTIVE host.

---

## Coverage vs. the question asked

| Question | Answer | Evidence |
| :--- | :--- | :--- |
| TLS provisioning per custom domain | Caddy on-demand TLS, ask-gated by the ACTIVE registry | §2 (issuer proves issuance) |
| Renewal | automatic (Caddy renews cached on-demand certs; no per-domain ops) | docs §1; issuance measured |
| Routing | catch-all `https://` block preserves Host; app serves host-bound links | §1/§2 (302 per host) |
| Unknown / PENDING hosts | refused (ask 404) — never issued, never routed | §3, `EdgeDomainAskIT` |
| Wrong/unset edge token | fail-closed for issuance (401 → handshake refused) | §4, `EdgeDomainAskControllerTest` |
| Revocation | ask 404 for new issuance + app 404 for every link (defense in depth) | §5 |
| Production parity | identical ask contract; only the CA differs (ACME vs internal) | §2 note |

**Cleanup:** app terminated, Caddy container removed, dev database dropped, Redis registry key
deleted, session token files removed. No staging or production system was accessed.
