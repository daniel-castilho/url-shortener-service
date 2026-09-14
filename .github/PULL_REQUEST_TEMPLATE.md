## Deployment hygiene checklist

Answer every item before requesting review. A PR that skips an item ships risk into the release
path (see `docs/release-runbook.md` §7 and `docs/adr/0007-blue-green-bare-metal.md`).

- [ ] **Backwards compatible with the running version** — cached shapes (Redis keys/payloads,
  e.g. the `url:v1:` prefix), analytics stream payloads (REQ-ANALYTICS-006) and configuration
  remain readable by the current production release during the cutover window.
- [ ] **Any schema change ships in an EARLIER deploy (expand-only)** — no destructive
  drops/renames in this PR; destructive steps land one release before the code that stops using
  the old shape (REQ-PERSIST-003; the old colour keeps serving during cutover).
- [ ] **New config values already exist in the target environment** — every new
  property/env var is listed here and confirmed present (or defaulted) on the blue/green hosts.
- [ ] **Rollback trigger named** — which number rolls this back (metric threshold / error budget
  burn) and within which window; `scripts/rollback.sh` covers it with no manual steps.
- [ ] **How this reaches users** — canary stage 10/30/100 via `scripts/deploy.sh` (weighted
  cutover), flag, or direct; stale cache/CDN implications stated.
- [ ] **Who watches the first 15 minutes after cutover** — named on-call; first stop is
  `/actuator/health` + the SLO dashboard burn-rate panels (docs/slos.md); the smoke legs
  (scripts/smoke.sh) define the expected baseline.
