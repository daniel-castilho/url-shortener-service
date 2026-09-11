# ADR 0001: Scale horizontally as stateless instances behind a load balancer

- **Status:** accepted
- **Date:** 2026-09-11
- **Epic:** 6 (Scalable)

## Context

The service runs on-premises bare metal (systemd, no orchestrator). Traffic growth on the
redirect hot path is the scaling driver. The application already satisfies 12-factor §6
(processes) and §8 (concurrency — virtual threads):

- Authentication is stateless JWT (no HTTP session).
- MongoDB and Redis are shared attached resources (data model in `MONGODB_ARCHITECTURE.md`).
- The click-analytics pipeline is a durable Redis Stream (`RedisClickEventQueue` +
  `ClickBatchWorker` with a self-healing consumer group) — no in-process state, at-least-once
  delivery; any instance can consume.
- Per-IP rate limiting lives in Redis (ADR 0002), not in instance memory.

The alternative considered was vertical scaling (bigger host). Virtual threads already
exploit a large host well (Epic 5: 2× stress on one instance, p95 < 5 ms, 0 failures), but a
single host is a SPOF for restarts/deploy and has a hard ceiling.

## Decision

Scale **horizontally**: run N identical stateless instances of the jar behind an nginx
reverse proxy (`deploy/proxy/nginx.conf`, upstream `url_shortener_backend` with per-server
weights) sharing the existing MongoDB + Redis. Deploy N=1 by default; add instances via the
systemd template unit (`deploy/url-shortener@.service`) without code changes.

Rejected: vertical-only (SPOF, hard ceiling); sharding MongoDB (no evidence of need —
single-collection lookups by `_id` at current volumes); orchestrators/k8s (out of scope for
on-prem bare metal).

## Consequences

**Positive**
- No re-architecture to grow; capacity is added by starting `url-shortener@2.service`.
- Rolling restarts: instances drain independently (graceful shutdown, `server.shutdown:
  graceful`) — zero-downtime deploys with weight flips.
- Stateless instances can be moved between hosts freely.

**Negative / trade-offs**
- The L1 Caffeine cache is per instance (ADR 0003) — N instances have N small caches.
- Shared-resource contention: MongoDB connections scale with N (pool per instance); Redis
  client connections likewise. Monitoring must watch both as N grows.
- Operational surface grows: nginx upstream config and systemd units must be kept in sync
  (addressed by the runbook in `docs/release-runbook.md`).
