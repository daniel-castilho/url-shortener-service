# Performance Profiling — URL Shortener Service (Epic 5, story 5.3)

Hot-path profile via **JFR** (built-in JDK 25, `settings=profile`) captured during a live
k6 `mixed` workload (20 shorten + 200 redirect req/s, 3m — 39,598 iterations, 235.7 req/s,
`p95 5.62 ms`, `http_req_failed = 0`). Recording: 236 s, 10.4 MB, dumped from the running
app JVM (`ca.tyny.urlshortener.Application`).

```
jcmd <pid> JFR.start name=epic5-profile settings=profile
# ... k6 load ...
jcmd <pid> JFR.dump name=epic5-profile filename=/tmp/opencode/epic5.jfr
jfr summary /tmp/opencode/epic5.jfr
```

## Verdict (short)

The hot path is **healthy**: no lock contention, GC pauses are short and rare, and the
request path is dominated by framework overhead (Micrometer observation + Spring Security
filter chain), which is inherent to the stack — not an application defect. No mitigation is
warranted by the data; p95/p99 sit at 5.4/8.8 ms against a 200 ms SLO (37× headroom).

## Findings

### 1. GC (G1) — healthy, no action required

```
Total Pause Time: 502 ms over 236 s (0.21% of wall clock)
Number of Pauses: 155
Median Pause: 3.43 ms   P95: 7.71 ms   P99: 14.6 ms   Max: 15.5 ms
```

Median 3.4 ms pauses cannot perturb a 200 ms SLO. Allocation pressure is dominated by
`byte[]` (16.24%), virtual-thread `StackChunk` (6.22%, inherent to Loom), request buffers
(`HeapByteBuffer` 2.18% + `DirectByteBuffer` 1.76%, Tomcat I/O) and **Micrometer
`ImmutableTag`/`KeyValues` (~6% combined)** — see finding 3.

### 2. Lock contention — none

`jfr view contention-by-thread` → **No events found**. `JavaMonitorWait` = 3 events
(scheduler idle, not application locks). The Redis/Redisson path shows no contention either.

### 3. Per-request framework allocation (observation stack) — dominant app-visible cost

Top allocation sites on `tomcat-handler-*` virtual threads:

- `io.micrometer.core.instrument.Meter$Id.getConventionTags` — allocates `ImmutableTag` +
  streams on **every meter registration lookup** (2.62% pressure alone)
- `io.micrometer.common.KeyValues.of` / `ObservationFilterChainDecorator` — Spring Security's
  observation filter materializes key-values per request
- `ArrayList$Itr` (4.15%) + `ConcurrentHashMap$ValueIterator` (2.72%) — iteration churn in
  the same paths

This is **framework-internal** (Micrometer 1.17 / Spring Security 7 observation plumbing);
every measured request still lands at p95 5.6 ms. Mitigation (disabling the observation
decorator or pre-registering meters) would shave single-digit-microsecond allocations —
no measurable SLO impact. **Decision: no change.**

### 4. Exceptions — noise, not defects

11,264 of 11,414 recorded throws are `io.netty ResourceLeakDetector$TraceRecord` —
diagnostic `Throwable` captures from the leak detector (sampling), not real failures.
`EOFException`(44)/`StacklessClosedChannelException`(5) are Redis idle-connection reaping.
`NoSuchMethodError`(40) are JVM-internal method-handle linkage probes on virtual threads
(benign, no user impact; matches the `delegatingMethodHandle` allocation sites).

### 5. Socket reads — Mongo idle pool heartbeats, off the request path

All 23 reads > 0.5 s are on `cluster-...-localhost:27018` **connection-maintenance**
threads (10 s waits = driver's `maxWaitTime`/idle scan), while request threads show Socket
read/write in the 2–6 ms range. No request-path I/O stall exists.

## Mitigations

**None applied.** Justification: every candidate finding is either healthy (GC, contention,
I/O), framework-internal with microsecond impact (Micrometer tags), or diagnostic noise
(Netty leak-detector records). The SLO has 37× headroom (p95 5.6 ms vs 200 ms) and
`http_req_failed = 0`. Any change here would be speculative complexity — contrary to the
data. Re-profile after the next platform upgrade or if the k6 baseline shows p95 > 20 ms.

## Reproduction

```bash
# infra (isolated) + app with relaxed rate limits
docker run -d --name urlshortener-mongo-isolated -p 27018:27017 mongo:6.0
docker run -d --name urlshortener-redis-isolated -p 6380:6379 redis:latest
RATE_LIMITER_LIMIT=1000000 RATE_LIMITER_REDIRECT_LIMIT=1000000 SERVER_PORT=8089 \
MONGODB_URI=mongodb://localhost:27018/url_shortener REDIS_HOST=localhost REDIS_PORT=6380 \
./mvnw spring-boot:run &
# profile while loading (mixed = shorten + redirect)
BASE_URL=http://localhost:8089 DURATION=3m REDIRECT_RPS=200 SHORTEN_RPS=20 \
docker run --rm --network host -v "$PWD:/baseline" -w /baseline grafana/k6 run load-tests/mixed.js
```

Recording artefact of this run: `/tmp/opencode/epic5.jfr` (10.4 MB, 236 s).
