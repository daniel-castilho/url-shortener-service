# ADR 0009 — Living specs: Javadoc structure is a machine-read contract, exempt from formatting

- **Status:** Accepted
- **Date:** 2026-09-13
- **Context:** Business Components declare their EARS requirements in `package-info.java`
  (living specifications, debt 32). The declaration format is structural: `### REQ-<COMP>-<NNN>`
  headings, one per requirement, each followed by `**When** ... **the Business Component
  shall** ...` lines, plus the `# Component:` line and the `@spec-complete true` marker. The
  living-spec gate (`scripts/check-living-spec.sh`) and the JSON extractor
  (`scripts/extract-requirements.sh`) parse this structure as **text** — line-anchored regexes
  that rely on one `### REQ-*` heading per line and on the Javadoc block layout staying intact.
  google-java-format (run by Spotless at `validate`) restructures Javadoc as prose: it merges
  lines into paragraphs, wraps them in `<p>` tags and moves content onto shared lines. Applied to
  a living-spec `package-info.java`, it destroyed the `### REQ-*` line anchors and silently
  un-declared requirements (observed 2026-09-13: the pilot spec was reformatted and the gate
  stopped finding `REQ-RATE-001..005`).
- **Decision:** The exclusion of `**/package-info.java` from google-java-format in `pom.xml`
  (Spotless `<excludes>`, the `src/main/java/**/*.java` include set) is **by design, not debt**.
  The Javadoc structure inside a `package-info.java` living spec is a machine-read contract:
  humans may reflow prose inside a requirement's EARS text freely, but the `### REQ-*` heading
  lines, the `# Component:` line and the `@spec-complete` marker must stay line-anchored for the
  gate to read them. Formatting is subordinated to the contract the gate parses. The gate's
  `--self-test` doubles as the **format contract test**: it plants well-formed and malformed
  specs and proves the gate still reads them, so a tooling change that breaks the accepted
  structure fails loudly at `bash scripts/check-living-spec.sh --self-test` instead of silently
  un-declaring requirements in CI.
- **Consequences:**
  - `package-info.java` files are hand-formatted; Spotless still enforces
    trailing-whitespace/newline hygiene on every other Java file.
  - Editing an EARS paragraph is unrestricted; moving or rewording a `### REQ-*` heading is a
    spec change (rename = requirement identity, gated by traceability).
  - The exclusion applies to **all** `package-info.java` files, not only living specs — the
    repository had none before the pilot, so the blast radius is exactly the living specs.
- **Rejected / deferred:**
  - **AST/doclet-based extraction now** — compiling the sources and reading Javadoc via the
    annotation/doclet API would make the gate immune to formatting. Deferred (not rejected):
    the text extractor is regex-simple, self-tested, and currently has zero known false
    positives/negatives; the investment only pays if the text approach degrades in practice.
  - **Revisit trigger:** if the text-based extractor produces **two or more false
    positives/negatives** in practice (e.g. formatting accidents, ambiguous headings, gate
    disagreement with the annotation set), re-evaluate the AST/doclet extractor. Until then the
    exclusion stands and this ADR closes debt 32(c).
