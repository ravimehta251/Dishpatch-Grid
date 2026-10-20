# Sample receiver

This Python standard-library receiver verifies `HMAC-SHA256(secret, timestamp + "." + raw_body)`, accepts comma-separated `v1=<lowercase hex>` signatures during secret rotation, rejects timestamps more than five minutes in either direction, and persists successfully processed delivery IDs in SQLite. The exact bytes must be verified before parsing. Synchronize clocks with NTP.

SQLite's unique delivery ID prevents repeat processing in this example. Real business mutations belong in the same transaction as the deduplication insert, or in another durable outbox. A check followed by an unrelated side effect cannot guarantee deduplication. Keep dedupe records for at least the sender's maximum replay horizon; old manual replays still carry a stable ID.

`docker compose up -d receiver` starts the local fixture. `scripts/demo.py` configures its secret after creating an endpoint. Dev control is unavailable unless `ENABLE_DEV_CONTROL=true` and a strong `RECEIVER_CONTROL_TOKEN` is provided. Both `/control` and `/received` require that bearer token. Never enable this control plane in an Internet-facing receiver.

Control fields: `signingSecret`, `failureCount` (`0` healthy, `-1` always fail), `status` (`400`–`599`), `delayMs` (`0`–`30000`). It stores hashes and IDs, not payloads. Health is `/health`; destination is `/webhook`. For production, supply `WEBHOOK_SIGNING_SECRET`, omit dev control, terminate HTTPS, add bounded request concurrency, and integrate business processing.

Run verification tests: `python -m unittest discover -s examples/sample-webhook-receiver -v`.
