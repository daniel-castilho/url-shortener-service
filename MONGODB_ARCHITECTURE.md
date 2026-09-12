# MongoDB Architecture - URL Shortener Service

## 1. Migration Overview

The migration from **Cassandra to MongoDB** was performed following strict **Clean Architecture** and **SOLID** principles.

```
BEFORE (Cassandra):                   AFTER (MongoDB):
CassandraUrlRepository       →       MongoUrlRepository
  ↓                                    ↓
ShortUrlEntity (Cassandra)   →       ShortUrlEntity (MongoDB)
  ↓                                    ↓
UrlRepositoryPort                    UrlRepositoryPort (NO CHANGE)
  ↓                                    ↓
Domain Layer                         Domain Layer (NO CHANGE)
```

**Benefit**: The application core has no knowledge of the persistence implementation.

---

## 2. Layered Architecture

### 2.1 Domain Layer (Core)
```
core/
├── model/
│   └── ShortUrl.java              ← Pure record, no infra dependencies
├── ports/outgoing/
│   └── UrlRepositoryPort.java     ← Persistence-agnostic interface
└── service/
    └── UrlShortenerService.java   ← Pure business logic
```

**Principles**:
- ✅ No Spring annotations
- ✅ No external library imports (except std lib)
- ✅ Testable without Spring container

### 2.2 Infrastructure Layer (Adapter)
```
infra/adapter/output/persistence/
├── MongoUrlRepository.java         ← Implements UrlRepositoryPort
├── entity/
│   └── ShortUrlEntity.java         ← Entity mapped to MongoDB
├── mapper/
│   └── ShortUrlMapper.java         ← Domain ↔ entity conversion
├── exception/
│   └── RepositoryException.java    ← Persistence-agnostic error
└── config/
    └── MongoCollections.java       ← Collection name constants
```

**Principles**:
- ✅ Spring annotations only here
- ✅ All MongoDB-specific logic encapsulated
- ✅ Domain layer remains pure

---

## 3. Data Model

### 3.1 ShortUrlEntity (MongoDB Document)
```java
@Document(collection = "short_urls")
public record ShortUrlEntity(
    @Id String id,
    String originalUrl,
    String urlHash,
    String userId,
    Instant createdAt,
    Instant expiresAt,
    long clickCount,
    Boolean deleted
) {}
```

### 3.2 Collections
| Collection       | Purpose                              |
|------------------|--------------------------------------|
| `short_urls`     | Shortened URLs (main data)           |
| `users`          | User accounts and plans              |
| `click_events`   | Analytics events (append-only)       |
| `click_daily`    | Daily rollup for analytics           |
| `schema_migrations` | Migration history                   |
| `custom_domains` | Branded domain management            |

---

## 4. Indexes

| Collection     | Index                              | Type    | Purpose                              |
|----------------|------------------------------------|---------|--------------------------------------|
| `short_urls`   | `_id`                              | Unique  | Primary key (short code)             |
| `short_urls`   | `userId`                           | Index   | List user's URLs                     |
| `short_urls`   | `urlHash`                          | Index   | Future analytics (non-unique)        |
| `short_urls`   | `expiresAt`                        | TTL     | Auto-expire expired URLs             |
| `users`        | `email`                            | Unique  | Authentication                       |
| `users`        | `plan`                             | Index   | Plan-based queries                   |
| `click_events` | `(shortCode, timestamp)`           | Compound| Time-series analytics                |
| `click_events` | `timestamp`                        | TTL     | 90-day retention                     |
| `click_daily`  | `(shortCode, date)`                | Unique  | Daily rollup primary key             |
| `click_daily`  | `date`                             | Index   | Time-range queries                   |
| `custom_domains` | `domain`                          | Unique  | Domain ownership                     |

### Index Management
- **Versioned migrations**: `MongoSchemaMigrator` (V1–V9)
- **No auto-index-creation**: `spring.data.mongodb.auto-index-creation: false`
- **Checksummed, idempotent, fail-fast**: see `infra/adapter/output/persistence/migration/`

---

## 5. Data Access Patterns

### 5.1 Read Path (Redirect)
```
GET /{code}
    ↓
RedisUrlCache.lookup(code) → CacheLookup { Absence, value }
    ├─ HIT  → return 302 (value.originalUrl)
    ├─ MISS → MongoDB findById(code) → 302 / 404 / 410
    └─ BLOOM_NEGATIVE → MongoDB findById(code) → 302 / 404 / 410
```
- **Bloom filter**: Redisson-backed, short-circuits non-existent codes
- **L1 Cache**: Caffeine (100 items, 5s TTL, jitter)
- **L2 Cache**: Redis (24h TTL + jitter)
- **Fail-open**: Redis down → cache miss → MongoDB (no block)

### 5.2 Write Path (Shorten)
```
POST /api/v1/urls
    ↓
Validate URL (SSRF, HTTPS, blocklist)
    ↓
IdGeneratorPort.generateId() → Base62 (SecureRandom, length=7)
    ↓
MongoUrlRepository.save() → atomic _id insert
    ├─ Success → 201 + ShortenResponse
    ├─ DuplicateKeyException → retry (bounded, collision)
    └─ AliasAlreadyExistsException → 409 Conflict
```

### 5.3 Analytics (Fire-and-Forget)
```
GET /{id} (redirect)
    ↓
RedisClickEventQueue.track() → Redis Stream (XADD MAXLEN ~)
    ↓
ClickBatchWorker (consumer group)
    ├─ Bulk insert to click_events
    ├─ Atomic $inc clickCount per unique code
    └─ Ack batch (at-least-once, PEL redelivery on crash)
```

---

## 6. Analytics Pipeline

### 6.1 Click Event Document
```json
{
  "_id": ObjectId,
  "shortCode": "abc123",
  "timestamp": ISODate("2026-09-12T10:30:00Z"),
  "referrer": "https://example.com",
  "device": "mobile",
  "country": "US",
  "consumedAt": ISODate("2026-09-12T10:30:01Z")
}
```

### 6.2 Daily Rollup (ClickDaily)
```json
{
  "_id": "abc123:2026-09-12",
  "shortCode": "abc123",
  "date": ISODate("2026-09-12T00:00:00Z"),
  "clicks": 42,
  "devices": { "mobile": 25, "desktop": 15, "bot": 2 },
  "countries": { "US": 20, "BR": 12, "EU": 10 },
  "referrers": { "direct": 30, "google.com": 12 },
  "uniqueVisitors": 38  // HyperLogLog estimate
}
```

### 6.3 Worker Configuration
| Parameter              | Default    | Env Override             |
|------------------------|------------|--------------------------|
| Stream key             | `urlshortener:clicks` | `APP_ANALYTICS_STREAM_KEY` |
| Consumer group         | `click-worker`        | `APP_ANALYTICS_GROUP`      |
| Consumer name          | `worker-1`            | `APP_ANALYTICS_CONSUMER`   |
| Batch size             | 500                    | `APP_ANALYTICS_BATCH_SIZE` |
| Poll interval          | 5000ms                 | `APP_ANALYTICS_POLL_INTERVAL_MS` |

---

## 7. Reliability & Failure Modes

| Dependency    | Failure Mode     | Behavior                    |
|---------------|------------------|-----------------------------|
| Redis (cache) | Down             | **Fail-open**: cache miss → MongoDB |
| Redis (rate)  | Down             | **Fail-open**: no rate limiting |
| Redis (analytics) | Down         | **Fail-open**: event dropped, 302 still served |
| MongoDB       | Down             | **Fail-closed**: 503 via circuit breaker |
| OTel Collector| Down             | **Fail-open**: tracing lost, requests succeed |

---

## 7. Operational Procedures

### 7.1 Backup
```bash
# Full dump (run on MongoDB host)
bash scripts/backup-mongodb.sh /var/backups/url-shortener
# Produces: /var/backups/url-shortener/YYYYMMDD-HHMMSS/
#   ├── dump/url_shortener/*.bson.gz
#   └── manifest.json (timestamp, db, version, collection counts)
```

### 7.2 Restore
```bash
# Restore with verification
bash scripts/restore-mongodb.sh --verify /var/backups/url-shortener/20260912-020000
# Re-counts all collections against manifest.json
# Fails on any divergence
```

### 7.3 Scheduled Backup (systemd)
```ini
# /etc/systemd/system/url-shortener-backup.timer
[Timer]
OnCalendar=*-*-* 03:30:00
Persistent=true

# /etc/systemd/system/url-shortener-backup.service
[Unit]
Description=URL Shortener nightly MongoDB backup
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
User=urlshortener
WorkingDirectory=/opt/url-shortener
ExecStart=/opt/url-shortener/scripts/backup-mongodb.sh /var/backups/url-shortener
Nice=10
```

---

## 8. DR Drill Results (Epic 7)

| Scenario                  | Result                           | Evidence                    |
|---------------------------|----------------------------------|-----------------------------|
| Redis down (mid-run)      | 100% success, p95 744ms          | 5,730/5,730 checks 302      |
| MongoDB down (cold cache) | CB fail-closed, auto-recovery    | p50 3.56ms, p99 27s         |
| Backup/Restore (RPO)      | Pre-backup 302, post-backup 404  | 7,640 docs, RPO = last dump |

See `docs/release-runbook.md` §5b for full playbooks.

---

## 9. Future Considerations

- **Replica Set**: Not yet implemented (SPOF accepted, see ADR 0001)
- **Sharding**: Not needed at current scale (ADR 0001)
- **Change Streams**: For real-time analytics (future)
- **Field-level encryption**: For PII in click_events (future)

---

*Last updated: 2026-09-12*