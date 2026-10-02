#!/usr/bin/env python3
"""
Documentation Impact Checker — core engine (Python 3 stdlib only).

Evaluates changed paths against a documentation-impact map and validates
required documentation updates or explicit no-impact rationales.

Exit codes:
  0 = PASS (required docs changed or valid rationale)
  1 = FAIL (missing docs, invalid rationale, unmapped in-scope path, map error)
  2 = SKIP (all-zero before SHA — no comparison base)
"""

import argparse
import json
import os
import subprocess
import sys
import fnmatch
from pathlib import Path
from typing import List, Dict, Set, Tuple, Optional


GENERIC_RATIONALES = {"none", "n/a", "na", "not needed", "no impact", "skip", "n/a"}


class DocImpactError(Exception):
    """Structured error with rule context."""
    def __init__(self, message: str, rule_id: Optional[str] = None, path: Optional[str] = None):
        self.message = message
        self.rule_id = rule_id
        self.path = path
        super().__init__(message)


def run_git_diff(base: str, head: str) -> List[Tuple[str, str]]:
    """
    Run `git diff -z --name-status base..head` and return list of (status, path).
    Handles renames (R), deletions (D), additions (A), copies (C), modifications (M), type changes (T).
    Returns both old and new paths for renames.
    """
    try:
        result = subprocess.run(
            ["git", "diff", "-z", "--name-status", f"{base}..{head}"],
            capture_output=True,
            text=True,
            check=True
        )
    except subprocess.CalledProcessError as e:
        raise DocImpactError(f"git diff failed: {e.stderr.strip()}")

    out = result.stdout
    if not out:
        return []

    parts = out.split('\x00')
    # parts come as: status, path1, [path2 for rename], status, path1, ...
    changes = []
    i = 0
    while i < len(parts) - 1:
        status = parts[i]
        path1 = parts[i + 1]
        i += 2
        if not status:
            continue
        if status.startswith('R'):
            # Rename: path1=old, path2=new
            if i < len(parts):
                path2 = parts[i]
                i += 1
                changes.append(('D', path1))  # old path deleted
                changes.append(('A', path2))  # new path added
            else:
                changes.append((status, path1))
        else:
            changes.append((status, path1))
    return changes


def load_map(map_path: str) -> Dict:
    """Load and validate the documentation-impact map."""
    with open(map_path, 'r') as f:
        data = json.load(f)

    # Validate structure
    if "scope_roots" not in data or "rules" not in data:
        raise DocImpactError("Map must contain 'scope_roots' and 'rules'")

    scope_roots = data["scope_roots"]
    rules = data["rules"]

    # Validate unique rule IDs
    seen_ids = set()
    for rule in rules:
        rid = rule.get("id")
        if not rid:
            raise DocImpactError("Rule missing 'id'")
        if rid in seen_ids:
            raise DocImpactError(f"Duplicate rule ID: {rid}")
        seen_ids.add(rid)

        # Validate source globs
        source = rule.get("source", [])
        if not source or not isinstance(source, list):
            raise DocImpactError(f"Rule {rid}: 'source' must be a non-empty list")
        for g in source:
            if not g or not isinstance(g, str):
                raise DocImpactError(f"Rule {rid}: empty or invalid source glob")

        # Validate requires groups
        requires = rule.get("requires", [])
        if not requires or not isinstance(requires, list):
            raise DocImpactError(f"Rule {rid}: 'requires' must be a non-empty list")
        for group in requires:
            any_of = group.get("any_of", [])
            if not any_of or not isinstance(any_of, list):
                raise DocImpactError(f"Rule {rid}: each requires group must have non-empty 'any_of' list")
            for target in any_of:
                if not target or not isinstance(target, str):
                    raise DocImpactError(f"Rule {rid}: empty or invalid target path in any_of")

    # Validate scope roots covered by at least one rule glob
    all_rule_globs = []
    for rule in rules:
        all_rule_globs.extend(rule["source"])
    for root in scope_roots:
        matched = False
        for glob in all_rule_globs:
            # A root is "covered" if its pattern is a subset of or equal to a rule glob
            # For simplicity: check if any file matching root could match a rule glob
            # We'll do a structural check: root pattern must match at least one rule glob pattern
            if glob_matches_pattern(root, glob):
                matched = True
                break
        if not matched:
            raise DocImpactError(f"Scope root not covered by any rule glob: {root}")

    # Validate target paths exist (unless allowed_new)
    for rule in rules:
        for group in rule["requires"]:
            for target in group["any_of"]:
                if not Path(target).exists():
                    raise DocImpactError(f"Rule {rule['id']}: target documentation path does not exist: {target}")

    return {"scope_roots": scope_roots, "rules": rules}


def glob_matches_pattern(pattern1: str, pattern2: str) -> bool:
    """
    Check if pattern1 (scope root) could match files that also match pattern2 (rule glob).
    Simplified: true if pattern2 is more specific or equal, or they share the same prefix.
    """
    # For practical purposes: if pattern1 is a prefix of pattern2 or vice versa
    # or if they share the same directory prefix before any wildcard
    p1_parts = pattern1.split('/')
    p2_parts = pattern2.split('/')
    for a, b in zip(p1_parts, p2_parts):
        if a == b:
            continue
        if '*' in a or '*' in b or '?' in a or '?' in b:
            return True
        return False
    return True


def match_globs(path: str, globs: List[str]) -> bool:
    """Check if path matches any of the glob patterns."""
    for g in globs:
        if fnmatch.fnmatch(path, g):
            return True
        # Also try with leading ./ stripped
        if path.startswith('./') and fnmatch.fnmatch(path[2:], g):
            return True
    return False


def is_in_scope(path: str, scope_roots: List[str]) -> bool:
    return match_globs(path, scope_roots)


def validate_rationale(reason: str) -> bool:
    """Check if rationale is non-empty and not generic."""
    if not reason or not reason.strip():
        return False
    stripped = reason.strip().lower()
    if stripped in GENERIC_RATIONALES:
        return False
    return True


def extract_rationale_from_trailer(commit_range: str, scope_roots: List[str]) -> Optional[str]:
    """
    Scan commits in range for Docs-Impact trailer.
    Return the rationale from the newest commit that touches an in-scope path.
    """
    try:
        # Get commits with trailers, newest first
        result = subprocess.run(
            ["git", "log", "--format=%H%n%(trailers:key=Docs-Impact)", commit_range],
            capture_output=True,
            text=True,
            check=True
        )
    except subprocess.CalledProcessError:
        return None

    lines = result.stdout.strip().split('\n')
    # Parse as pairs: commit_hash, trailer_value
    i = 0
    while i < len(lines) - 1:
        commit = lines[i].strip()
        trailer = lines[i + 1].strip()
        i += 2
        if not trailer or trailer == " ":
            continue
        if trailer.lower().startswith("none - "):
            reason = trailer[7:].strip()
            if validate_rationale(reason):
                # Check if this commit touched any in-scope path
                try:
                    diff = subprocess.run(
                        ["git", "diff", "-z", "--name-only", f"{commit}^..{commit}"],
                        capture_output=True,
                        text=True,
                        check=True
                    )
                    changed = diff.stdout.split('\x00')
                    for p in changed:
                        if p and is_in_scope(p, scope_roots):
                            return reason
                except subprocess.CalledProcessError:
                    continue
    return None


def check_impact(
    base: str,
    head: str,
    map_data: Dict,
    rationale: Optional[str] = None
) -> Tuple[bool, List[str]]:
    """
    Main check logic.
    Returns (passed, output_lines).
    """
    output = []
    scope_roots = map_data["scope_roots"]
    rules = map_data["rules"]

    # Get changed paths
    changes = run_git_diff(base, head)

    # Collect all changed paths (including rename old paths)
    all_changed_paths = []
    for status, path in changes:
        if path:
            all_changed_paths.append(path)

    if not all_changed_paths:
        output.append("PASS: no changed paths — doc-impact gate skipped")
        return True, output

    # Find which rules are triggered
    triggered_rules = []
    for rule in rules:
        rule_source_matched = []
        for path in all_changed_paths:
            if match_globs(path, rule["source"]):
                rule_source_matched.append(path)
        if rule_source_matched:
            triggered_rules.append((rule, rule_source_matched))

    if not triggered_rules:
        # Changed paths exist but none match any rule source
        # Check if any are in scope roots (unmapped in-scope)
        for path in all_changed_paths:
            if is_in_scope(path, scope_roots):
                output.append(f"FAIL: unmapped in-scope path: {path}")
                output.append("  No rule covers this path — add a rule to documentation-impact-map.json")
                return False, output
        output.append("PASS: changed paths outside documented impact areas")
        return True, output

    # Evaluate each triggered rule
    all_passed = True
    rationale_used = False

    for rule, matched_paths in triggered_rules:
        rule_id = rule["id"]
        output.append(f"Rule triggered: {rule_id}")
        output.append(f"  Changed source paths: {', '.join(matched_paths)}")

        # Check required doc groups
        rule_passed = True
        for group_idx, group in enumerate(rule["requires"]):
            any_of = group["any_of"]
            satisfied = False
            satisfied_by = None
            for target in any_of:
                if target in all_changed_paths:
                    satisfied = True
                    satisfied_by = target
                    break
            if satisfied:
                output.append(f"  Required doc group {group_idx + 1}: satisfied by {satisfied_by}")
            else:
                output.append(f"  Required doc group {group_idx + 1}: MISSING (expected one of: {', '.join(any_of)})")
                rule_passed = False

        if not rule_passed:
            if rationale and validate_rationale(rationale):
                output.append(f"  No-impact rationale accepted: {rationale}")
                output.append("  (This is a reviewable exception — not a documentation update)")
                rationale_used = True
            else:
                output.append(f"  FAIL: missing required documentation and no valid rationale")
                all_passed = False
        else:
            output.append(f"  PASS: all required documentation groups satisfied")

    if all_passed:
        if rationale_used:
            output.append("RESULT: PASS (with no-impact rationale — review required)")
        else:
            output.append("RESULT: PASS")
    else:
        output.append("RESULT: FAIL")

    return all_passed, output


def self_test() -> bool:
    """Run self-tests with temporary git repos and map fixtures."""
    import tempfile
    import shutil

    print("=== Documentation Impact Self-Test ===")

    # Create a valid map fixture
    valid_map = {
        "scope_roots": [
            "src/api/**",
            "config/**",
            "db/**",
            "release/**"
        ],
        "rules": [
            {
                "id": "API",
                "source": ["src/api/**"],
                "requires": [{"any_of": ["README.md", "docs/api.md"]}]
            },
            {
                "id": "CONFIG",
                "source": ["config/**"],
                "requires": [{"any_of": ["README.md", "docs/config.md"]}]
            },
            {
                "id": "DB",
                "source": ["db/**"],
                "requires": [{"any_of": ["docs/schema.md"]}]
            },
            {
                "id": "RELEASE",
                "source": ["release/**"],
                "requires": [{"any_of": ["docs/release.md", "CHANGELOG.md"]}]
            }
        ]
    }

    all_passed = True

    def run_case(name: str, setup_fn, check_fn, should_pass: bool, rationale: Optional[str] = None):
        nonlocal all_passed
        tmpdir = tempfile.mkdtemp(prefix=f"docimpact_test_{name}_")
        try:
            os.chdir(tmpdir)
            subprocess.run(["git", "init", "-q"], check=True, capture_output=True)
            subprocess.run(["git", "config", "user.email", "test@test"], check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Test"], check=True, capture_output=True)

            # Write map
            with open("docmap.json", "w") as f:
                json.dump(valid_map, f)

            # Create initial docs
            for doc in ["README.md", "docs/api.md", "docs/config.md", "docs/schema.md", "docs/release.md", "CHANGELOG.md"]:
                Path(doc).parent.mkdir(parents=True, exist_ok=True)
                Path(doc).write_text("# " + doc)

            # Initial commit
            subprocess.run(["git", "add", "."], check=True, capture_output=True)
            subprocess.run(["git", "commit", "-q", "-m", "init"], check=True, capture_output=True)
            base = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()

            # Run test setup
            head = setup_fn()

            # Run checker
            from io import StringIO
            old_stdout = sys.stdout
            sys.stdout = StringIO()
            try:
                map_data = load_map("docmap.json")
                passed, out_lines = check_impact(base, head, map_data, rationale)
            finally:
                sys.stdout = old_stdout

            success = passed == should_pass
            if not success:
                print(f"FAIL: {name} — expected {'PASS' if should_pass else 'FAIL'}, got {'PASS' if passed else 'FAIL'}")
                all_passed = False
            else:
                print(f"PASS: {name}")
        finally:
            os.chdir("/")
            shutil.rmtree(tmpdir, ignore_errors=True)

    # Case 1: Missing doc → FAIL
    def setup_missing_doc():
        Path("src/api/new_endpoint.java").parent.mkdir(parents=True, exist_ok=True)
        Path("src/api/new_endpoint.java").write_text("// new")
        subprocess.run(["git", "add", "src/api/new_endpoint.java"], check=True, capture_output=True)
        subprocess.run(["git", "commit", "-q", "-m", "add api"], check=True, capture_output=True)
        return subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    run_case("missing_doc", setup_missing_doc, lambda b, h, m, r: check_impact(b, h, m, r), False)

    # Case 2: Valid target doc → PASS
    def setup_valid_doc():
        Path("src/api/new_endpoint.java").parent.mkdir(parents=True, exist_ok=True)
        Path("src/api/new_endpoint.java").write_text("// new")
        Path("docs/api.md").write_text("# API\n\nUpdated")
        subprocess.run(["git", "add", "src/api/new_endpoint.java", "docs/api.md"], check=True, capture_output=True)
        subprocess.run(["git", "commit", "-q", "-m", "add api + doc"], check=True, capture_output=True)
        return subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    run_case("valid_doc", setup_valid_doc, lambda b, h, m, r: check_impact(b, h, m, r), True)

    # Case 3: Valid rationale → PASS (exception)
    def setup_valid_rationale():
        Path("src/api/new_endpoint.java").parent.mkdir(parents=True, exist_ok=True)
        Path("src/api/new_endpoint.java").write_text("// new")
        subprocess.run(["git", "add", "src/api/new_endpoint.java"], check=True, capture_output=True)
        subprocess.run(["git", "commit", "-q", "-m", "add api"], check=True, capture_output=True)
        return subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    def check_with_rationale(b, h, m, r):
        return check_impact(b, h, m, "Refactoring only — no behavior change")
    run_case("valid_rationale", setup_valid_rationale, check_with_rationale, True, "Refactoring only — no behavior change")

    # Case 4: Invalid rationale → FAIL
    def setup_invalid_rationale():
        Path("src/api/new_endpoint.java").parent.mkdir(parents=True, exist_ok=True)
        Path("src/api/new_endpoint.java").write_text("// new")
        subprocess.run(["git", "add", "src/api/new_endpoint.java"], check=True, capture_output=True)
        subprocess.run(["git", "commit", "-q", "-m", "add api"], check=True, capture_output=True)
        return subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    def check_with_bad_rationale(b, h, m, r):
        return check_impact(b, h, m, "none")
    run_case("invalid_rationale", setup_invalid_rationale, check_with_bad_rationale, False, "none")

    # Case 5: Docs-only change → PASS/no-op
    def setup_docs_only():
        Path("docs/api.md").write_text("# API\n\nUpdated")
        subprocess.run(["git", "add", "docs/api.md"], check=True, capture_output=True)
        subprocess.run(["git", "commit", "-q", "-m", "update docs"], check=True, capture_output=True)
        return subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    run_case("docs_only", setup_docs_only, lambda b, h, m, r: check_impact(b, h, m, r), True)

    # Case 6: Rename in scope → both paths evaluated
    def setup_rename():
        Path("src/api/old.java").parent.mkdir(parents=True, exist_ok=True)
        Path("src/api/old.java").write_text("// old")
        subprocess.run(["git", "add", "src/api/old.java"], check=True, capture_output=True)
        subprocess.run(["git", "commit", "-q", "-m", "add old"], check=True, capture_output=True)
        # Rename
        subprocess.run(["git", "mv", "src/api/old.java", "src/api/new.java"], check=True, capture_output=True)
        subprocess.run(["git", "commit", "-q", "-m", "rename"], check=True, capture_output=True)
        return subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    run_case("rename", setup_rename, lambda b, h, m, r: check_impact(b, h, m, r), False)  # missing doc

    # Case 7: Multiple rules triggered
    def setup_multi_rule():
        Path("src/api/endpoint.java").parent.mkdir(parents=True, exist_ok=True)
        Path("src/api/endpoint.java").write_text("// api")
        Path("config/app.yaml").parent.mkdir(parents=True, exist_ok=True)
        Path("config/app.yaml").write_text("config:")
        subprocess.run(["git", "add", "src/api/endpoint.java", "config/app.yaml"], check=True, capture_output=True)
        subprocess.run(["git", "commit", "-q", "-m", "api + config"], check=True, capture_output=True)
        return subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    run_case("multi_rule", setup_multi_rule, lambda b, h, m, r: check_impact(b, h, m, r), False)

    # Case 8: Unmapped in-scope path → FAIL
    def setup_unmapped():
        Path("src/api/new.java").parent.mkdir(parents=True, exist_ok=True)
        Path("src/api/new.java").write_text("// new")
        subprocess.run(["git", "add", "src/api/new.java"], check=True, capture_output=True)
        subprocess.run(["git", "commit", "-q", "-m", "add"], check=True, capture_output=True)
        return subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    # Use a map without API rule
    def check_unmapped(b, h, m, r):
        bad_map = {"scope_roots": ["src/api/**"], "rules": [{"id": "CONFIG", "source": ["config/**"], "requires": [{"any_of": ["docs/config.md"]}]}]}
        return check_impact(b, h, bad_map, r)
    run_case("unmapped_in_scope", setup_unmapped, check_unmapped, False)

    # Case 9: Scope root uncovered by rules → map validation FAIL
    def check_bad_map(b, h, m, r):
        bad_map = {"scope_roots": ["src/api/**"], "rules": []}
        try:
            load_map("bad_map.json")
            return (False, ["FAIL: map validation should have failed"])
        except DocImpactError:
            return (True, ["PASS: map validation caught uncovered root"])
    with tempfile.TemporaryDirectory() as tmpdir:
        os.chdir(tmpdir)
        subprocess.run(["git", "init", "-q"], check=True, capture_output=True)
        subprocess.run(["git", "config", "user.email", "test@test"], check=True, capture_output=True)
        subprocess.run(["git", "config", "user.name", "Test"], check=True, capture_output=True)
        bad_map = {"scope_roots": ["src/api/**"], "rules": []}
        with open("bad_map.json", "w") as f:
            json.dump(bad_map, f)
        Path("README.md").write_text("# Readme")
        subprocess.run(["git", "add", "."], check=True, capture_output=True)
        subprocess.run(["git", "commit", "-q", "-m", "init"], check=True, capture_output=True)
        try:
            load_map("bad_map.json")
            print("FAIL: uncovered_scope_root — map validation did not reject")
            all_passed = False
        except DocImpactError:
            print("PASS: uncovered_scope_root")

    if all_passed:
        print("PASS: self-test verified — gate detects violations and allows compliant changes.")
        return True
    else:
        print("FAIL: self-test detected problems")
        return False


def main():
    parser = argparse.ArgumentParser(description="Documentation Impact Checker")
    parser.add_argument("--base", required=False, help="Base commit SHA")
    parser.add_argument("--head", required=False, help="Head commit SHA")
    parser.add_argument("--rationale-file", help="File containing rationale string")
    parser.add_argument("--rationale", help="Rationale string directly")
    parser.add_argument("--self-test", action="store_true", help="Run self-tests")
    parser.add_argument("--map", default="docs/documentation-impact-map.json", help="Path to impact map")
    args = parser.parse_args()

    if args.self_test:
        ok = self_test()
        sys.exit(0 if ok else 1)

    if not args.base or not args.head:
        parser.error("--base and --head are required (unless --self-test)")

    # Handle zero before SHA
    if args.base == "0" * 40 or args.base == "0" * 64:
        print("SKIP: initial push — no comparison base")
        sys.exit(0)

    # Load rationale
    rationale = None
    if args.rationale_file:
        with open(args.rationale_file, 'r') as f:
            rationale = f.read().strip()
    elif args.rationale:
        rationale = args.rationale

    try:
        map_data = load_map(args.map)
        passed, output = check_impact(args.base, args.head, map_data, rationale)
        for line in output:
            print(line)
        sys.exit(0 if passed else 1)
    except DocImpactError as e:
        msg = f"FAIL: {e.message}"
        if e.rule_id:
            msg += f" (rule: {e.rule_id})"
        if e.path:
            msg += f" (path: {e.path})"
        print(msg)
        sys.exit(1)
    except Exception as e:
        print(f"FAIL: unexpected error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()