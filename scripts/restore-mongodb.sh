#!/bin/bash
# MongoDB Restore Script for URL Shortener Service (Epic 8 story 8.5: + verified restore).
#
# Restores a mongodump backup and — with --verify — re-counts every manifest collection
# and compares against the manifest.json written by backup-mongodb.sh. Any divergence
# (missing collection, smaller count, unreadable manifest) exits non-zero: a restore is
# only "done" when the counts prove it. "Backup without a verified restore is hope."
#
# Usage:
#   bash scripts/restore-mongodb.sh <backup-directory> [--verify]
#
# Environment:
#   MONGODB_URI  - MongoDB connection string (default: mongodb://localhost:27017/url_shortener)
#   DROP_FIRST   - Drop existing database before restore (default: false)
#
# Requirements:
#   - mongorestore + mongosh in PATH (or the mongo container fallback, like backup-mongodb.sh)

set -euo pipefail

BACKUP_DIR=""
VERIFY=false
for arg in "$@"; do
  case "$arg" in
    --verify) VERIFY=true ;;
    *) [ -z "$BACKUP_DIR" ] && BACKUP_DIR="$arg" ;;
  esac
done

if [ -z "$BACKUP_DIR" ]; then
    echo "Usage: $0 <backup-directory> [--verify]"
    echo "Example: $0 /var/backups/url-shortener/20260827-120000"
    exit 1
fi

MONGODB_URI="${MONGODB_URI:-mongodb://localhost:27017/url_shortener}"
DROP_FIRST="${DROP_FIRST:-false}"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
die() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $*" >&2; exit 1; }

# resolve a mongo CLI tool: PATH first; otherwise a generated wrapper script (same pattern
# and rationale as backup-mongodb.sh — see its header for the full explanation).
trap '[ -n "${TOOL_BIN_DIR:-}" ] && rm -rf "$TOOL_BIN_DIR"' EXIT
mongo_tool() {
    local bin="$1"
    if command -v "$bin" >/dev/null 2>&1; then
        echo "$bin"
        return 0
    fi
    if docker image inspect mongo:6.0 >/dev/null 2>&1; then
        TOOL_BIN_DIR=$(mktemp -d)
        local wrapper="$TOOL_BIN_DIR/$bin"
        cat > "$wrapper" <<EOF
#!/bin/bash
exec docker run --rm --network host --user "\$(id -u):\$(id -g)" -e HOME=/tmp \${MONGO_TOOL_EXTRA_ARGS:-} mongo:6.0 $bin "\$@"
EOF
        chmod +x "$wrapper"
        echo "$wrapper"
        return 0
    fi
    return 1
}

MONGORESTORE="$(mongo_tool mongorestore)" || die "mongorestore not in PATH and mongo:6.0 image unavailable (see backup-mongodb.sh header)"
MONGOSH="$(mongo_tool mongosh)" || die "mongosh not in PATH and mongo:6.0 image unavailable (see backup-mongodb.sh header)"

if [ ! -d "$BACKUP_DIR" ]; then
    die "Backup directory not found: $BACKUP_DIR"
fi

# Extract database name from URI
DB_NAME=$(echo "$MONGODB_URI" | sed -E 's|.*/([^/?]+)(\?.*)?$|\1|')
if [ -z "$DB_NAME" ]; then
    DB_NAME="url_shortener"
fi

BACKUP_DB_DIR="$BACKUP_DIR/$DB_NAME"
if [ ! -d "$BACKUP_DB_DIR" ]; then
    die "Backup does not contain database '$DB_NAME' at $BACKUP_DB_DIR"
fi

log "Starting MongoDB restore for database: $DB_NAME"
log "Source: $BACKUP_DB_DIR"
log "Target: $MONGODB_URI"

if [ "$DROP_FIRST" = "true" ]; then
    log "Dropping existing database '$DB_NAME'..."
    $MONGOSH "$MONGODB_URI" --eval "db.dropDatabase()" --quiet
fi

log "Restoring from backup..."
# When the tool is the container wrapper, mount the backup dir read-only so mongorestore
# can read the dump files (MONGO_TOOL_EXTRA_ARGS is read by the wrapper; detection via the
# mktemp path pattern — same note about subshells as in backup-mongodb.sh).
RESTORE_SRC="$BACKUP_DB_DIR"
case "$MONGORESTORE" in
    /tmp/tmp.*/mongorestore)
        export MONGO_TOOL_EXTRA_ARGS="-v $BACKUP_DIR:/mongorestore-src:ro"
        RESTORE_SRC="/mongorestore-src/$DB_NAME"
        ;;
esac

if ! $MONGORESTORE --uri="$MONGODB_URI" --db="$DB_NAME" --gzip "$RESTORE_SRC"; then
    die "mongorestore failed"
fi
log "Restore completed successfully"

# ---------------------------------------------------------------- verification
log "Verifying restore..."
COLLECTION_COUNT=$($MONGOSH "$MONGODB_URI" --quiet --eval "db.getCollectionNames().length")
log "Restored database has $COLLECTION_COUNT collections"

$MONGOSH "$MONGODB_URI" --quiet --eval '
    db.getCollectionNames().forEach(function(c) {
        var count = db[c].countDocuments();
        print("  " + c + ": " + count + " documents");
    });
'

if [ "$VERIFY" != "true" ]; then
    log "Restore verification complete! (counts only — use --verify to enforce the manifest contract)"
    exit 0
fi

# ---------------------------------------------------------------- --verify: manifest contract
MANIFEST="$BACKUP_DIR/manifest.json"
[ -f "$MANIFEST" ] || die "--verify: manifest not found next to the dump: $MANIFEST (backup-mongodb.sh writes it)"

# Same collection list as backup-mongodb.sh (the contract is symmetrical by design).
MANIFEST_COLLECTIONS=(short_urls users custom_domains click_events click_daily schema_migrations)

log "--verify: re-counting manifest collections against $MANIFEST"
FAILURES=0
printf "%-20s %12s %12s %s\n" "COLLECTION" "MANIFEST" "RESTORED" "VERDICT"
for c in "${MANIFEST_COLLECTIONS[@]}"; do
    expected=$(grep -oE "\"$c\"[[:space:]]*:[[:space:]]*[0-9]+" "$MANIFEST" | grep -oE '[0-9]+' | head -1)
    [ -n "$expected" ] || { printf "%-20s %12s %12s %s\n" "$c" "?" "-" "UNREADABLE-MANIFEST"; FAILURES=$((FAILURES+1)); continue; }
    actual=$($MONGOSH "$MONGODB_URI" --quiet --eval "db.$c.countDocuments({})" 2>/dev/null || echo "-1")
    if [ "$actual" -lt "$expected" ]; then
        printf "%-20s %12s %12s %s\n" "$c" "$expected" "$actual" "DIVERGENT"
        FAILURES=$((FAILURES+1))
    else
        printf "%-20s %12s %12s %s\n" "$c" "$expected" "$actual" "ok"
    fi
done

if [ "$FAILURES" -ne 0 ]; then
    die "--verify: $FAILURES collection(s) diverge from the manifest — restore UNVERIFIED"
fi
log "--verify: all manifest collections match — restore VERIFIED"
