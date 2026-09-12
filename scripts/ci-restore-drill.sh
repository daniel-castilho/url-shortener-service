#!/usr/bin/env bash
#
# URL Shortener restore drill (Epic 8 story 8.5 — "the drill is the deliverable").
# CI body of the `restore-drill` job (release.yml), executable locally.
#
# Boots an ISOLATED stack (own compose project `urlshortener-drill`, ports 18xxx — the
# Epic 5/6/7 isolation pattern: never touches the dev stack), seeds codes through the
# API, takes a manifest backup, DROPS short_urls, restores with --verify, and asserts:
#   - seeds created BEFORE the backup answer 302 (they are in the dump)
#   - seeds created AFTER the backup answer 404 (RPO = last backup, EP7 drill semantics)
#   - wall-clock RTO of backup -> restore -> verify vs RTO_BUDGET_S (default 300)
# The manifests are the deliverable (uploaded as the workflow artifact).
#
# Usage: bash scripts/ci-restore-drill.sh
# Environment: RTO_BUDGET_S (default 300), JAR (default target/url-shortener-service-0.0.1-SNAPSHOT.jar)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

JAR="${JAR:-$(ls -t "$REPO_DIR"/target/url-shortener-service-*.jar 2>/dev/null | head -1 || echo "$REPO_DIR/target/url-shortener-service-0.0.1-SNAPSHOT.jar")}"
RTO_BUDGET_S="${RTO_BUDGET_S:-300}"

DRILL_PROJECT=urlshortener-drill
DRILL_DIR=$(mktemp -d /tmp/urlshortener-drill.XXXXXX)
DRILL_MONGO_PORT=18017
DRILL_REDIS_PORT=16379
APP_PORT=18080
APP_LOG="$DRILL_DIR/app.log"

note() { echo "DRILL $(date +%T): $*"; }
fail() { echo "DRILL FAIL: $*" >&2; exit 1; }

cleanup() {
    note "cleanup: stopping app + drill compose (down -v destroys only the drill's volume)"
    pkill -f "server.port=$APP_PORT" 2>/dev/null || true
    docker compose -p "$DRILL_PROJECT" -f "$DRILL_DIR/compose.yaml" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

[ -f "$JAR" ] || fail "jar not found: $JAR (build it first)"

# ---------------------------------------------------------------- preflight: the ports must be OURS
# An earlier drill silently "passed" its liveness wait against a LEFTOVER nginx front from a
# manual cutover exercise (the drill app had died on the busy port); its seeds then landed in
# the wrong Mongo and mongodump exited 0 with an empty dump. A drill must fail closed if any
# of its ports is already taken.
for p in "$APP_PORT" "$DRILL_MONGO_PORT" "$DRILL_REDIS_PORT"; do
    if ss -H -tln | awk '{print $4}' | grep -qE ":$p\$"; then
        fail "port $p is already in use — kill the process holding it (ss -tlnp | grep :$p) and re-run; a drill must never share ports with leftovers"
    fi
done

# ---------------------------------------------------------------- isolated stack (compose, 18xxx ports)
note "booting isolated mongo (:$DRILL_MONGO_PORT) + redis (:$DRILL_REDIS_PORT) — project $DRILL_PROJECT"
cat > "$DRILL_DIR/compose.yaml" <<EOF
services:
  mongo:
    image: mongo:6.0
    command: ["--quiet"]
    ports:
      - "$DRILL_MONGO_PORT:27017"
    tmpfs:
      - /data/db:size=512m
  redis:
    image: redis:8-alpine
    ports:
      - "$DRILL_REDIS_PORT:6379"
EOF
docker compose -p "$DRILL_PROJECT" -f "$DRILL_DIR/compose.yaml" up -d >/dev/null
MONGODB_URI="mongodb://localhost:$DRILL_MONGO_PORT/url_shortener"
export MONGODB_URI
for i in $(seq 1 30); do
    if docker compose -p "$DRILL_PROJECT" -f "$DRILL_DIR/compose.yaml" exec -T mongo mongosh --quiet --eval "db.runCommand({ping:1}).ok" 2>/dev/null | grep -q 1; then
        break
    fi
    sleep 2
done

# ---------------------------------------------------------------- app (relaxed rate limits)
note "booting app on :$APP_PORT (jar: $(basename "$JAR"))"
MONGODB_URI="$MONGODB_URI" \
REDIS_HOST=localhost REDIS_PORT=$DRILL_REDIS_PORT \
RATE_LIMITER_LIMIT=1000000 RATE_LIMITER_REDIRECT_LIMIT=1000000 \
SERVER_PORT=$APP_PORT \
setsid java -jar "$JAR" > "$APP_LOG" 2>&1 < /dev/null &
APP_PID=$!

for i in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 3 "http://localhost:$APP_PORT/actuator/health/liveness" 2>/dev/null || true)
    [ "$code" = "200" ] && break
    sleep 2
done
[ "$code" = "200" ] || fail "app did not come up (see $APP_LOG)"

BASE="http://localhost:$APP_PORT"

# ---------------------------------------------------------------- seed 20 codes + 2 post-backup
note "seeding 20 codes via API"
PRE_SEEDS=()
for i in $(seq 1 20); do
    body=$(curl -s -m 10 -H "Content-Type: application/json" \
        -d "{\"originalUrl\": \"https://example.com/drill/seed-$i\"}" "$BASE/api/v1/urls")
    id=$(echo "$body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    [ -n "$id" ] || fail "seed $i failed: $body"
    PRE_SEEDS+=("$id")
done
note "pre-backup seeds: ${PRE_SEEDS[*]}"

# PROVE the seeds landed in the DRILL's mongo (not in a neighbouring stack via some leftover
# proxy): count documents there before the backup — the backup below dumps this instance.
SEED_COUNT=$(docker compose -p "$DRILL_PROJECT" -f "$DRILL_DIR/compose.yaml" exec -T mongo \
    mongosh url_shortener --quiet --eval 'db.short_urls.countDocuments({})' 2>/dev/null || echo -1)
if [ "$SEED_COUNT" -lt "${#PRE_SEEDS[@]}" ]; then
    fail "seeds are NOT in the drill mongo (found $SEED_COUNT docs, expected >= ${#PRE_SEEDS[@]}) — the app is writing somewhere else; check MONGODB_URI/port collisions"
fi
note "seed proof: $SEED_COUNT short_urls in the drill mongo"

# ---------------------------------------------------------------- RTO clock starts at the backup
RTO_START=$(date +%s)

note "backup (manifest contract)"
BACKUP_OUT="$DRILL_DIR/backup"
MONGODB_URI="$MONGODB_URI" bash "$SCRIPT_DIR/backup-mongodb.sh" "$BACKUP_OUT"
DUMP_DIR=$(ls -dt "$BACKUP_OUT"/20* | head -1)
note "backup at $DUMP_DIR"

note "creating 2 POST-backup codes (they must 404 after restore — RPO proof)"
POST_SEEDS=()
for i in 1 2; do
    body=$(curl -s -m 10 -H "Content-Type: application/json" \
        -d "{\"originalUrl\": \"https://example.com/drill/post-$i\"}" "$BASE/api/v1/urls")
    id=$(echo "$body" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    [ -n "$id" ] || fail "post seed $i failed: $body"
    POST_SEEDS+=("$id")
done
note "post-backup seeds: ${POST_SEEDS[*]}"

note "simulating loss: dropping short_urls"
# inside the compose exec the container-internal 27017 is the endpoint (the published 18017
# is host-side only) — mongosh with no URI targets localhost:27017 inside the container
docker compose -p "$DRILL_PROJECT" -f "$DRILL_DIR/compose.yaml" exec -T mongo mongosh url_shortener --quiet --eval 'db.short_urls.drop()'

note "restore with --verify (manifest contract)"
MONGODB_URI="$MONGODB_URI" bash "$SCRIPT_DIR/restore-mongodb.sh" "$DUMP_DIR" --verify

RTO_SECONDS=$(( $(date +%s) - RTO_START ))

# ---------------------------------------------------------------- assertions
FAILS=0
for id in "${PRE_SEEDS[@]}"; do
    c=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$BASE/$id" || true)
    [ "$c" = "302" ] || { note "ASSERT FAIL: pre-backup seed $id answered $c (expected 302)"; FAILS=$((FAILS+1)); }
done
note "pre-backup seeds: ${#PRE_SEEDS[@]}/$(( ${#PRE_SEEDS[@]} )) answered 302"

for id in "${POST_SEEDS[@]}"; do
    c=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$BASE/$id" || true)
    [ "$c" = "404" ] || { note "ASSERT FAIL: post-backup seed $id answered $c (expected 404)"; FAILS=$((FAILS+1)); }
done
note "post-backup seeds: $(( ${#POST_SEEDS[@]} - FAILS ))/${#POST_SEEDS[@]} answered 404 (RPO proof)"

[ "$FAILS" -eq 0 ] || fail "$FAILS assertion(s) failed"

if [ "$RTO_SECONDS" -gt "$RTO_BUDGET_S" ]; then
    fail "RTO ${RTO_SECONDS}s exceeds budget RTO_BUDGET_S=${RTO_BUDGET_S}s"
fi
note "RTO (backup -> restore -> verify): ${RTO_SECONDS}s <= ${RTO_BUDGET_S}s budget"

# manifests are the deliverable
echo "DRILL_MANIFEST_DIR=$DUMP_DIR"
note "RESTORE DRILL PASS (RTO ${RTO_SECONDS}s; pre=302, post=404; restore --verify green)"
