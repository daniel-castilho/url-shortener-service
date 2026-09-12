#!/usr/bin/env bash
#
# URL Shortener runtime smoke probe (Epic 8 story 8.4 — ONE probe, TWO consumers:
# deploy.sh after each canary bump, and the CI runtime-smoke job).
# Contract: docs/release-engineering.md §2 + the HTTP contracts read from the code
# (ShortenResponse{id, shortUrl}; X-Request-Id echoed by RequestCorrelationFilter;
# GET /{id} 302/404/410; HEAD mirrors GET — ReadPathIT#headMirrorsGetOnRedirectPath, EP7).
#
# Usage: scripts/smoke.sh <base-url>
#   base-url  the front the probe should hit, e.g. http://localhost:8080 (direct)
#            or the nginx front. Legs exit non-zero NAMING the failed leg.

set -euo pipefail

BASE="${1:?usage: smoke.sh <base-url> [seed-origin]}"
# origin used to build the unique destination URL (default: the base itself)
ORIGIN="${2:-$BASE}"

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }
note() { echo "SMOKE $(date +%T): $*"; }

# unique destination per run (no dedup by design — distinct codes each call)
UNIQ="$(date +%s%N)"
DEST="https://example.com/smoke/${UNIQ}"

# ---------------------------------------------------------------- leg 1: liveness
note "leg 1/8 liveness"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$BASE/actuator/health/liveness" 2>/dev/null || true)
[ "$CODE" = "200" ] || fail "leg 1: liveness expected 200, got $CODE"

# ---------------------------------------------------------------- leg 2: readiness
note "leg 2/8 readiness"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$BASE/actuator/health/readiness" 2>/dev/null || true)
[ "$CODE" = "200" ] || fail "leg 2: readiness expected 200, got $CODE"

# ---------------------------------------------------------------- leg 3: info
note "leg 3/8 info"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$BASE/actuator/info" 2>/dev/null || true)
[ "$CODE" = "200" ] || fail "leg 3: info expected 200, got $CODE"

# ---------------------------------------------------------------- leg 4: shorten
note "leg 4/8 shorten (200 + id + shortUrl + X-Request-Id)"
BODY_FILE=$(mktemp)
HDR_FILE=$(mktemp)
CODE=$(curl -s -o "$BODY_FILE" -D "$HDR_FILE" -w '%{http_code}' -m 10 \
    -H "Content-Type: application/json" \
    -d "{\"originalUrl\": \"$DEST\"}" \
    "$BASE/api/v1/urls" 2>/dev/null || true)
[ "$CODE" = "200" ] || { cat "$BODY_FILE" >&2; fail "leg 4: shorten expected 200, got $CODE"; }
ID=$(grep -o '"id":"[^"]*"' "$BODY_FILE" | head -1 | cut -d'"' -f4)
SHORT=$(grep -o '"shortUrl":"[^"]*"' "$BODY_FILE" | head -1 | cut -d'"' -f4)
[ -n "$ID" ] || fail "leg 4: no id in shorten response: $(cat "$BODY_FILE")"
[ -n "$SHORT" ] || fail "leg 4: no shortUrl in shorten response: $(cat "$BODY_FILE")"
grep -qi '^x-request-id:' "$HDR_FILE" || fail "leg 4: X-Request-Id header absent on shorten"

# ---------------------------------------------------------------- leg 5: redirect 302 + Location
note "leg 5/8 redirect (302 + Location == originalUrl)"
LOC_FILE=$(mktemp)
CODE=$(curl -s -o /dev/null -D "$LOC_FILE" -w '%{http_code}' -m 10 --max-redirs 0 "$BASE/$ID" 2>/dev/null || true)
[ "$CODE" = "302" ] || fail "leg 5: redirect expected 302, got $CODE"
LOCATION=$(grep -i '^location:' "$LOC_FILE" | head -1 | tr -d '\r' | cut -d' ' -f2-)
[ "$LOCATION" = "$DEST" ] || fail "leg 5: Location '$LOCATION' != original '$DEST'"

# ---------------------------------------------------------------- leg 6: HEAD mirrors GET
note "leg 6/8 HEAD redirect (302 — HEAD mirrors GET, EP7 fix)"
CODE=$(curl -s -o /dev/null -I -w '%{http_code}' -m 10 "$BASE/$ID" 2>/dev/null || true)
[ "$CODE" = "302" ] || fail "leg 6: HEAD redirect expected 302, got $CODE"

# ---------------------------------------------------------------- leg 7: unknown code 404
note "leg 7/8 unknown code (zzzzzzz -> 404)"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "$BASE/zzzzzzz" 2>/dev/null || true)
[ "$CODE" = "404" ] || fail "leg 7: unknown code expected 404, got $CODE"

# ---------------------------------------------------------------- leg 8: TTL expiry -> 410
note "leg 8/8 expiry (ttlSeconds:1 -> poll 410 in <=15s)"
BODY2=$(mktemp)
CODE=$(curl -s -o "$BODY2" -w '%{http_code}' -m 10 \
    -H "Content-Type: application/json" \
    -d "{\"originalUrl\": \"https://example.com/smoke-expired/${UNIQ}\", \"ttlSeconds\": 1}" \
    "$BASE/api/v1/urls" 2>/dev/null || true)
[ "$CODE" = "200" ] || { cat "$BODY2" >&2; fail "leg 8: shorten (ttl) expected 200, got $CODE"; }
ID2=$(grep -o '"id":"[^"]*"' "$BODY2" | head -1 | cut -d'"' -f4)
[ -n "$ID2" ] || fail "leg 8: no id in ttl shorten response: $(cat "$BODY2")"
DEADLINE=$(( $(date +%s) + 15 ))
EXPIRED=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    C=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$BASE/$ID2" 2>/dev/null 2>/dev/null || true)
    if [ "$C" = "410" ]; then EXPIRED="yes"; break; fi
    sleep 1
done
[ "$EXPIRED" = "yes" ] || fail "leg 8: $ID2 not 410 within 15s (last code: $C)"
rm -f "$BODY_FILE" "$HDR_FILE" "$LOC_FILE" "$BODY2"

note "SMOKE PASS"
