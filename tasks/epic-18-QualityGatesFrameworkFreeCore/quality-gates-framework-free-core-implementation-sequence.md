# Quality Gates & Framework-Free Core — Implementation Sequence
## Complete the framework-free `core/`, make the boundary gate green & wire quality gates

**Companions:** `quality-gates-framework-free-core-spec.md` · `quality-gates-framework-free-core-backlog.md`
**Rule:** complete each step's acceptance and verification before starting the next. Do not invent
out-of-scope work.

---

## Global execution rules

1. Work in small, reviewable vertical commits.
2. Read the referenced story acceptance before coding.
3. Add tests with the production change, not at the end.
4. A red baseline or ambiguous locked decision stops work.
5. Every boundary/quality-gate change updates the enforcement and the docs in the same step.
6. Never add a Maven coordinate without explicit approval (JaCoCo and SpotBugs are the only additions
   here and are pre-approved by this spec; anything else — ask).
7. After each step, update task status and note deviations; do not silently alter the specification.

### Fast verification (throughout)

```bash
mvn test
```

### Boundary verification

```bash
bash scripts/check-boundaries.sh
```

### Full gate

```bash
mvn verify
```

---

## Step 0 — Baseline, confirm the gate state, dependency gate
### Stories: (verify D0–D4)

### Actions

1. Confirm HEAD (`9691b31`) and that the previously-landed items (D0–D4) are present.
2. Run `bash scripts/check-boundaries.sh` — it should currently **fail** on the five annotated core
   classes (that is the known residual; record it).
3. Run `mvn test` — confirm a green fast loop.
4. Confirm the only remaining quality gate is the boundary check (no JaCoCo/SpotBugs yet).
5. Record the five offending classes and the exact boundary-gate failing output.
6. Get approval for the JaCoCo and SpotBugs versions (pre-approved in spec; confirm exact versions).

### Done when

- baseline is understood and the residual boundary failure is documented;
- dependency approach for JaCoCo/SpotBugs is approved;
- no unresolved "or/if available" choice remains.

### Verify

```bash
mvn test
bash scripts/check-boundaries.sh   # expected: FAIL (documented residual)
```

---

## Step 1 — Remove Spring/Lombok annotations from core
### Stories: Q1

### Actions

1. Edit the five classes to drop `@Component`/`@Service`/`@RequiredArgsConstructor` and the Lombok
   import; add an explicit constructor to each.
2. Keep the behaviour identical (same constructor dependencies as before).
3. Run the existing `core` tests — they should still pass with no Spring context.

### Done when

- no `org.springframework`/`lombok`/`org.mongodb`/`org.redisson`/`io.jsonwebtoken`/`io.micrometer`
  import in `core/`;
- each class has an explicit constructor.

### Verify

```bash
mvn test
```

---

## Step 2 — Register the five classes as `@Bean`
### Stories: Q2

### Actions

1. Add `@Bean` methods in `infra/config` (extend `ServiceConfig` or add `DomainConfig`) for
   `ReservedWordsValidator`, `CompositeUrlIdGenerator`, `RandomUrlIdStrategy`, `VanityUrlIdStrategy`,
   `QuotaService`.
2. Use explicit constructor injection (pass the concrete ports).
3. Ensure the `UrlShortenerService` `@Bean` still resolves from these beans.

### Done when

- the Spring context starts with no bean-definition error;
- all five beans resolve from their explicit constructors.

### Verify

```bash
mvn test
```

---

## Step 3 — Make the boundary gate green and enforced
### Stories: Q3

### Actions

1. Update `scripts/check-boundaries.sh` to also scan for `lombok` (and confirm it catches `@Component`
   via the `org.springframework` import check).
2. Confirm the CI "Architecture boundary check" step uses the same expression (or calls the script).
3. Run the script — it must now exit 0.

### Done when

- `scripts/check-boundaries.sh` exits 0;
- the CI boundary step passes;
- an intentionally-injected `@Component`/`lombok` import in `core/` makes it fail.

### Verify

```bash
bash scripts/check-boundaries.sh
```



---

## Step 4 — Wire the JaCoCo coverage gate
### Stories: Q4

### Actions

1. Add `jacoco-maven-plugin` (0.8.12) with `prepare-agent` + a `check` rule at `verify`.
2. Set `LINE ≥ 0.60`, `BRANCH ≥ 0.40` (adjust only with a documented justification).
3. Confirm it runs as part of `mvn verify`.

### Done when

- `mvn verify` enforces the floor;
- a test removal that drops coverage below the floor fails the build;
- the floor values are documented (coding-standards).

### Verify

```bash
mvn verify
```

---

## Step 5 — Wire the SpotBugs static-analysis gate
### Stories: Q5

### Actions

1. Add `spotbugs-maven-plugin` (4.8.6) with `effort Max`, `threshold High`, `check` goal.
2. Wire to `mvn verify`.
3. Fix or narrowly-justify any findings that surface.

### Done when

- `mvn verify` runs SpotBugs and fails on findings at the configured threshold;
- no broad suppressions.

### Verify

```bash
mvn verify
```

---

## Step 6 — CI steps + doc sync
### Stories: Q6

### Actions

1. Add CI steps (or confirm `mvn verify` runs them) for JaCoCo and SpotBugs after the build.
2. Update `docs/coding-standards.md` (framework-free core rule, JaCoCo/SpotBugs tolerances),
   `docs/testing-playbook.md` (command matrix, gate interpretation), `README.md` (quality gates /
   framework-free claim), `docs/lessons.md` (`@Component` → `@Bean` lesson).
3. Update `AGENTS.md` debt matrix: clear item 2, close item 12.

### Done when

- CI runs the coverage + static gates;
- `AGENTS.md` reflects the resolved items;
- docs match the framework-free core and quality gates.

### Verify

```bash
mvn verify
bash scripts/check-boundaries.sh
```

---

## Final smoke / acceptance path

1. `grep` `org.springframework|lombok|org.mongodb|org.redisson|io.jsonwebtoken|io.micrometer` in
   `src/main/java/ca/tyny/urlshortener/core` → no matches.
2. `bash scripts/check-boundaries.sh` → PASS, exit 0.
3. `mvn test` → green, no Spring context needed for core.
4. `mvn verify` → green and runs JaCoCo + SpotBugs.
5. Boot the app (or `@SpringBootTest` smoke) → context starts with all five beans wired.
6. `AGENTS.md` debt matrix / docs reflect the framework-free core and the gates.

---

_Pre-implementation sequence. Preserve deviations and final evidence as an as-built record after delivery._
