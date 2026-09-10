# Twelve-Factor App — Reference & Compliance

The project follows the [Twelve-Factor App](https://12factor.net/) methodology. Goal: a codebase that
deploys identically to any environment (dev, staging, prod) with no code changes, reproducible builds,
and easy scaling.

> **This is a commitment, not a suggestion.** When writing or reviewing code, check the factor affected
> by the change and keep the table below green.

## The 12 factors and how URL Shortener Service complies

| # | Factor           | Status | Notes |
| - | ---------------- | ------ | ----- |
| 1 | Codebase         | ✅ One repo, one app | Git repo, `main` branch. No per-environment branches. `0.0.1-SNAPSHOT`. |
| 2 | Dependencies     | ✅ Declared, pinned | `pom.xml` with Spring Boot BOM (4.1.1) and explicit versions for third-party libs (jjwt 0.12.7, resilience4j 2.4.0, redisson 4.7.0, testcontainers 2.0.5, logstash-logback-encoder 9.0). Maven wrapper 3.9.16 — build with `./mvnw`. Hashids removed (identity model: Base62 + SecureRandom). |
| 3 | Config           | ✅ Env contract + fail-fast | Dev-only defaults in `application.yaml` (`MONGODB_URI` → `localhost`, `REDIS_HOST`/`PORT` → `localhost`, `APP_JWT_SECRET` example, `app.shortener.code-length` default 7). All env-specific values bind from env vars. **ProdConfigValidator** aborts startup on missing/weak required prod vars (`app.jwt.secret`, `spring.mongodb.uri`, `spring.data.redis.host`, `rate-limiter.trusted-proxy-cidrs`, `management.otlp.tracing.endpoint`, `app.analytics.retention-days`). `application-test.yaml` overrides DB/Redis for tests. |
| 4 | Backing services | ✅ Attached resources | MongoDB and Redis are attached external resources addressed by endpoint/config (`docker-compose.yaml` + `application.yaml`). Nothing is embedded in the app. |
| 5 | Build, release, run | ✅ Complete | Build = `./mvnw clean package` (jar) or `-Pnative` (GraalVM native image). Run = `java -jar ...` or `./mvnw spring-boot:run` or systemd unit (`deploy/url-shortener.service`). CI workflow runs `./mvnw test`, `*IT`, `./mvnw verify`. Schema via versioned in-code migrations (`MongoSchemaMigrator` V1–V6). |
| 6 | Processes        | ✅ Stateless | Auth is stateless JWT; no session. Cache (Redis) and DB are shared attached resources. Analytics queue is a **durable Redis Stream** (`RedisClickEventQueue` + `ClickBatchWorker`) — no in-process state, scales horizontally, at-least-once delivery. |
| 7 | Port binding     | ✅ Self-contained | Spring Boot embedded web server (Tomcat 11) binds `:8080`; no external web server injected. |
| 8 | Concurrency      | ✅ Virtual Threads | Stateless service scales by spawning processes; **Virtual Threads** (Loom) handle I/O concurrency well on a single host. |
| 9 | Disposability    | ✅ Graceful | Spring Boot with `server.shutdown: graceful` and `spring.lifecycle.timeout-per-shutdown-phase: 30s` drains in-flight requests before stopping on `SIGTERM`. Verified via `scripts/verify-graceful-shutdown.sh`. |
| 10 | Dev/prod parity  | ✅ Containers | `docker-compose up -d` (MongoDB + Redis) keeps local close to prod. On-premises bare metal is the production target. |
| 11 | Logs             | ✅ Structured + JSON profile | `logback-spring.xml` writes to console (async) and rolling file. **JSON profile** (`-Dspring.profiles.active=json`) emits structured JSON via `logstash-logback-encoder` (service=url-shortener, traceId/spanId MDC). Never log passwords, JWT secrets, bearer tokens or destinations with credentials. |
| 12 | Admin processes  | ✅ Scripted & documented | One-off tasks run as separate commands: `scripts/backup-mongodb.sh`, `scripts/restore-mongodb.sh`, `scripts/performance-baseline.sh`, `scripts/verify-graceful-shutdown.sh`. Versioned schema changes (indexes/TTL) via `MongoSchemaMigrator` V1–V9. MongoDB backup/restore documented in `release-runbook.md`. |

Legend: ✅ compliant · ⚠️ partially compliant / has an open TODO.

---

## Hard rules to keep the list green

- Never hardcode environment-specific values (URLs, secrets, credentials) in production code paths;
  read them from env vars, with dev-only defaults in `application.yaml`.
- Secrets live only in env vars / the deployed environment — never in git, code, or logs. The
  `app.jwt.secret` in `application.yaml` is a dev-only example; `APP_JWT_SECRET` must be a strong
  random value in production. Do not add a code-generation salt.
- Every build must be reproducible: `./mvnw clean package` from a clean checkout. Do not rely on artifacts
  left in `target/`.
- Schema changes (MongoDB indexes, TTL, collections) ship as part of the change set — via a versioned
  migration, not `auto-index-creation`, and not by hand-editing a shared environment only.
- Local dev must match production dependencies as closely as possible (use `docker-compose`).

---

## Open TODOs (tracked)

1. **GraalVM native `mainClass` fixed** — `native` profile now points to `ca.tyny.urlshortener.Application`; `./mvnw clean package -Pnative` resolves correctly.
   `ca.tyny.urlshortener.Application`; document the startup/memory targets under load.
2. **Expose analytics queue depth gauge** — `analytics.queue.depth` (Micrometer) for the Grafana panel.

---

*Last updated: 2026-08-27 (Operational Excellence epic)*