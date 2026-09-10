# Handoff Definition of Done — reusable appendix for every execution prompt

**Rule zero — zero-from-memory:** EVERY number, sha, run id, and test count in a handoff must be
copy-pasted from a command output included in the handoff. If you cannot paste the command that
produced it, the claim does not go in. A number written from memory is a hypothesis, and unlabeled
hypotheses in closing handoffs are defects (TD-13 class).

## 1. Mandatory evidence block (paste RAW outputs)

```bash
# The exact chain — nothing else
git log --oneline <branch-base>..HEAD

# Clean-tree proof
git status --porcelain

# Run pairs (number AND sha) come FROM here — never from memory
gh run list --limit 10

# Surefire summary for every class you cite
grep -h "Tests run" **/target/surefire-reports/*.txt | tail -30

# Per-cited-class test inventory — use the surefire summary above;
# anchored pattern (unanchored counts @Testcontainers too;
# this project ships inflated numbers if not anchored)\n
# Example anchored check (replace <test-class> with actual class):
grep -c "@Test" <each cited test file>   # or just reuse the surefire summary itself
```

## 2. Self-audit — run BEFORE sending (any \"no\" = fix the handoff, not the audit)

- [ ] Every sha resolves: `git cat-file -e <sha>` for each one cited
- [ ] Every (run number, run sha) pair appears verbatim in your pasted `gh run list`
- [ ] Every count equals the pasted surefire/grep output (never rounded, never remembered;
      omitting counts is always acceptable — inventing them never is)
- [ ] Every red is IN the table with its pair (a red in a footnote = TD-13)
- [ ] Every \"owner approved X\" QUOTES the channel message that approved it
- [ ] Every claim about `<default-branch>` is true of `<default-branch>`: work that is local-only is labeled
      `LOCAL — awaiting push`, never described as landed
- [ ] No closure claims: closure is adjudicated by the owner channel; handoffs report state + gaps
- [ ] Flip = last content commit; citation = separate final commit, citing a run whose tree IS the
      flip, with nothing landed after it

## 3. Standing definitions

- **Pair** = (test, run number, run sha). Ids alone rot; numbers alone drift; both, from `gh run list`.
- **Evidence** = pasted command output. Memory = hypothesis. Hypotheses are labeled as such.
- **Owner sanction** = a quoted channel message. Anything else is attribution; false attribution is
  TD-30 class.
- **LOCAL** prefix = true and unlanded. Never upgrade LOCAL to landed.

## 3. Failure classes this codifies (the E9 record — why each rule exists)

| Rule | The failure it kills |
|---|---|
| §1 `gh run list` | Invented run ids; off-by-one numbers |
| §1 surefire/grep | Invented counts (e.g. 21/21, 22, 1/1·6/6·2/2·10/10·3/3 vs real 31/34) |
| §2 sha check | Citing commits that resolve nowhere |
| §2 main-claims | \"re-enabled\" against a commit message reading \"disabled (HOLD)\"; Known-Gap narrative about a @Disabled test |
| §2 owner-quote | `@Disabled(\"HOLD: owner re-baselining...\")` with no owner authorization |
| §1 arithmetic | Correct per-class list, wrong sum (TD-34: 1+6+2+10+3+1 stated as 22 — the TD-31 correction itself carried the off-by-one it fixed) |
| §2 no-closure | Four consecutive \"E9 CLOSED\" declarations from one epic |

---

_This file is mandatory for every PR/merge hand‑off. Without the evidence block and self‑audit, the handoff
will be rejected by the owner channel._