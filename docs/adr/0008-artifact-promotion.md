# ADR 0008 — Artifact promotion: the CI-built jar is what gets deployed

- **Status:** Accepted
- **Date:** 2026-09-12
- **Context:** The pom is `0.0.1-SNAPSHOT`; tags exist (`v0.13.0` current) but trigger nothing.
  The CI `build` job produces a jar that is never promoted: whoever deploys rebuilds locally, so
  the bytes that serve production are not the bytes the gates validated. Docker images are built
  ad-hoc with no SBOM or vulnerability gate. There is no GitHub Release, so there is no canonical
  place where "the artifact for vX.Y.Z" lives with a digest.
- **Decision:**
  1. **Single build, promoted verbatim** — the `release.yml` `gates` job runs the full
     `./mvnw -B verify -Drevision=<semver>` suite and uploads the jar it built; every downstream job
     (`k6-gate`, `runtime-smoke`, `restore-drill`) and the final `release` job consume **that**
     artifact. There is no second build anywhere in the pipeline.
  2. **`deploy.sh <tag>` downloads the exact jar from the GitHub Release** (asset named by semver)
     and **verifies the sha256** against the `SHA256SUMS` asset before staging it into
     `/opt/url-shortener/<color>/`. A local rebuild is never a deploy source.
  3. **Docker image is a secondary artifact** — built by the `release` job, non-root enforced
     (`docker run --entrypoint id` must not be UID 0), scanned with Trivy (fail on HIGH/CRITICAL,
     action SHA-pinned), and shipped with a CycloneDX SBOM. The image is convenience for
     container-based hosts; the systemd/bare-metal path consumes the jar.
  4. **Tags are immutable** — a bad release is fixed forward with `vX.Y.Z+1`; a tag is never moved
     or deleted (moving a tag invalidates every recorded sha256 and the Release that references it).
  5. **Auth is `GITHUB_TOKEN` only** — no PATs, no extra secrets in the workflow.
- **Consequences:**
  - "What is in production" is answerable with one lookup: `last-deploy.txt` names the tag; the
    Release names the sha256; the gates job is the only thing that ever built those bytes.
  - The jar name carries the semver (`url-shortener-service-<semver>.jar`) via the Maven
    `revision` property + flatten-maven-plugin (story 8.2) — no `SNAPSHOT` ever reaches a Release.
  - Deploying requires the Release to exist (fail-closed precondition): no Release, no deploy.
- **Rejected:**
  - **SSH-deploy from CI** — a human gate on bare metal is a recorded decision; CI produces the
    artifact and the operator runs `deploy.sh` on the host. Also removes the need for
    private-key secrets in the runner.
  - **A container registry as the mandatory promotion path** — pushes the operator to Docker for
    the app and adds a registry dependency for a systemd/bare-metal deployment contract.
    Registry publishing can be added later without changing this decision's promotion spine.
  - **`mvn release:perform`** — a heavyweight release plugin (SCM commits, tag juggling) that
    duplicates what the tag + `revision` + CHANGELOG gate already do in this repo, and it would
    rebuild the artifact it deploys (breaking rule 1).

## Links

- `docs/release-engineering.md`, `.github/workflows/release.yml`, `scripts/deploy.sh`
  (sha256 verification), `CHANGELOG.md` (keep-a-changelog promotion at tag time).
