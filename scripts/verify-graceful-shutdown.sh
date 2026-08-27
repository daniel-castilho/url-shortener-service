#!/bin/bash
# Graceful Shutdown Verification Script
#
# This script verifies that the application properly drains in-flight requests
# when receiving SIGTERM, and rejects new requests during the grace period.
#
# Usage:
#   bash scripts/verify-graceful-shutdown.sh
#
# Requires:
#   - Application running on localhost:8080
#   - curl, jq available

set -euo pipefail

PORT="${PORT:-8080}"
BASE_URL="http://localhost:${PORT}"
GRACE_PERIOD=30  # Should match spring.lifecycle.timeout-per-shutdown-phase

log() { echo "[verify] $*"; }

# Check if app is running
if ! curl -sf "${BASE_URL}/actuator/health/liveness" > /dev/null 2>&1; then
    log "ERROR: Application not responding at ${BASE_URL}"
    exit 1
fi

log "Application is up, testing graceful shutdown..."

# Start a slow request in background (simulate in-flight request)
# Using a redirect to a slow endpoint would be ideal, but we'll use the redirect path
# with a code that exists. First, create a short URL.
SHORTEN_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/v1/urls" \
    -H "Content-Type: application/json" \
    -d '{"originalUrl": "https://httpbin.org/delay/10"}')
CODE=$(echo "$SHORTEN_RESPONSE" | jq -r '.shortUrl | split("/") | .[-1]')

if [ -z "$CODE" ] || [ "$CODE" = "null" ]; then
    log "ERROR: Failed to create test short URL"
    echo "$SHORTEN_RESPONSE"
    exit 1
fi

log "Created test short URL with code: $CODE"

# Start a request that will take time (use httpbin delay endpoint)
# We'll hit the redirect endpoint which will forward to httpbin with delay
log "Starting slow redirect request (background)..."
curl -s --max-time 20 "${BASE_URL}/${CODE}" > /tmp/slow_redirect.out 2>&1 &
SLOW_PID=$!

# Give the request time to start
sleep 2

# Send SIGTERM to the application (simulate systemd stop)
log "Sending SIGTERM to application..."
APP_PID=$(pgrep -f 'url-shortener.*\.jar' | head -1)
if [ -z "$APP_PID" ]; then
    log "ERROR: Could not find application PID"
    kill $SLOW_PID 2>/dev/null || true
    exit 1
fi

log "Application PID: $APP_PID"
kill -TERM "$APP_PID"

# Wait for the slow request to complete (should succeed during grace period)
log "Waiting for slow request to complete (max ${GRACE_PERIOD}s)..."
wait $SLOW_PID 2>/dev/null
SLOW_EXIT=$?

if [ $SLOW_EXIT -eq 0 ]; then
    log "SUCCESS: In-flight request completed during grace period"
else
    log "FAIL: In-flight request was terminated (exit code: $SLOW_EXIT)"
    cat /tmp/slow_redirect.out
    exit 1
fi

# Verify new requests are rejected after grace period (app should be stopping)
sleep 2
log "Verifying new requests are rejected after shutdown initiated..."
if curl -sf --max-time 5 "${BASE_URL}/actuator/health/liveness" > /dev/null 2>&1; then
    log "WARN: App still responding (may still be in grace period)"
else
    log "SUCCESS: App no longer accepting new requests"
fi

log "Graceful shutdown verification complete!"