#!/bin/bash
# E2E Seed Script for URL Shortener Service (Epic 14 Request 2).
#
# Seeds a disposable backend with a demo user (admin optional) and a set of sample
# links via the public API. Intended for frontend E2E / staging against the compose
# stack in docker-compose.e2e.yaml:
#
#   docker compose -f docker-compose.e2e.yaml up --build
#   bash scripts/seed-e2e.sh                 # demo user + links
#   APP_ADMIN_EMAILS=admin@example.com \
#     bash scripts/seed-e2e.sh               # also make that e-mail an ADMIN user
#
# The script is idempotent-ish: it registers users only when the e-mail is not yet
# present (a 400 on /register is treated as already-seeded), and shortens the sample
# URLs with fixed vanity aliases, so re-runs do not create duplicates.
#
# Environment:
#   API_BASE            Base URL (default: http://localhost:8080)
#   APP_ADMIN_EMAILS    Comma-separated emails granted ADMIN (ADR 0011); the first one
#                       (if set) is seeded as a demo admin user.
#   DEMO_PASSWORD       Password for seeded users (default: "password123"; min 6)
#
# Requirements: curl, python3 (for JSON parsing), a running backend.

set -euo pipefail

API_BASE="${API_BASE:-http://localhost:8080}"
DEMO_PASSWORD="${DEMO_PASSWORD:-password123}"

USER_EMAIL="demo@example.com"
ADMIN_EMAILS="${APP_ADMIN_EMAILS:-}"

say() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
ok() { printf '\033[0;32m    %s\033[0m\n' "$*"; }

# register a user; tolerate "already exists" (400 with EMAIL_IN_USE error code)
register() {
  local name="$1" email="$2"
  local resp code
  resp="$(
    curl -sS -o /tmp/seed-body.json -w '%{http_code}' \
      -H 'Content-Type: application/json' \
      -d "{\"name\":\"$name\",\"email\":\"$email\",\"password\":\"$DEMO_PASSWORD\"}" \
      "$API_BASE/api/v1/auth/register" || true
  )"
  code="${resp}"
  if [ "$code" = "200" ]; then
    ok "registered $email"
    python3 -c 'import json;print(json.load(open("/tmp/seed-body.json"))["token"])'
  elif [ "$code" = "400" ] && grep -q "EMAIL_IN_USE\|already" /tmp/seed-body.json 2>/dev/null; then
    ok "$email already registered (reusing)"
    # fall back to login for the token (cookie mode also works)
    code=$(curl -sS -o /tmp/seed-body.json -w '%{http_code}' \
      -H 'Content-Type: application/json' \
      -d "{\"email\":\"$email\",\"password\":\"$DEMO_PASSWORD\"}" \
      "$API_BASE/api/v1/auth/login" || true)
    [ "$code" = "200" ] || { echo "ERROR: login for $email failed (HTTP $code)"; exit 1; }
    python3 -c 'import json;print(json.load(open("/tmp/seed-body.json"))["token"])'
  else
    echo "ERROR: seeding $email failed (HTTP $code): $(cat /tmp/seed-body.json 2>/dev/null || true)" >&2
    exit 1
  fi
}

shorten() {
  local alias="$1" url="$2" ttl="${3:-}" token="$4"
  local body="{\"originalUrl\":\"$url\",\"customAlias\":\"$alias\""
  [ -n "$ttl" ] && body="$body,\"ttlSeconds\":$ttl"
  body="$body}"
  local code
  code=$(curl -sS -o /tmp/seed-body.json -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $token" \
    -d "$body" \
    "$API_BASE/api/v1/urls" || true)
  if [ "$code" = "200" ]; then
    ok "$(python3 -c 'import json;print(json.load(open("/tmp/seed-body.json"))["shortUrl"])' 2>/dev/null || echo "$alias")"
  elif [ "$code" = "409" ]; then
    ok "$alias already exists (skipped)"
  else
    echo "ERROR: shortening $alias failed (HTTP $code): $(cat /tmp/seed-body.json 2>/dev/null || true)" >&2
    exit 1
  fi
}

main() {
  curl -fsS "$API_BASE/actuator/health/liveness" >/dev/null \
    || { echo "ERROR: backend not reachable at $API_BASE (start docker-compose.e2e.yaml first)" >&2; exit 1; }

  say "Seeding demo user"
  USER_TOKEN="$(register "E2E Demo User" "$USER_EMAIL")"

  if [ -n "$ADMIN_EMAILS" ]; then
    ADMIN_EMAIL="${ADMIN_EMAILS%%,*}"
    say "Seeding admin user ($ADMIN_EMAIL)"
    ADMIN_TOKEN="$(register "E2E Admin" "$ADMIN_EMAIL")"
  fi

  say "Seeding sample links (demo user)"
  shorten "docs"    "https://example.com/documentation"   ""   "$USER_TOKEN"
  shorten "blog"    "https://example.com/blog/launch"     ""   "$USER_TOKEN"
  shorten "expires" "https://example.com/limited-time"    3600 "$USER_TOKEN"

  say "Seed complete."
  ok "demo user:   $USER_EMAIL / $DEMO_PASSWORD"
  [ -n "$ADMIN_EMAILS" ] && ok "admin user:  $ADMIN_EMAIL / $DEMO_PASSWORD"
  ok "sample links: /docs /blog /expires (shortUrl uses APP_PUBLIC_BASE_URL when set)"
}

main "$@"