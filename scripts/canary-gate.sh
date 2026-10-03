#!/bin/bash
# Canary Metrics Gate
#
# Evaluates whether a blue/green canary stage is healthy per ADR 0012
# (metrics-gated blue-green canary). Reads metric thresholds from the deploy-host
# Prometheus (loopback 127.0.0.1:9090) instead of application health probes alone.
#
# Signals evaluated over the trailing evaluation window (all instant queries):
#   1. up{job="url-shortener-<color>"} == 1              exactly one series, fresh sample
#   2. count_over_time(up{...}[<window>]) >= 2           minimum scrapes in the window
#   3. max(timestamp(http_server_requests_seconds_count)) <= <freshness> (source freshness)
#   4. 5xx request ratio over window   <  <error-ratio-max>
#   5. le="0.2" latency-bucket ratio over window  >= <latency-ok-min>
#   6. request volume over window  >= <min-requests>
#
# Contract:
#   exit 0  PASS  - every signal satisfied (stage may proceed to next weight).
#   exit 1  FAIL  - a MEASURED threshold breach (5xx ratio, latency ratio). The
#                   stage is definitively unhealthy; abort immediately (fail-closed).
#   exit 2  NO-EVIDENCE - indeterminate: query error/timeout, empty/missing/
#                   multiple series, NaN/+Inf/-Inf, stale or up!=1, or request
#                   volume below the minimum after the bounded evaluation budget.
#                   A stage that cannot produce evidence NEVER passes.
#
# Retry policy (bounded): indeterminate results retry up to --max-evals with
# --evals-spacing seconds between evaluations, then exit 2 (terminal action).
# Measured breaches fail immediately without retrying.
#
# Multiple/unexpected series: `up` must be EXACTLY one series; aggregated scalars
# (ratio/volume/source-age) are expected to be exactly one series each and are
# rejected otherwise. No cross-color leakage is possible because every query
# scopes on the color-specific job selector.
#
# Usage:
#   scripts/canary-gate.sh --color blue [options]
#
# Required:
#   --color blue|green        the canary color being verified
#
# Options:
#   --prometheus-url URL      Prometheus query endpoint (default $PROMETHEUS_URL or
#                             http://127.0.0.1:9090). Loopback-only in production.
#   --window SECONDS          evaluation window (default: 90)
#   --freshness SECONDS       max allowed age of an up/source sample (default: 45)
#   --min-requests N          minimum request volume in the window (default: 300;
#                             provisional pending staging rehearsal)
#   --error-ratio-max R       maximum acceptable 5xx ratio (default: 0.001)
#   --latency-ok-min R        minimum le="0.2" bucket ratio (default: 0.99)
#   --max-evals N             bounded retries for indeterminate results (default: 3)
#   --evals-spacing SECONDS   delay between evaluations (default: --window)
#   --help                    print this help and exit (exit 0)
#   --self-test               run the offline mock-API test suite (no network) and exit
#
# Requires: curl, jq (same prerequisites as scripts/verify-graceful-shutdown.sh).
# No credentials are ever passed on the command line or logged; the scrape
# credentials live in the Prometheus scrape config (password_file), never here.
#
# Environment:
#   PROMETHEUS_URL  default value for --prometheus-url
#   CURL_TIMEOUT    per-request timeout in seconds (default: 5)
#
# Exit codes follow the contract above. --self-test exits 0 on success, 1 on failure.

set -uo pipefail

PROM_BIN="${PROM_BIN:-curl}"
CURL_TIMEOUT="${CURL_TIMEOUT:-5}"

log() { printf '[canary-gate] %s\n' "$*" >&2; }

die() {
  log "ERROR: $*"
  exit 1
}

usage() {
  sed -n '2,60p' "$0" | grep -E '^#( |$)' | sed 's/^# \{0,1\}//'
}

# --- numeric helpers ---------------------------------------------------------

NUM_RE='^[+-]?([0-9]+\.?[0-9]*|\.[0-9]+)([eE][+-]?[0-9]+)?$'

is_num() {
  case "$1" in
    '' | NaN | nan | +Inf | -Inf | Inf | infinity | -infinity) return 1 ;;
    *) [[ "$1" =~ $NUM_RE ]] ;;
  esac
}

lt() { awk -v a="$1" -v b="$2" 'BEGIN{exit !(a<b)}'; }
ge() { awk -v a="$1" -v b="$2" 'BEGIN{exit !(a>=b)}'; }

# --- Prometheus HTTP --------------------------------------------------------

# Prometheus instant query. Writes the JSON body to $2 and echoes the HTTP status
# to stdout. On curl failure echoes 000. Never logs the URL (has no credentials).
http_query() {
  local body_file="$1" expr="$2"
  local code
  code=$("$PROM_BIN" -sS --max-time "$CURL_TIMEOUT" -o "$body_file" \
    -w '%{http_code}' -G "$PROM_URL/api/v1/query" --data-urlencode "query=$expr" \
    2>/dev/null) || code=000
  [[ "$code" =~ ^[0-9]+$ ]] || code=000
  printf '%s\n' "$code"
}

# Returns "ERR" | "LEN<TAB>TS<TAB>VAL" for an instant vector result.
read_vec() {
  local f="$1" out
  out=$(jq -r '
    if .status == "success" and .data.resultType == "vector" then
      .data.result as $r |
      ( [ ($r | length),
          (if (($r | length) > 0) then $r[0].value[0] else "" end),
          (if (($r | length) > 0) then $r[0].value[1] else "" end) ] | @tsv )
    else "ERR" end' "$f" 2>/dev/null) || out="ERR"
  printf '%s\n' "$out"
}

# --- single-signal checks ----------------------------------------------------

# Returns 0 (ok) or non-zero (indeterminate reason). The signal functions
# communicate status through the shared variables EVAL_STATE / EVAL_MSG.
eval_state="INDET"
eval_msg=""

set_indet() { [[ "$eval_state" == "FAIL" ]] || { eval_state="INDET"; eval_msg="$1"; } }
set_fail()  { eval_state="FAIL"; eval_msg="$1"; }

# signal 1+3: up == 1, exactly one series, sample younger than freshness.
# Prometheus sample timestamps in an instant vector are unix SECONDS (float, e.g.
# 1790965152.964). Compare in seconds with float-safe arithmetic (awk), never bash
# $((...)) integer subtraction on a fractional value.
check_up() {
  local body code vec len ts val now age
  code=$(http_query "$tmp/up.json" "up{job=\"url-shortener-$COLOR\"}")
  if [[ "$code" != "200" ]]; then
    set_indet "up query HTTP $code"; return 1
  fi
  vec=$(read_vec "$tmp/up.json")
  [[ "$vec" == "ERR" ]] && { set_indet "up malformed response"; return 1; }
  IFS=$'\t' read -r len ts val <<<"$vec"
  [[ "$len" == "1" ]] || { set_indet "up must be exactly 1 series, got ${len:-0}"; return 1; }
  is_num "$val" || { set_indet "up value not numeric"; return 1; }
  is_num "$ts"  || { set_indet "up sample timestamp missing"; return 1; }
  now=$(date +%s)
  age=$(awk -v n="$now" -v t="$ts" 'BEGIN{if (t<=0) {print "err"} else {printf "%.6f", n - t}}' 2>/dev/null)
  # Allow up to 2s clock skew (sample timestamp slightly in future relative to now).
  # Freshness: -2s <= age < FRESHNESS.
  if [[ "$age" == "err" ]] || ! lt "$age" "$FRESHNESS" || ! lt "-2" "$age"; then
    set_indet "up sample older than freshness ${FRESHNESS}s (age=${age}s)"; return 1
  fi
  if [[ "$val" != "1" ]]; then
    [[ "$val" == "0" ]] && { set_indet "up{...} == 0 (target down or not yet scraped)"; return 1; }
    set_indet "up value != 1"; return 1
  fi
  return 0
}

# signal 2: count_over_time(up) >= 2 in the window.
check_scrape_count() {
  local body code vec len ts val
  code=$(http_query "$tmp/count.json" "sum_over_time(up{job=\"url-shortener-$COLOR\"}[${WINDOW}s])")
  [[ "$code" == "200" ]] || { set_indet "scrape-count query HTTP $code"; return 1; }
  vec=$(read_vec "$tmp/count.json")
  [[ "$vec" == "ERR" ]] && { set_indet "scrape-count malformed response"; return 1; }
  IFS=$'\t' read -r len ts val <<<"$vec"
  [[ "$len" == "1" ]] || { set_indet "scrape-count must be exactly 1 series, got ${len:-0}"; return 1; }
  is_num "$val" || { set_indet "scrape-count not numeric"; return 1; }
  # sum_over_time(up[W]) = count of successful scrapes (up==1) in the window;
  # need at least 2 to confirm both colors are reachable.
  if lt "$val" "2"; then set_indet "insufficient successful scrapes in window (${val})"; return 1; fi
  return 0
}

# signal 3: source samples (the request counters) are fresh, not just `up`.
check_source_freshness() {
  local body code vec len ts val now now_s age
  code=$(http_query "$tmp/source.json" \
    "max(timestamp(http_server_requests_seconds_count{job=\"url-shortener-$COLOR\"}))")
  [[ "$code" == "200" ]] || { set_indet "source-freshness query HTTP $code"; return 1; }
  vec=$(read_vec "$tmp/source.json")
  [[ "$vec" == "ERR" ]] && { set_indet "source-freshness malformed response"; return 1; }
  IFS=$'\t' read -r len ts val <<<"$vec"
  [[ "$len" == "1" ]] || { set_indet "source-freshness must be exactly 1 series, got ${len:-0}"; return 1; }
  is_num "$val" || { set_indet "source-freshness not numeric"; return 1; }
  # value[1] is max(timestamp(...)) in unix SECONDS (may carry a fractional part);
  # compare in seconds with float-safe arithmetic.
  now_s=$(date +%s)
  age=$(awk -v n="$now_s" -v v="$val" 'BEGIN{if (v<=0) {print "err"} else {printf "%.6f", n - v}}' 2>/dev/null)
  if [[ "$age" == "err" ]] || ! lt "$age" "$FRESHNESS" || ! lt "$((0))" "$age"; then
    set_indet "source samples older than freshness ${FRESHNESS}s"; return 1
  fi
  return 0
}

# signal 4: 5xx ratio below error-ratio-max.
check_error_ratio() {
  local body code vec len ts val
  code=$(http_query "$tmp/error.json" \
    "sum(rate(http_server_requests_seconds_count{job=\"url-shortener-$COLOR\",status=~\"5..\"}[${WINDOW}s])) / sum(rate(http_server_requests_seconds_count{job=\"url-shortener-$COLOR\"}[${WINDOW}s]))")
  [[ "$code" == "200" ]] || { set_indet "error-ratio query HTTP $code"; return 1; }
  vec=$(read_vec "$tmp/error.json")
  [[ "$vec" == "ERR" ]] && { set_indet "error-ratio malformed response"; return 1; }
  IFS=$'\t' read -r len ts val <<<"$vec"
  [[ "$len" == "1" ]] || { set_indet "error-ratio must be exactly 1 series, got ${len:-0}"; return 1; }
  is_num "$val" || { set_indet "error-ratio non-finite (${val})"; return 1; }
  if ge "$val" "$ERROR_RATIO_MAX"; then
    eval_state="FAIL"
    eval_msg="5xx ratio ${val} >= ${ERROR_RATIO_MAX}"
    return 1
  fi
  return 0
}

# signal 5: le="0.2" bucket ratio at/above latency-ok-min.
check_latency_ratio() {
  local body code vec len ts val
  code=$(http_query "$tmp/latency.json" \
    "sum(rate(http_server_requests_seconds_bucket{job=\"url-shortener-$COLOR\",le=\"0.2\"}[${WINDOW}s])) / sum(rate(http_server_requests_seconds_count{job=\"url-shortener-$COLOR\"}[${WINDOW}s]))")
  [[ "$code" == "200" ]] || { set_indet "latency-ratio query HTTP $code"; return 1; }
  vec=$(read_vec "$tmp/latency.json")
  [[ "$vec" == "ERR" ]] && { set_indet "latency-ratio malformed response"; return 1; }
  IFS=$'\t' read -r len ts val <<<"$vec"
  [[ "$len" == "1" ]] || { set_indet "latency-ratio must be exactly 1 series, got ${len:-0}"; return 1; }
  is_num "$val" || { set_indet "latency-ratio non-finite (${val})"; return 1; }
  if lt "$val" "$LATENCY_OK_MIN"; then
    eval_state="FAIL"
    eval_msg="le=0.2 latency ratio ${val} < ${LATENCY_OK_MIN}"
    return 1
  fi
  return 0
}

# signal 6: request volume >= min-requests (provisional threshold).
check_volume() {
  local body code vec len ts val
  code=$(http_query "$tmp/volume.json" \
    "sum(increase(http_server_requests_seconds_count{job=\"url-shortener-$COLOR\"}[${WINDOW}s]))")
  [[ "$code" == "200" ]] || { set_indet "volume query HTTP $code"; return 1; }
  vec=$(read_vec "$tmp/volume.json")
  [[ "$vec" == "ERR" ]] && { set_indet "volume malformed response"; return 1; }
  IFS=$'\t' read -r len ts val <<<"$vec"
  [[ "$len" == "1" ]] || { set_indet "volume must be exactly 1 series, got ${len:-0}"; return 1; }
  is_num "$val" || { set_indet "volume non-finite (${val})"; return 1; }
  if lt "$val" "$MIN_REQUESTS"; then
    set_indet "request volume ${val} < min ${MIN_REQUESTS} (bounded wait, then terminal)"
    return 1
  fi
  return 0
}

# --- single evaluation -------------------------------------------------------

evaluate() {
  eval_state="INDET"
  eval_msg=""
  local all_ok=1
  check_up            || all_ok=0
  check_scrape_count  || all_ok=0
  check_source_freshness || all_ok=0
  check_error_ratio   || all_ok=0
  check_latency_ratio || all_ok=0
  check_volume        || all_ok=0
  if [[ "$all_ok" -eq 1 && "$eval_state" == "INDET" ]]; then
    eval_state="PASS"
  fi
}

parse_args() {
  COLOR=""
  PROM_URL="${PROMETHEUS_URL:-http://127.0.0.1:9090}"
  WINDOW="${METRICS_WINDOW_SECONDS:-90}"
  FRESHNESS="${METRICS_FRESHNESS_SECONDS:-45}"
  MIN_REQUESTS="${METRICS_MIN_REQUESTS:-300}"
  ERROR_RATIO_MAX="${METRICS_ERROR_RATIO_MAX:-0.001}"
  LATENCY_OK_MIN="${METRICS_LATENCY_OK_MIN:-0.99}"
  MAX_EVALS="${METRICS_MAX_EVALS:-3}"
  EVALS_SPACING=""
  SELF_TEST=0

  if [[ $# -eq 0 ]]; then
    die "missing required --color blue|green (see --help)"
  fi

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --color)
        [[ $# -ge 2 ]] || die "--color requires an argument"
        COLOR="$2"; shift 2 ;;
      --prometheus-url)
        [[ $# -ge 2 ]] || die "--prometheus-url requires an argument"
        PROM_URL="$2"; shift 2 ;;
      --window)
        [[ $# -ge 2 ]] || die "--window requires an argument"
        WINDOW="$2"; shift 2 ;;
      --freshness)
        [[ $# -ge 2 ]] || die "--freshness requires an argument"
        FRESHNESS="$2"; shift 2 ;;
      --min-requests)
        [[ $# -ge 2 ]] || die "--min-requests requires an argument"
        MIN_REQUESTS="$2"; shift 2 ;;
      --error-ratio-max)
        [[ $# -ge 2 ]] || die "--error-ratio-max requires an argument"
        ERROR_RATIO_MAX="$2"; shift 2 ;;
      --latency-ok-min)
        [[ $# -ge 2 ]] || die "--latency-ok-min requires an argument"
        LATENCY_OK_MIN="$2"; shift 2 ;;
      --max-evals)
        [[ $# -ge 2 ]] || die "--max-evals requires an argument"
        MAX_EVALS="$2"; shift 2 ;;
      --evals-spacing)
        [[ $# -ge 2 ]] || die "--evals-spacing requires an argument"
        EVALS_SPACING="$2"; shift 2 ;;
      --self-test)
        SELF_TEST=1; shift ;;
      --help|-h)
        usage; exit 0 ;;
      *)
        die "unknown argument: $1 (see --help)" ;;
    esac
  done

  case "$COLOR" in
    blue|green) ;;
    *) die "--color must be 'blue' or 'green', got '${COLOR:-}'" ;;
  esac
  [[ "$WINDOW" =~ ^[0-9]+$ ]] && [[ "$WINDOW" -gt 0 ]] || die "--window must be a positive integer"
  [[ "$FRESHNESS" =~ ^[0-9]+$ ]] && [[ "$FRESHNESS" -gt 0 ]] || die "--freshness must be a positive integer"
  [[ "$MIN_REQUESTS" =~ ^[0-9]+$ ]] && [[ "$MIN_REQUESTS" -ge 0 ]] || die "--min-requests must be a non-negative integer"
  is_num "$ERROR_RATIO_MAX" || die "--error-ratio-max must be numeric"
  is_num "$LATENCY_OK_MIN" || die "--latency-ok-min must be numeric"
  [[ "$MAX_EVALS" =~ ^[0-9]+$ ]] && [[ "$MAX_EVALS" -ge 1 ]] || die "--max-evals must be a positive integer"
  if [[ -z "$EVALS_SPACING" ]]; then EVALS_SPACING="$WINDOW"; fi
  [[ "$EVALS_SPACING" =~ ^[0-9]+$ ]] && [[ "$EVALS_SPACING" -ge 0 ]] || die "--evals-spacing must be a non-negative integer"
}

# --- entrypoint --------------------------------------------------------------

main() {
  parse_args "$@"
  command -v jq >/dev/null 2>&1 || die "requires jq (see --help)"
  tmp=$(mktemp -d) || die "cannot create temp dir"
  trap 'rm -rf "$tmp"' EXIT

  log "color=${COLOR} prometheus=${PROM_URL} window=${WINDOW}s freshness=${FRESHNESS}s min-requests=${MIN_REQUESTS} error-ratio-max=${ERROR_RATIO_MAX} latency-ok-min=${LATENCY_OK_MIN} max-evals=${MAX_EVALS} evals-spacing=${EVALS_SPACING}s"

  local i result
  for ((i = 1; i <= MAX_EVALS; i++)); do
    log "evaluation ${i}/${MAX_EVALS}"
    evaluate
    if [[ "$eval_state" == "PASS" ]]; then
      log "PASS: all canary signals healthy for color=${COLOR}"
      exit 0
    fi
    if [[ "$eval_state" == "FAIL" ]]; then
      log "FAIL: measured threshold breach for color=${COLOR}: ${eval_msg}"
      exit 1
    fi
    log "INDETERMINATE: ${eval_msg}"
    if [[ $i -lt MAX_EVALS ]]; then
      log "retrying in ${EVALS_SPACING}s (bounded budget)"
      sleep "$EVALS_SPACING"
    fi
  done
  log "NO-EVIDENCE: budget of ${MAX_EVALS} evaluation(s) exhausted for color=${COLOR}; last reason: ${eval_msg}"
  exit 2
}

# --- self-test ------------------------------------------------------------------
# Offline mock: a stub `curl` on PATH serves canned synthetic Prometheus JSON keyed
# by scenario (CURL_STUB_SCENARIO) with freshly computed timestamps. No network, no
# credentials, no host state. Mirrors the real Prometheus wire format.

self_test() {
  ST_ROOT=$(mktemp -d) || die "cannot create temp dir"
  trap 'rm -rf "$ST_ROOT"' EXIT
  local root="$ST_ROOT"
  mkdir -p "$root/bin"

  local fail=0
  assert_eq() { # desc expected actual
    if [[ "$2" == "$3" ]]; then
      printf '  ok   %s\n' "$1"
    else
      printf '  FAIL %s (expected %s, got %s)\n' "$1" "$2" "$3" >&2
      fail=1
    fi
  }

  cat > "$root/bin/curl" <<'EOF'
#!/bin/bash
# Stub curl for canary-gate.sh --self-test. Serves synthetic Prometheus JSON,
# writes the HTTP code to stdout and the body to the -o target, like real curl.
SC="${CURL_STUB_SCENARIO:-pass}"
CODE="${CURL_STUB_HTTP_CODE:-200}"
CALLS="${CURL_STUB_CALLS:-/dev/null}"
echo x >> "$CALLS"
N=$(wc -l < "$CALLS" 2>/dev/null || echo 0)

TARGET=""
PREV=""
Q=""
for a in "$@"; do
  if [[ "$PREV" == "-o" ]]; then TARGET="$a"; fi
  PREV="$a"
  case "$a" in
    query=*) Q="${a#query=}" ;;
  esac
done

if [[ "$SC" == "retry" && "$N" -eq 1 ]]; then
  CODE="500"
fi

if [[ "$SC" == "p500" ]]; then
  CODE="500"
fi

if [[ "$SC" == "timeout" ]]; then
  exit 28
fi

CASE="up"
case "$Q" in
  *count_over_time*|*sum_over_time*) CASE="count" ;;
  *timestamp*) CASE="source" ;;
  *status*) CASE="error" ;;
  *le%3D*|*le=*) CASE="latency" ;;
  *increase*) CASE="volume" ;;
esac

NOW=$(date +%s)
JOB="url-shortener-blue"

# Self-test mock: emit timestamps the way a real Prometheus instant vector does —
# unix SECONDS with a fractional part (e.g. "1790965152.964"). This regresses the
# fresh/up + source checks against float-safe arithmetic (bash $((..)) cannot parse
# the fraction, and comparing ms to s would be wrong).
SEC_FRAC=".964"
nowf() { printf '%s%s' "$(( NOW - $1 ))" "$SEC_FRAC"; }

vec() { # ts_seconds_ago value
  printf '{"status":"success","data":{"resultType":"vector","result":[{"metric":{"__name__":"up","job":"%s","instance":"127.0.0.1:8080","color":"blue"},"value":["%s","%s"]}]}}' "$JOB" "$(nowf "$1")" "$2"
}
vec_empty() {
  printf '{"status":"success","data":{"resultType":"vector","result":[]}}'
}
vec_multi() {
  printf '{"status":"success","data":{"resultType":"vector","result":[{"metric":{"__name__":"up","job":"%s","instance":"127.0.0.1:8080","color":"blue"},"value":["%s","1"]},{"metric":{"__name__":"up","job":"%s","instance":"127.0.0.1:8081","color":"green"},"value":["%s","1"]}]}}' "$JOB" "$(nowf 2)" "$JOB" "$(nowf 2)"
}
scalar_one() { # seconds_ago value
  printf '{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":["%s","%s"]}]}}' "$(nowf "$1")" "$2"
}
scalar_none() {
  printf '{"status":"success","data":{"resultType":"vector","result":[]}}'
}

# Default PASS bodies per query.
case "$CASE" in
  up)      BODY=$(vec 2 "1") ;;
  count)   BODY=$(scalar_one 1 "6") ;;
  source)  BODY=$(scalar_one 3 "$(( NOW - 15 ))") ;;
  error)   BODY=$(scalar_one 1 "0.0005") ;;
  latency) BODY=$(scalar_one 1 "0.995") ;;
  volume)  BODY=$(scalar_one 1 "800") ;;
esac

# Per-scenario single-signal overrides (everything else passes).
case "$SC:$CASE" in
  stale-up:up)        BODY=$(vec 120 "1") ;; # 2 min old -> stale
  up-down:up)         BODY=$(vec 2 "0") ;;
  multi-up:up)        BODY=$(vec_multi) ;;
  stale-up:source)    BODY=$(scalar_one 300 "$(( NOW - 300 ))") ;; # source older than freshness
  5xx:error)          BODY=$(scalar_one 1 "0.01") ;;
  nan:error)          BODY=$(scalar_one 1 "NaN") ;;
  latency:latency)    BODY=$(scalar_one 1 "0.5") ;;
  low-volume:volume)  BODY=$(scalar_one 1 "30") ;;
  missing:*)          BODY=$(vec_empty) ;;
  aparams:*)          BODY=$(scalar_none) ;;
  # scrape-mix: 1 successful scrape + 1 failed scrape in the window -> sum_over_time(up)==1
  # (< 2). count_over_time would have counted BOTH samples (2) and wrongly passed;
  # sum_over_time only credits the successful one -> INDET -> exit 2.
  scrape-mix:count)   BODY=$(scalar_one 1 "1") ;;
  # fail-then-indet: measured 5xx breach (FAIL) followed by a non-numeric latency
  # sample (would-be INDET). The sticky-FAIL rule must keep the FAIL verdict -> exit 1.
  fail-then-indet:error)   BODY=$(scalar_one 1 "0.01") ;;
  fail-then-indet:latency) BODY=$(scalar_one 1 "NaN") ;;
esac

if [[ "$SC" == "bad-json" ]]; then
  BODY="not-json{{"
fi

printf '%s' "$BODY" > "$TARGET"
printf '%s\n' "$CODE"
EOF
  chmod +x "$root/bin/curl"

  local gate=()
  gate=(bash "$0")
  export PATH="$root/bin:$PATH"
  export CURL_STUB_CALLS="$root/calls"

  run_case() { # name scenario [expect] [extra...]
    local name="$1" sc="$2" expect="$3"; shift 3
    : > "$root/calls"
    CURL_STUB_SCENARIO="$sc" "${gate[@]}" --color blue --max-evals 1 --evals-spacing 0 \
      --window 90 --freshness 45 --min-requests 300 --error-ratio-max 0.001 \
      --latency-ok-min 0.99 "$@" >/dev/null 2>&1
    local rc=$?
    assert_eq "scenario $name -> exit $expect" "$expect" "$rc"
  }

  # CLI contract (no network: stub must NEVER be invoked)
  "${gate[@]}" --help >/dev/null 2>&1; assert_eq "--help exits 0" 0 $?
  : > "$root/calls"
  "${gate[@]}" --color purple >/dev/null 2>&1; assert_eq "invalid color rejected" 1 $?
  assert_eq "invalid color: no network" $(wc -l < "$root/calls") 0
  "${gate[@]}" --window abc --color blue >/dev/null 2>&1; assert_eq "non-numeric window rejected" 1 $?
  "${gate[@]}" --bogus --color blue >/dev/null 2>&1; assert_eq "unknown flag rejected" 1 $?
  "${gate[@]}" >/dev/null 2>&1; assert_eq "missing --color rejected" 1 $?
  assert_eq "rejections: no network" $(wc -l < "$root/calls") 0

  # Signal behaviors
  run_case "pass"             pass        0
  run_case "5xx breach"       5xx         1
  run_case "latency breach"   latency     1
  run_case "stale up"         stale-up    2
  run_case "target down"      up-down     2
  run_case "multi series up"  multi-up    2
  run_case "NaN ratio"        nan         2
  run_case "low volume"       low-volume  2
  run_case "missing series"   missing     2
  run_case "prometheus 500"   p500        2
  run_case "bad json"         bad-json    2
  run_case "curl failure"     timeout     2
  # sum_over_time semantics: 1 success + 1 fail in window -> only 1 credited (< 2).
  run_case "scrape mix"       scrape-mix  2
  # Sticky FAIL: measured 5xx breach cannot be downgraded to INDET by a later
  # non-numeric latency sample -> exit 1, not 2.
  run_case "sticky FAIL"      fail-then-indet 1

  # Bounded retry: first eval hits HTTP 500 on `up`, second eval passes -> exit 0.
  : > "$root/calls"
  CURL_STUB_SCENARIO=retry \
    "${gate[@]}" --color blue --max-evals 3 --evals-spacing 0 \
    --window 90 --freshness 45 --min-requests 300 --error-ratio-max 0.001 \
    --latency-ok-min 0.99 >/dev/null 2>&1
  assert_eq "bounded retry -> pass" 0 $?
  assert_eq "retry hit network >1 eval" 1 "$([[ $(wc -l < "$root/calls") -gt 6 ]] && echo 1 || echo 0)"

  # Measured breach must NOT retry: only one network round, immediate exit 1.
  : > "$root/calls"
  CURL_STUB_SCENARIO=5xx \
    "${gate[@]}" --color blue --max-evals 3 --evals-spacing 0 \
    --window 90 --freshness 45 --min-requests 300 --error-ratio-max 0.001 \
    --latency-ok-min 0.99 >/dev/null 2>&1
  assert_eq "measured breach: no retry" 1 $?
  assert_eq "measured breach: single eval" 1 "$([[ $(wc -l < "$root/calls") -le 6 ]] && echo 1 || echo 0)"

  if [[ "$fail" -eq 0 ]]; then
    printf 'canary-gate --self-test: ALL PASS\n'
    return 0
  fi
  printf 'canary-gate --self-test: FAILURES\n' >&2
  return 1
}

if [[ "${1:-}" == "--self-test" ]]; then
  self_test
  exit $?
fi

main "$@"