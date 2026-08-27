#!/bin/bash
# MongoDB Restore Script for URL Shortener Service
#
# Restores a mongodump backup to the url_shortener database.
# Includes a verification step to confirm data integrity.
#
# Usage:
#   bash scripts/restore-mongodb.sh <backup-directory>
#
# Environment:
#   MONGODB_URI - MongoDB connection string (default: mongodb://localhost:27017/url_shortener)
#   DROP_FIRST - Drop existing database before restore (default: false)
#
# Requirements:
#   - mongorestore in PATH (MongoDB Database Tools)
#   - Network access to MongoDB
#   - Valid backup directory created by backup-mongodb.sh

set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <backup-directory>"
    echo "Example: $0 /var/backups/url-shortener/20260827-120000"
    exit 1
fi

BACKUP_DIR="$1"
MONGODB_URI="${MONGODB_URI:-mongodb://localhost:27017/url_shortener}"
DROP_FIRST="${DROP_FIRST:-false}"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

if [ ! -d "$BACKUP_DIR" ]; then
    log "ERROR: Backup directory not found: $BACKUP_DIR"
    exit 1
fi

# Extract database name from URI
DB_NAME=$(echo "$MONGODB_URI" | sed -E 's|.*/([^/?]+)(\?.*)?$|\1|')
if [ -z "$DB_NAME" ]; then
    DB_NAME="url_shortener"
fi

BACKUP_DB_DIR="$BACKUP_DIR/$DB_NAME"
if [ ! -d "$BACKUP_DB_DIR" ]; then
    log "ERROR: Backup does not contain database '$DB_NAME' at $BACKUP_DB_DIR"
    exit 1
fi

log "Starting MongoDB restore for database: $DB_NAME"
log "Source: $BACKUP_DB_DIR"
log "Target: $MONGODB_URI"

# Optionally drop existing database
if [ "$DROP_FIRST" = "true" ]; then
    log "Dropping existing database '$DB_NAME'..."
    mongosh "$MONGODB_URI" --eval "db.dropDatabase()" --quiet
fi

# Perform restore
log "Restoring from backup..."
if mongorestore --uri="$MONGODB_URI" --db="$DB_NAME" --gzip "$BACKUP_DB_DIR"; then
    log "Restore completed successfully"
else
    log "ERROR: mongorestore failed"
    exit 1
fi

# Verification step
log "Verifying restore..."
COLLECTION_COUNT=$(mongosh "$MONGODB_URI" --eval "db.getCollectionNames().length" --quiet)
log "Restored database has $COLLECTION_COUNT collections"

# Verify each collection has documents
mongosh "$MONGODB_URI" --eval '
    db.getCollectionNames().forEach(function(c) {
        var count = db[c].countDocuments();
        print("  " + c + ": " + count + " documents");
    });
' --quiet

log "Restore verification complete!"