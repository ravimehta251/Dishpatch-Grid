# Security model

## Identity and authorization

Passwords use BCrypt cost 12. Access JWTs use HS256 with a separate environment secret and expire after 15 minutes. Every authenticated request checks current membership and quota in PostgreSQL. Refresh tokens are random, hashed in the database, expire after seven days, and rotate under row lock. Refresh cookies are HttpOnly and SameSite=Strict; production sets Secure. The browser stores access tokens only in memory. Logout revokes the refresh token; existing access tokens remain usable until expiry while membership remains valid.

API keys are high-entropy `dg_` tokens. Only SHA-256 hashes and display prefixes are retained. They authenticate only POST `/api/v1/events`, never administrative reads or mutations. Revocation is checked on each request. OWNER/ADMIN manage keys, tenant settings and memberships; DEVELOPER also manages endpoints, publishes events and replays failures; VIEWER reads tenant data. The last owner cannot be removed. Owner/admin may adjust the tenant's development quota; introduce a platform-owned quota ceiling before using this as a billed SaaS.

Refresh/logout browser requests verify Origin against the request origin. Nginx preserves Host including port. In production only the trusted reverse proxy can reach the API's loopback-bound port; it overwrites forwarded headers. Do not enable forwarded-header trust on an API directly exposed to untrusted clients.

## Destination safety

Only HTTPS URLs without credentials/fragments and port 443 are accepted by default. Resolution rejects private, loopback, link-local, multicast, reserved and transition ranges. The Apache HTTP client's DNS resolver repeats the check at connection time and uses those checked addresses for the connection. Redirects and automatic HTTP retries are disabled; every attempt is recorded by the delivery engine. Response bodies are not stored or logged. Connections have connect/read/pool-acquisition timeouts and a 15-second cancellation watchdog. DNS lookup uses the operating system resolver; run with an egress firewall and bounded resolver behavior in production.

`DISPATCHGRID_ALLOW_PRIVATE_DESTINATIONS` is an exact hostname allowlist for development (Compose: `receiver`; host Java: `localhost`). It permits HTTP/private addresses for those names only. Link-local metadata addresses remain prohibited. Production override sets this list empty. Do not allow attacker-controlled domains in it.

## Signatures and receiver deduplication

Headers:

```text
X-DispatchGrid-Event-Id: stable event UUID
X-DispatchGrid-Delivery-Id: stable event/destination delivery UUID
X-DispatchGrid-Timestamp: Unix epoch seconds
X-DispatchGrid-Signature: v1=<lowercase hex HMAC>
```

The signed bytes are UTF-8 timestamp, one ASCII period, then the **exact raw HTTP body bytes**. The HMAC key is the UTF-8 signing-secret string shown when creating or rotating the endpoint; do not base64-decode that string. Verify with constant-time comparison, reject timestamps outside ±300 seconds, and atomically deduplicate the delivery ID with the receiver's business transaction.

Rotation keeps the prior encrypted key for 24 hours and sends comma-separated `v1=` signatures for both keys. The example receiver accepts any valid signature. Update receivers before the overlap ends. AES-256-GCM with random 96-bit nonces and authenticated context protects stored signing secrets. `ENCRYPTION_KEY` must be base64 of 32 random bytes, separate from `JWT_SECRET`. Back up that master key separately; losing it makes stored endpoint secrets unusable. Replacing it requires an explicit re-encryption migration.

## Operations

Requests are limited to one MiB including chunked bodies. Prometheus endpoints require a separate monitoring bearer token; health exposes no component details. Structured delivery logs include IDs and outcome, not payloads or credentials. Auth endpoints are Redis-rate-limited. Redis failures return 503; quota exhaustion returns 429 with Retry-After.

Compose generates random development secrets, binds published ports to loopback, and runs Java as a non-root user. Production requires TLS, restricted security groups, protected environment files, backups, patching, image scans and a dedicated secrets manager. The sample receiver's authenticated control API is development-only. Do not deploy it to a public network.

This is a tested portfolio implementation, not an independent security audit. The CI image scanner is a gate when run; a configured scanner is not proof of a clean scan.
