# Epic 2: Secure – Security by Design

**Project:** url-shortener-service  
**Context:** Java 25, Spring Boot 4.1.1, Hexagonal Architecture, MongoDB, Redis  
**Objective:** Ensure the application does not accept malicious inputs, does not expose secrets, and is free from known attack vectors (SSRF, log injection, insecure headers, vulnerable dependencies). All fixes must be evidenced with pasted commands and green CI tests.

---

## Why is this epic second?

- Maintainability (EP1) provides the package base and logging conventions that EP2 depends on.
- Poorly designed security forces later refactoring in subsequent epics (Performance, Observability, Deployable).
- The `AGENTS.md` rules (especially rule 2 about shas and counts) can only be validated if the code is stable and the metrics (EP3) are already structured.

**Relationship with other epics:**

| Epic | Direct dependency |
|--------|-------------------|
| EP3 – Observable | New structured logs and security metrics (e.g.: `security.ssrf.blocked.total`). |
| EP4 – Tests | Security test stories (log injection, SSRF, headers). |
| EP5 – Performance | Ensure that security mitigations do not degrade p95 latency. |
| EP6 – Scalable | Ensure that input validations do not become a bottleneck when scaling. |
| EP7 – Reliable | Circuit‑breaker and bulkhead names aligned with the security exceptions. |
| EP8 – Deployable | Docker images with security labels and integrated OWASP gate. |

**Elevated Acceptance Criteria:**

1. `./mvnw verify` → **green** with all security gates (SpotBugs, OWASP Dependency‑Check, CodeQL).
2. `check‑boundaries.sh` → **PASS** (`infra.*` imports still forbidden in `core/`).
3. `logSafe(String)` present in all `log.warn`/`info` of `infra/` (pasted grep).
4. `SSRFIT` test → 400 for internal IPs (`127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.169.254`).
5. `ConfigValidator` fail‑fast in the `prod` profile; warning if `APP_JWT_SECRET` is the default (`ConfigValidatorIT` test).
6. HTTP security headers present in `curl -I https://sistema/` (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`).
7. `./mvnw dependency:tree` does not introduce new dependencies with CVSS ≥ 7 without human approval (CI gate blocks).

**Quick traceability:**

| Story | Reference doc | AGENTS.md |
|-------|----------------|-----------|
| 2.1 | `logSafe` present in `infra` (pasted grep) | Rule 2 (ID Generation Standard) + Rule 6 (Security & Secrets) |
| 2.2 | `SSRFIT` → 400 for internal IPs | Rule 6 (Security & Secrets) |
| 2.3 | `ConfigValidatorIT` → fails in prod with a default secret | Rule 6 |
| 2.4 | HTTP headers pasted from `curl -I` | Rule 6 |
| 2.4 | `owasp-dependency-check` gate green | Rule 9 (No Unapproved Dependencies) |

--- 

*Next step: create the detailed stories (2.1‑2.5) and the corresponding technical tasks.*
