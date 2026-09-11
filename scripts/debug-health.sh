#!/bin/bash
# Debug health triage script (Epic 3, story 3.5).
#
# Queries the health probes and /actuator/prometheus of a running instance and prints
# the recommended action for the symptoms it detects, referencing
# docs/observability.md §Diagnóstico and docs/slos.md §Response runbook.
#
# Usage:
#   bash scripts/debug-health.sh [base_url]          # default http://localhost:8080
#   DEBUG_HEALTH_TOKEN=<jwt-or-basic> bash scripts/debug-health.sh
#
# Exit code 0 even when health is degraded (this is a triage aid, not a gate).
# /actuator/prometheus is role-gated today (AGENTS.md debt item 26); when it answers
# 401/403 the script prints the diagnosis from the public probes and tells the operator
# how to get the metric side (scrape directly via Prometheus or run as an operator once
# operator identity is wired).
set -u

BASE="${1:-http://localhost:8080}"
TOKEN="${DEBUG_HEALTH_TOKEN:-}"

curl_get() { # url -> prints body, hides network noise
  local url="$1"
  if [ -n "$TOKEN" ]; then
    curl -sf -H "Authorization: Bearer $TOKEN" "$url"
  else
    curl -sf "$url"
  fi
}

prom_body=""
if curl_get "$BASE/actuator/health/liveness" > /tmp/debug-health-liveness.$$ 2>/dev/null; then
  liveness=$(cat /tmp/debug-health-liveness.$$)
else
  liveness="unreachable"
fi

readiness="unreachable"
if curl_get "$BASE/actuator/health/readiness" > /tmp/debug-health-readiness.$$ 2>/dev/null; then
  readiness=$(cat /tmp/debug-health-readiness.$$)
fi

echo "== URL shortener — debug health =="
echo "Base: $BASE (debug-health.sh, Epic 3 story 3.5)"
echo "liveness : $liveness"
echo "readiness: $readiness"

case "$readiness" in
  *'"status":"UP"'*) echo "backends : UP (Mongo + Redis reachable)" ;;
  *DOWN*|*unreachable*) echo "backends : DOWN or unreachable -> row 1 in §Diagnóstico below" ;;
  *) echo "backends : unknown readiness body ($readiness)" ;;
esac

# --- Metrics probe (best effort) ------------------------------------------------
if prom_body=$(curl_get "$BASE/actuator/prometheus"); then
  # 5xx / total request counts from the http.server.requests histogram (spring renders _count for
  # each tagged series). Sum launches -> crude but correct signal for triage.
  total=$(printf '%s' "$prom_body" \
    | awk '/^http_server_requests_seconds_total\{/ { n=split($0,a," "); g=a[n]+0 }
           /^http_server_requests_seconds_bucket\{/{next}
           /^http_server_requests_seconds_sum\{/{next}
           /^http_server_requests_seconds_count\{/{ n=split($0,a," "); total+=a[n]+0 }
           END { print total }')
  err5=$(printf '%s' "$prom_body" \
    | awk '/^http_server_requests_seconds_count\{.*status="5[0-9][0-9]".*/ { n=split($0,a," "); sum+=a[n]+0 }
           END { print sum }')
  cache_hits=$(printf '%s' "$prom_body" \
    | awk '/^cache_hits_total/ { n=split($0,a," "); s+=a[n]+0 } END { print s }')
  cache_misses=$(printf '%s' "$prom_body" \
    | awk '/^cache_misses_total/ { n=split($0,a," "); s+=a[n]+0 } END { print s }')
  queue=$(printf '%s' "$prom_body" \
    | awk '/^analytics_queue_depth/ { n=split($0,a," "); print a[n]+0; exit }')

  echo "metrics  : prometheus reachable (role-gated scrape)"
  printf "error5xx : %s of %s requests\n" "${err5:-0}" "${total:-0}"
  if [ -n "${err5:-}" ] && [ "${total:-0}" -gt 0 ]; then
    ratio=$(awk -v e="$err5" -v t="$total" 'BEGIN { printf "%.4f", e/t }')
    echo "5xx ratio: $ratio  ${ratio:-x}" | awk '{ if ($2+0 > 0.001*14.4) print "ratio above fast-burn threshold -> runbook (docs/slos.md §Fast burn)" }'
  fi
  printf "cache    : hits=%s misses=%s\n" "${cache_hits:-0}" "${cache_misses:-0}"
  printf "queue    : analytics.queue.depth=%s\n" "${queue:-n/a}"
else
  cat <<'EOF'
metrics  : /actuator/prometheus needs an operator credential (AGENTS.md debt 26).
           Options: (a) run this script from the Prometheus host and query
           http://<prom>/api/v1/query directly; (b) once operator identity is wired,
           export DEBUG_HEALTH_TOKEN and the scrape is fetched here.
EOF
fi

# --- Symptom -> action table (docs/observability.md §Diagnóstico) ----------------
cat <<'EOF'

§Diagnóstico (symptom -> check -> action; detail in docs/observability.md)
 1. Readiness DOWN / Mongo or Redis unreachable
      check : /actuator/health/readiness body + `docker compose ps` + service logs
      action: start the backend stack (docker-compose up -d), verify network/replicaset,
              then re-check readiness. Readiness UP requires BOTH Mongo and Redis.
 2. Liveness DOWN / app unresponsive
      check : service status (`systemctl status url-shortener`) + application.log tail
      action: restart the unit; if it recurs, run scripts/verify-graceful-shutdown.sh and
              inspect previous shutdown logs (JDK crash, OOM, blocked migration).
 3. Sustained 5xx / fast-burn alert
      check : error5xx above + SLOAvailabilityFastBurn alert status in Alertmanager
      action: docs/slos.md §Response runbook — Fast burn (critical) row.
 4. analytics.queue.depth growing (>100s of events)
      check : queue line above + click_events collection insert stats
      action: consumer (ClickBatchWorker) is failing/backed up; check its logs; the queue
              is bounded (XADD MAXLEN) and fail-open, so watch for drops.
 5. Cache hit ratio collapsing
      check : cache lines above (hits/(hits+misses))
      action: verify Redis reachable + L1/bloom reset from RedisUrlCache; a single DB hit
              per redirect is by design (Rule 5), ratios must stay high.
 6. Redirect latency p99 > 200ms
      check : url_retrieval_duration_seconds + redirect_latency_seconds p99 panels
      action: MongoDB/Redis latency + network; correlate with k6 baseline
              (docs/load-test-baseline.md) and the latency panel.

Recommended action for the top symptom above:
  - readiness not UP      -> follow row 1
  - 5xx ratio above burn  -> follow row 3
  - queue depth climbing  -> follow row 4
  - otherwise             -> cross-check rows 5-6 against the prometheus panels.
EOF
rm -f /tmp/debug-health-liveness.$$ /tmp/debug-health-readiness.$$
exit 0