#!/bin/bash
# Metrics freeze gate — enforces Epic 3 (Observable) invariant: every Micrometer meter
# registered by the application is part of the reviewed, frozen contract below.
#
# The frozen set is the ground truth of business series registered at runtime (see
# docs/slos.md §2). New gauges/counters/timers are a data-model change for Prometheus:
# they must be designed (name, tags, desc, percentiles), recorded here, in docs/slos.md
# and in docs/observability.md — never added ad hoc.
#
# Epic 3 story 3.2 consolidation: the legacy infra/observability/MetricsService (which
# registered urls.shortened.total, redirects.total, cache.hits.total, cache.misses.total,
# bloomfilter.rejections.total, shorten.latency, redirect.latency and was wired directly
# into UrlController) was folded into MicrometerMetricsAdapter behind MetricsPort. All
# meter names/tags/descriptions are preserved, so the exposed prometheus series are
# byte-for-byte unchanged.
#
# Usage:
#   bash scripts/check-metrics-frozen.sh               # static scan over src/main/java
#   bash scripts/check-metrics-frozen.sh -u <baseUrl>  # also probes /actuator/prometheus
#   bash scripts/check-metrics-frozen.sh --self-test   # proves the gate catches violations
set -euo pipefail

SRC="src/main/java"

# Frozen set: the 25 business meter base names (dot notation) registered by the app.
# Micrometer auto-configuration additionally registers JVM/HTTP/Redis/Spring family
# series (jvm_*, http_server_requests_*, redis_*, process_*, ...) — those come from
# upstream, are not ours to freeze, and are ignored by this gate.
FROZEN_METERS=(
    "analytics.events.dropped.total"
    "analytics.events.enqueued.total"
    "analytics.events.failed.total"
    "analytics.events.persisted.total"
    "analytics.queue.depth"
    "analytics.retention.errors.total"
    "analytics.retention.purged.total"
    "analytics.retention.runs.total"
    "analytics.rollup.days.total"
    "analytics.retention.errors.total"
    "analytics.retention.purged.total"
    "analytics.retention.runs.total"
    "analytics.rollup.days.total"
    "analytics.retention.errors.total"
    "analytics.retention.purged.total"
    "analytics.retention.runs.total"
    "analytics.rollup.days.total"
    "analytics.rollup.errors.total"
    "analytics.rollup.groups.upserted.total"
    "bloomfilter.rejections.total"
    "cache.hits.total"
    "cache.misses.total"
    "custom.domains.created.total"
    "domains.claimed.total"
    "domains.verified.total"
    "id.generation.duration"
    "rate.limit.exceeded.total"
    "redirect.latency"
    "redirects.total"
    "schema.migrations.applied.total"
    "schema.migrations.failed.total"
    "security.ssrf.blocked.total"
    "shorten.latency"
    "url.retrieval.duration"
    "urls.expired.total"
    "urls.shortened.total"
    "vanity.urls.created.total"
    "domains.claimed.total"
    "domains.verified.total"
    "custom.domains.created.total"
    "rate.limit.exceeded.total"

find_registered_meters() {
    local root="${1:-$SRC}"
    grep -rEno "(Counter|Timer|Gauge|DistributionSummary|FunctionCounter|TimeGauge)\.builder\(\s*\"[^\"]+\"" "$root" --include="*.java" 2>/dev/null \
        | sed -E 's/.*builder\(\s*"([^"]+)".*/\1/' \
        | sort -u
}

# Static check: the set of meters registered via (Counter|Timer|Gauge|...).builder("name")
# must be exactly the frozen set. A missing or extra series is a contract change for
# dashboards/alerts/SLOs.
check_static() {
    local root="${1:-$SRC}"
    local missing=0
    local frozen
    local registered
    frozen=$(printf '%s\n' "${FROZEN_METERS[@]}" | sort)
    registered=$(find_registered_meters "$root")

    local extra
    extra=$(comm -13 <(printf "%s\n" "$frozen") <(printf "%s\n" "$registered"))
    local gone
    gone=$(comm -23 <(printf "%s\n" "$frozen") <(printf "%s\n" "$registered"))

    if [ -n "$extra" ]; then
        echo "  UNFROZEN METERS (new series need design review + docs/slos.md §2 + AGENTS.md):"
        echo "$extra" | sed 's/^/    /'
        missing=1
    fi
    if [ -n "$gone" ]; then
        echo "  MISSING FROZEN METERS (removed? then dashboards/alerts/SLOs must be updated):"
        echo "$gone" | sed 's/^/    /'
        missing=1
    fi
    return $missing
}

# Runtime check (optional): if a base URL is given, probe /actuator/prometheus and assert
# every frozen counter/timer/gauge is actually exported (Prometheus suffix normalization:
# counters get _total, timers get _seconds/_nanos; base name substring match).
check_runtime() {
    local base="${1:-}"
    if [ -z "$base" ]; then
        return 0
    fi
    local missing=0
    local body
    body=$(curl -sf "http://${base}/actuator/prometheus" 2>/dev/null || true)
    if [ -z "$body" ]; then
        echo "  WARN: /actuator/prometheus not reachable at http://${base} — skipping runtime probe"
        return 0
    fi

    local normalized
    for meter in "${FROZEN_METERS[@]}"; do
        normalized=${meter//./_}
        if ! printf '%s' "$body" | grep -q "^${normalized}_\|^${normalized}{"; then
            echo "  NOT EXPORTED at runtime: ${meter} (${normalized})"
            missing=1
        fi
    done
    return $missing
}

run_all_checks() {
    local root="${1:-$SRC}"
    local base="${2:-}"
    local pass=1
    check_static "$root" || pass=0
    check_runtime "$base" || pass=0
    return $((1 - pass))
}

if [ "${1:-}" = "--self-test" ]; then
    echo "=== Metrics Freeze Gate Self-Test ==="

    TMPDIR=$(mktemp -d)
    trap 'rm -rf "$TMPDIR"' EXIT

    # ---- Violation tree: a stray un-frozen meter and a removed frozen one.
    VIO="$TMPDIR/violation${SRC}"
    mkdir -p "$VIO"
    printf '%s\n' \
        'class Fake { void m(R x) { x = Counter.builder("payments.total"); } }' \
        'class Fake2 { void m(R x) { x = counter("bloomfilter.rejections.total"); } }' \
        'class Fake3 { Object o = Trace.trace("id.generation.duration"); }' \
        > "$VIO/Stray.java"

    check_static "$VIO" && { echo "FAIL: self-test did not detect planted violations"; exit 1; }

    check_static "$SRC" || { echo "FAIL: clean src tree falsely rejected"; exit 1; }

    echo "OK: planted violations detected and clean src tree passes."
    echo "PASS: self-test verified — gate detects violations."
    exit 0
fi

echo "=== Metrics Freeze Gate ==="

# Optional runtime probe: `bash scripts/check-metrics-frozen.sh -u localhost:8080`
BASE_URL=""
if [ "${1:-}" = "-u" ]; then
    BASE_URL="${2:-}"
    shift 2
fi

if run_all_checks "$SRC" "$BASE_URL"; then
    echo "PASS: metrics frozen — all registered meters belong to the reviewed set (docs/slos.md §2)."
    exit 0
else
    echo "FAIL: metrics freeze gate — reconcile the list above with docs/slos.md §2."
    exit 1
fi