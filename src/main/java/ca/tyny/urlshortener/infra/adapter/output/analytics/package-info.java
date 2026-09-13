/**
 * # Component: Analytics
 *
 * ## Purpose
 * Asynchronous click-event pipeline behind the redirect hot path: fire-and-forget tracking onto a
 * durable Redis Stream, a self-healing consumer that enriches and bulk-persists events to
 * `click_events`, daily `click_daily` rollups with approximate unique visitors, and a scheduled
 * retention purge. Enrichment (device, country, referrer) happens worker-side only — the redirect
 * path never blocks on analytics.
 *
 * ## Requirements (EARS)
 *
 * ### REQ-ANALYTICS-001
 * **When** a redirect is served,
 * **the Business Component shall** accept the click event fire-and-forget (never block or fail
 * the redirect), enqueue it durably on the Redis Stream with a bounded length, and drop-with-metric
 * (fail-open) if Redis is unavailable.
 *
 * ### REQ-ANALYTICS-002
 * **When** the consumer processes a batch of click events,
 * **the Business Component shall** enrich each event worker-side (device from the user agent,
 * country via opt-in GeoIP), map it to a `click_events` document with a defaulted UTC timestamp,
 * bulk-insert the batch, and increment the short link's click counter atomically (`$inc`) — blank
 * short codes are skipped.
 *
 * ### REQ-ANALYTICS-003
 * **When** a consumer batch fails or a poison batch blocks the stream,
 * **the Business Component shall** redeliver it (at-least-once, PEL reclaim after recovery) and
 * finalize poison batches so the group keeps processing — click counts remain exact under
 * redelivery.
 *
 * ### REQ-ANALYTICS-004
 * **When** the daily rollup runs,
 * **the Business Component shall** aggregate per (shortCode, UTC day) into `click_daily`
 * idempotently (rerunning a day yields the same document), including device/country/referrer
 * breakdowns and approximate unique visitors via HyperLogLog.
 *
 * ### REQ-ANALYTICS-005
 * **When** the retention purge runs,
 * **the Business Component shall** delete `click_events` older than
 * `app.analytics.retention-days` (default 90) in bounded batches, idempotently and without
 * failing the schedule on error (fail-open with an error metric).
 *
 * ### REQ-ANALYTICS-006
 * **When** the ClickEvent payload shape changes,
 * **the Business Component shall** keep payloads readable by the previous release's consumer for
 * one release cycle (additive change, then drop) — the blue/green cutover window runs
 * old-consumer/new-producer concurrently on the same Redis Stream.
 *
 * ## Ports (Contracts)
 * - Outbound: {@link ca.tyny.urlshortener.core.ports.outgoing.AnalyticsPort} (track)
 * - REST (read side): `GET /api/v1/urls/{id}/clicks` (owner-guarded time series)
 *
 * ## Local Decisions (ADR inline)
 * - Queue: Redis Stream (`XADD MAXLEN ~`, bounded) — ADR 0006 (at-least-once; exactly-once rejected).
 * - Payload: flat string map (`c` short code, `t` UTC instant, `ua`, `ip`, `ref`, ...); new fields
 *  are additive and unknown-to-consumer fields are ignored (REQ-ANALYTICS-006).
 * - Rollup uniqueness: Redis HyperLogLog (`PFADD` in worker / `PFCOUNT` in query).
 * - Retention: batched delete, 5-min run cap, fail-open (ADR 0005 family).
 *
 * @spec-complete true
 */
package ca.tyny.urlshortener.infra.adapter.output.analytics;
