# Failure and recovery

PostgreSQL contains the state required to resume accepted work. Recovery is automatic for ordinary publisher/consumer/worker restarts; operators should inspect growing backlogs and repeated dependency failures instead of deleting stuck rows.

| Failure boundary | Durable state | Recovery and consequence |
| --- | --- | --- |
| API crashes before commit | Neither event nor outbox is committed | Client retries its idempotency key |
| API crashes after commit but before response | Event and outbox exist | Retry returns the existing event; a mismatched request is rejected |
| Kafka unavailable during publication | Outbox remains unpublished | A later publisher claim retries publication |
| Publisher dies while holding a lease | Unpublished row has an expiring lease | Another publisher claims it after expiry |
| Kafka acknowledges; publisher dies before marking success | Kafka may already contain the message | Outbox republishes; routing/job processing deduplicates |
| Router commits; consumer stops before offset commit | Unique delivery rows already exist | Kafka redelivery encounters the uniqueness constraint safely |
| Scheduler dies before its transaction commits | No new lease/job is committed | A different scheduler claims the due row |
| Scheduler commits; process dies before job publication | Lease and job outbox both exist | Outbox publisher sends the job; abandoned leases can be reclaimed |
| Delivery job arrives after its lease was replaced | Job carries stale fencing token | Worker rejects it without changing the new lease |
| Worker stops before HTTP | Processing lease eventually expires | Scheduler issues a new fenced job |
| Receiver commits; worker stops before saving success | Outcome is ambiguous | Retry can deliver a duplicate; receiver deduplicates by delivery ID |
| Old worker completes after lease replacement | New worker owns a different token | Conditional completion cannot overwrite new state |
| Redis unavailable | Existing events/deliveries remain durable | New ingestion fails conservatively; retry after dependency recovery |
| Destination unavailable | Attempt and next deadline are persisted | Bounded exponential retries; terminal exhaustion is recorded |
| PostgreSQL unavailable | New durable writes cannot commit | API rejects ingestion; workers must not acknowledge uncommitted processing |

## Outcome classification

- HTTP `2xx`: delivered.
- HTTP `408`, `425`, `429`, and `5xx`: retryable until the endpoint attempt budget is exhausted.
- Other `4xx`: terminal failure because retrying the same request ordinarily cannot fix it.
- `3xx`: not followed; treated as a terminal destination response. A redirect must never bypass destination validation.
- Connection timeouts, read timeouts, DNS/network failures, connection resets and TLS transport errors: recorded failures with bounded retries.
- Invalid or unsafe destination: terminal security failure; do not retry toward a forbidden address.

The worker's classifier is authoritative if this documentation and a future code change diverge. Inspect attempt outcome and error category; response bodies are not needed for operational logging and can contain sensitive information.

## Retry and replay semantics

The endpoint defines maximum attempts, base delay and delay cap. Delay grows exponentially and includes jitter to avoid synchronized retry storms. PostgreSQL's `next_attempt_at` is authoritative; a process restart does not reset it. The first attempt is immediately eligible.

Exhausted or terminal deliveries create a durable dead-letter record and an outbox notification to the DLQ topic. The database record powers inspection even when Kafka is temporarily unavailable. Authorized manual replay resets eligibility and the attempt budget for a new replay generation, retains historical attempts/dead letters, and keeps the stable delivery ID. Receivers should deduplicate against their own successfully committed processing, not merely receipt.

## Incident checks

1. Check health endpoints, database connectivity and available disk space.
2. Compare outbox backlog, pending/retry backlog, processing leases and consumer lag. A growing outbox with healthy API ingestion usually points to broker/publisher failure.
3. Inspect structured logs with the returned correlation ID, event ID or delivery ID; never paste tokens or payloads into incident channels.
4. Inspect the latest attempt, next retry time, endpoint status and retry policy. Large `429` volume calls for lower sending concurrency or a coordinated receiver quota.
5. Restart failed workers after fixing the dependency; allow leases to expire. Do not manually mark rows delivered or delete unpublished outbox rows.
6. Replay a selected terminal delivery only after correcting the underlying destination failure. Bulk replay needs controlled rate and database backups.

Useful read-only queries:

```sql
SELECT count(*) AS unpublished,
       min(created_at) AS oldest_unpublished
FROM outbox_messages WHERE published_at IS NULL;

SELECT status, count(*)
FROM webhook_deliveries GROUP BY status ORDER BY status;

SELECT id, tenant_id, event_id, lease_until, attempt_count
FROM webhook_deliveries
WHERE status = 'PROCESSING' AND lease_until < now()
ORDER BY lease_until LIMIT 100;

SELECT delivery_id, replay_number, attempt_number, outcome,
       status_code, latency_ms, started_at, completed_at
FROM delivery_attempts
WHERE delivery_id = '<delivery-uuid>'::uuid
ORDER BY replay_number, attempt_number;
```

## Recovery testing

Worker integration tests use real PostgreSQL and Kafka containers plus WireMock. They exercise persisted transitions, duplicates, expired leases and transport failures. API integration tests additionally exercise authentication, tenant isolation, idempotency and Redis concurrency. Tests must fail when mandatory container infrastructure is unavailable rather than silently reporting the distributed checks as passed. Run `mvn verify` and inspect the generated Surefire and Failsafe reports under each module's `target/` directory for current results.

## Operational limits

Leases bound recovery delay but do not provide a distributed cancellation mechanism. Outage duration can exceed lease duration and cause extra queued messages; fencing prevents stale jobs from overwriting current state. The outbox polling interval, Kafka lag and scheduler interval all contribute to end-to-end delivery latency. A one-host Compose deployment does not survive host or disk loss without a tested external backup/restore plan.
