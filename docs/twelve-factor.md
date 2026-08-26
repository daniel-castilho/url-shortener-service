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
| 2 | Dependencies     | ⚠️ Declared, not pinned to a wrapper | `pom.xml` with Spring Boot BOM (3.5.7) and explicit versions for third-party libs (jjwt 0.12.3, resilience4j 2.2.0, redisson 3.43.0, testcontainers 1.19.3). **No Maven wrapper** (`./mvnw`) — build with `mvn`. Identity model does **not** include Hashids; remove `org.hashids` when stories I1–I2 land. |
| 3 | Config           | ✅ Env contract | Dev-only defaults live in `application.yaml` (`MONGODB_URI` → `localhost`, `REDIS_HOST`/`PORT` → `localhost`, `APP_JWT_SECRET` example, `app.shortener.code-length` default 7). All env-specific values bind from env vars. Short codes do **not** use a salt. **Target:** add a `ProdConfigValidator`-style fail-fast startup check for required prod vars so an incomplete contract aborts at boot. `application-test.yaml` overrides DB/Redis for tests. |
| 4 | Backing services | ✅ Attached resources | MongoDB and Redis are attached external resources addressed by endpoint/config (`docker-compose.yaml` + `application.yaml`). Nothing is embedded in the app. |
| 5 | Build, release, run | ⚠️ Partial | Build = `mvn clean package` (jar) or `-Pnative` (currently **broken** — bad `mainClass`). Run = `java -jar ...` or `mvn spring-boot:run` or a container. **No CI workflow committed**; schema (indexes) is created by `auto-index-creation` — replace with **versioned migrations** (debt item 10). |
| 6 | Processes        | ⚠️ Mostly stateless | Auth is stateless JWT; no session. Cache (Redis) and the DB are shared attached resources. **Caveat:** the analytics queue is an **in-memory `LinkedBlockingQueue`** in the app process — per-instance state that does not scale horizontally and drops events when full. Replace with a durable queue (Redis Stream) to keep processes stateless (debt item 5/15). |
| 7 | Port binding     | ✅ Self-contained | Spring Boot embedded web server (Undertow) binds `:8080`; no external web server injected. |
| 8 | Concurrency      | ⚠️ Process-based | Stateless service scales by spawning processes; **Virtual Threads** (Loom) handle I/O concurrency well on a single host. Once the analytics queue is made durable (factor 6), scaling is clean. |
| 9 | Disposability    | ✅ Graceful | Spring Boot with `server.shutdown: graceful` drains in-flight requests before stopping on `SIGTERM` (verify the configured grace period under load). |
| 10 | Dev/prod parity  | ✅ Containers | `docker-compose up -d` (MongoDB + Redis) keeps local close to prod. On-premises bare metal is the production target. |
| 11 | Logs             | ✅ Structured | `logback-spring.xml` writes to console (async) and a rolling file. **Target:** add structured (JSON) logs via a `logstash`/`logstash-logback-encoder` profile for aggregation, and keep the `GlobalExceptionHandler` logging request context (method + URI + exception class). Never log passwords, JWT secrets, bearer tokens or destinations with credentials. |
| 12 | Admin processes  | ⚠️ Partial | One-off tasks run as separate commands: `docker-compose up -d`, `docker exec mongosh ...`. **TBD:** versioned schema changes (indexes/TTL) as a repeatable, committed migration step; backup/restore scripts for MongoDB. |

Legend: ✅ compliant · ⚠️ partially compliant / has an open TODO.

---

## Hard rules to keep the list green

- Never hardcode environment-specific values (URLs, secrets, credentials) in production code paths;
  read them from env vars, with dev-only defaults in `application.yaml`.
- Secrets live only in env vars / the deployed environment — never in git, code, or logs. The
  `app.jwt.secret` in `application.yaml` is a dev-only example; `APP_JWT_SECRET` must be a strong
  random value in production. Do not add a code-generation salt.
- Every build must be reproducible: `mvn clean package` from a clean checkout. Do not rely on artifacts
  left in `target/`.
- Schema changes (MongoDB indexes, TTL, collections) ship as part of the change set — via a versioned
  migration, not `auto-index-creation`, and not by hand-editing a shared environment only.
- Local dev must match production dependencies as closely as possible (use `docker-compose`).

---

## Open TODOs (tracked)

1. **Versioned schema/index migration** — replace `spring.data.mongodb.auto-index-creation: true` with a
   migration framework; manage indexes (incl. TTL on `expiresAt`) in a deploy step. Required to drop
   leftover unique-on-`originalUrl` indexes cleanly (identity model: no URL dedup).
2. **Make processes stateless (factor 6)** — replace the in-memory analytics queue with a durable queue
   (Redis Stream) so the app scales and doesn't drop events.
3. **Wire a CI workflow** — add `.github/workflows/ci.yml` running `mvn test`, `*IT` (with Docker) and
   `mvn clean package` on every push/PR.
4. **Add structured JSON logging + tracing** — enable a JSON log profile and (optionally)
   OpenTelemetry for observability; record real p50/p95/p99 latency baseline.
5. **Add a prod config fail-fast validator** — abort startup on missing/weak required env vars.
6. **Fix the GraalVM native build** — correct the `native` profile `mainClass` to
   `ca.tyny.urlshortener.Application`; document the startup/memory targets under load.
