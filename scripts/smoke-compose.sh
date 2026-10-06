#!/usr/bin/env bash
#
# Docker Compose production smoke (CD — deploy.yml step + operator one-liner).
#
# Contract: docs/release-runbook.md §Docker Compose deployment.
# Layers on top of scripts/smoke.sh (the 8 business legs, run against the
# loopback app WITH the production Host mirror, since the redirect path rejects
# the default loopback Host):
#   leg 9  host-mirror negative — a valid code answers 404 without the Host
#          override and the app log records `Rejecting id=… on unbound host`
#          (the HTTP body is the generic 404 by design — anti-enumeration)
#   leg 10 Prometheus — the app scrape target is `up` (catches credential,
#          network and group_add regressions that a container "healthy" hides)
#   leg 11 image identity (optional) — the running app container really serves
#          the image the deploy intended to release (GHCR ref)
#
# Usage: scripts/smoke-compose.sh [--base URL] [--host NAME] [--expect-image REF]
#   --base         default http://127.0.0.1:8080 (loopback publish in compose)
#   --host         default www.tyny.ca (must match APP_DOMAIN_DEFAULT_HOST)
#   --expect-image e.g. ghcr.io/owner/repo:0.16.0 — asserted via docker inspect
#   PROM_URL       Prometheus base, default http://127.0.0.1:9090
#   APP_CONTAINER  default urlshortener-app

set -euo pipefail

BASE="http://127.0.0.1:8080"
HOST="www.tyny.ca"
EXPECT_IMAGE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="${2:?--base needs a value}"; shift 2 ;;
    --host) HOST="${2:?--host needs a value}"; shift 2 ;;
    --expect-image) EXPECT_IMAGE="${2:?--expect-image needs a value}"; shift 2 ;;
    *) echo "SMOKE-COMPOSE FAIL: unknown argument $1" >&2; exit 1 ;;
  esac
done

PROM_URL="${PROM_URL:-http://127.0.0.1:9090}"
APP_CONTAINER="${APP_CONTAINER:-urlshortener-app}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

fail() { echo "SMOKE-COMPOSE FAIL: $*" >&2; exit 1; }
note() { echo "SMOKE-COMPOSE $(date +%T): $*"; }

UNIQ="$(date +%s%N)"

# ------------------------------------------------- legs 1-8: business contract
note "legs 1-8: business smoke (base=$BASE, host=$HOST)"
bash "$SCRIPT_DIR/smoke.sh" "$BASE" "$BASE" "$HOST" \
  || fail "business smoke (scripts/smoke.sh) failed"

# ------------------------------------------------- leg 9: host-mirror negative
note "leg 9/11 host-mirror negative (valid code, default Host -> 404 + log line)"
BODY=$(mktemp)
CODE=$(curl -s -o "$BODY" -w '%{http_code}' -m 10 \
    -H "Content-Type: application/json" \
    -d "{\"originalUrl\": \"https://example.com/mirror-negative/${UNIQ}\"}" \
    "$BASE/api/v1/urls" 2>/dev/null || true)
[ "$CODE" = "200" ] || { cat "$BODY" >&2; fail "leg 9: shorten with Host expected 200, got $CODE"; }
ID=$(grep -o '"id":"[^"]*"' "$BODY" | head -1 | cut -d'"' -f4)
[ -n "$ID" ] || fail "leg 9: no id in shorten response"
# Same code WITHOUT the Host override: the app must refuse to resolve it.
CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$BASE/$ID" 2>/dev/null || true)
[ "$CODE" = "404" ] || fail "leg 9: unbound-host probe expected 404, got $CODE"
docker logs "$APP_CONTAINER" --since 3m 2>&1 \
  | grep -q "Rejecting id=$ID on unbound host" \
  || fail "leg 9: app log missing 'Rejecting id=$ID on unbound host' (mirror decision not taken)"
rm -f "$BODY"

# ------------------------------------------------- leg 10: Prometheus target
note "leg 10/11 Prometheus scrape target is up ($PROM_URL)"
PROM_BODY=$(curl -s -m 10 "$PROM_URL/api/v1/targets?state=active" 2>/dev/null || true)
[ -n "$PROM_BODY" ] || fail "leg 10: no answer from $PROM_URL (is Prometheus up?)"
printf '%s' "$PROM_BODY" | python3 -c '
import json, sys
targets = json.load(sys.stdin)["data"]["activeTargets"]
app = [t for t in targets if t["labels"].get("job") == "url-shortener"]
if not app:
    sys.exit("no url-shortener scrape job configured")
for t in app:
    if t["health"] != "up":
        addr = t["discoveredLabels"].get("__address__")
        sys.exit("target %s is %s: %s" % (addr, t["health"], t.get("lastError", "")))
' || fail "leg 10: app scrape target not up (see python error above)"

# ------------------------------------------------- leg 11: image identity
if [ -n "$EXPECT_IMAGE" ]; then
  note "leg 11/11 running image == expected ($EXPECT_IMAGE)"
  RUNNING=$(docker inspect --format '{{.Config.Image}}' "$APP_CONTAINER" 2>/dev/null || true)
  [ -n "$RUNNING" ] || fail "leg 11: container $APP_CONTAINER not found"
  [ "$RUNNING" = "$EXPECT_IMAGE" ] \
    || fail "leg 11: running image '$RUNNING' != expected '$EXPECT_IMAGE'"
else
  note "leg 11/11 skipped (--expect-image not given)"
fi

note "SMOKE-COMPOSE PASS"
