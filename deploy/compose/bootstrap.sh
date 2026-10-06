#!/usr/bin/env bash
#
# Host-side bootstrap for the Docker Compose deployment (idempotent).
#
#   bash bootstrap.sh
#
# Verifies .env exists (fail-closed with instructions when missing) and renders
# the git-ignored prometheus/operator_password from OPERATOR_PASSWORD. Secret
# values are never echoed. Run before the first `docker compose up` and after
# rotating OPERATOR_PASSWORD (then restart the stack's Prometheus: kill -s SIGHUP).
set -euo pipefail
cd "$(dirname "$0")"

ENV_FILE=.env
PW_FILE=prometheus/operator_password

if [ ! -f "$ENV_FILE" ]; then
  echo "FATAL: $ENV_FILE not found. First-time setup:" >&2
  echo "  cp .env.example .env && chmod 600 .env && \$EDITOR .env && bash bootstrap.sh" >&2
  exit 1
fi

pw=$(grep -E '^OPERATOR_PASSWORD=' "$ENV_FILE" | tail -1 | cut -d= -f2- || true)
if [ -z "$pw" ]; then
  echo "FATAL: OPERATOR_PASSWORD is missing or empty in $ENV_FILE" >&2
  exit 1
fi

mkdir -p prometheus
umask 077
printf '%s\n' "$pw" >"$PW_FILE"
chmod 600 "$PW_FILE"

echo "OK: $ENV_FILE present; $PW_FILE rendered from OPERATOR_PASSWORD (value not shown)."
