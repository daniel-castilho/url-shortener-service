# Epic 2 – Stories (Acceptance)

| # | Story | Acceptance Criteria | Agile Reference / Anchor |
|---|-------|------------------------|--------------------------|
| **2.1** | **Sink sanitization – logSafe** – implement `logSafe(String)` in all `log.warn`/info of `infra/`, stripping `\n`/`\r` (lesson 20 of `lessons.md`). | • `grep -R logSafe src/main/java/infra` → 100 % covered <br>• CI `log-injection` gate PASS <br>• No test log contains `\n` or `\r` from client-controlled input | Rule 20 of `lessons.md` (log injection) |
| **2.2** | **SSRF protection** – the shortening endpoint rejects destinations with internal/private/link-local IPs; HTTPS-only + secure hostname; return 400. | • `SsrfProtectionIT` extended → 400 for literal IPs (`127.0.0.1`, `10.0.0.1`, `172.16.0.1`, `192.168.0.1`, `169.254.169.254`, `[::1]`) <br>• `security.ssrf.blocked.total` metric incremented and visible in `/actuator/prometheus` <br>• Documentation updated with examples of blocked IPs | Rule 6 of `AGENTS.md` (Security & Secrets) |
| **2.3** | **ConfigValidator fail‑fast** – in the `prod` profile the boot aborts if `APP_JWT_SECRET` is the default value or shorter than 32 characters (current `ProdConfigValidator` rule). | • `ProdConfigValidatorIT` → boot fails with a default/short secret, passes with a strong secret <br>• `APP_JWT_SECRET` defined via env-var <br>• `AGENTS.md` documentation updated | Rule 6 of `AGENTS.md` (Security & Secrets) |
| **2.4** | **HTTP security headers** – via native Spring Security (`.headers()` in `SecurityConfig`): `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin` on all responses. | • `SecurityHeadersIT` test → headers present on representative routes <br>• No header duplicated or overwritten | Rule 6 of `AGENTS.md` (Security & Secrets) |
| **2.5** | **OWASP Dependency‑Check gate** – `dependency-check-maven` **12.2.2** (pin 12.x; keyless 13.x broken — upstream #8715) blocks the build if CVSS ≥ 7 is introduced; versioned empty suppression file (future entries with rationale + review date); NVD data cached in CI and **primed from the ODC nightly mirror** (`nvdDatafeedUrl` — cold sync ~2 min vs >1 h stuck directly on the NVD API, upstream issues #7431/#8435); `NVD_API_KEY` stays in the org but is not wired (the datafeed ignores it). | • `./mvnw dependency-check:check` → fails if a new vulnerable artifact appears <br>• CI job `security-check`/dep‑check green <br>• `./mvnw dependency:tree` listed in the PR for manual review | Rule 9 of `AGENTS.md` (No Unapproved Dependencies) |

---

**Quick traceability:**

| Story | Reference doc | AGENTS.md |
|-------|----------------|-----------|
| 2.1 | `logSafe` present in `infra` (pasted grep) | Rule 2 + Rule 6 |
| 2.2 | `SSRFIT` → 400 for internal IPs | Rule 6 |
| 2.3 | `ConfigValidatorIT` → fails in prod with a default secret | Rule 6 |
| 2.4 | HTTP headers pasted from `curl -I` | Rule 6 |
| 2.5 | `owasp-dependency-check` gate green | Rule 9 |

--- 

*Next step: create the technical tasks (2.1‑2.5) and the testing strategy (epic‑2‑testing.md).*
