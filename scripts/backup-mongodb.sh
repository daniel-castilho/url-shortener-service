#!/bin/bash
# MongoDB Backup Script for URL Shortener Service
#
# Performs a mongodump of the url_shortener database to a timestamped directory.
# Designed to run as a scheduled job (cron/systemd timer) on the database host
# or a backup server with network access to MongoDB.
#
# Usage:
#   bash scripts/backup-mongodb.sh [output-dir]
#
# Environment:
#   MONGODB_URI - MongoDB connection string (default: mongodb://localhost:27017/url_shortener)
#   BACKUP_RETENTION_DAYS - Days to keep backups (default: 30)
#
# Requirements:
#   - mongodump in PATH (MongoDB Database Tools)
#   - Network access to MongoDB

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

MONGODB_URI="${MONGODB_URI:-mongodb://localhost:27017/url_shortener}"
OUTPUT_BASE="${1:-/var/backups/url-shortener}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
OUTPUT_DIR="${OUTPUT_BASE}/${TIMESTAMP}"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# Extract database name from URI
DB_NAME=$(echo "$MONGODB_URI" | sed -E 's|.*/([^/?]+)(\?.*)?$|\1|')
if [ -z "$DB_NAME" ]; then
    DB_NAME="url_shortener"
fi

log "Starting MongoDB backup for database: $DB_NAME"
log "Output directory: $OUTPUT_DIR"

mkdir -p "$OUTPUT_DIR"

# Perform backup
if mongodump --uri="$MONGODB_URI" --db="$DB_NAME" --out="$OUTPUT_DIR" --gzip; then
    log "Backup completed successfully: $OUTPUT_DIR"
    
    # Verify backup
    if [ -d "$OUTPUT_DIR/$DB_NAME" ]; then
        COLLECTION_COUNT=$(find "$OUTPUT_DIR/$DB_NAME" -name "*.bson.gz" | wc -l)
        log "Backup contains $COLLECTION_COUNT collections"
        
        # Create metadata file
        cat > "$OUTPUT_DIR/metadata.json" <<EOF
{
    "timestamp": "$(date -Iseconds)",
    "database": "$DB_NAME",
    "collections": $COLLECTION_COUNT,
    "mongodb_uri": "$(echo "$MONGODB_URI" | sed 's/:[^@]*@/:***@/')",
    "hostname": "$(hostname)"
}
EOF
    else
        log "ERROR: Backup directory structure unexpected"
        exit 1
    fi
else
    log "ERROR: mongodump failed"
    rm -rf "$OUTPUT_DIR"
    exit 1
fi

# Cleanup old backups
log "Cleaning up backups older than $RETENTION_DAYS days..."
find "$OUTPUT_BASE" -maxdepth 1 -type d -name "20*" -mtime +"$RETENTION_DAYS" -exec rm -rf {} \; 2>/dev/null || true

REMAINING=$(find "$OUTPUT_BASE" -maxdepth 1 -type d -name "20*" | wc -l)
log "Backup complete. $REMAINING backups retained in $OUTPUT_BASE"