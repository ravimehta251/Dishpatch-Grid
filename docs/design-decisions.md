# Design decisions

## 1. Modular monolith with a separate worker executable

The API and worker share the domain schema and security primitives but run as separate Spring Boot processes. This supports independent deployment/concurrency and keeps external webhook latency out of API requests. A service per feature would introduce extra network failures and coordination without improving the initial product's correctness.

## 2. PostgreSQL is authoritative; Kafka transports work

The database owns accepted events, idempotency, delivery state, retry deadlines and attempts. Kafka provides asynchronous buffering, consumer groups and partitioned throughput. Losing a consumer process does not lose retry state. Redis is deliberately outside the durable-correctness boundary.

## 3. Transactional outbox instead of a database/Kafka dual write

An event and publication intention commit in the same database transaction. The publisher waits for a broker acknowledgement before recording success. Duplicate publication is acceptable; missing accepted events is not. This avoids requiring distributed transactions or pretending Kafka transactions can atomically commit an arbitrary PostgreSQL write and external HTTP call.

## 4. Explicit SQL for distributed claims, JPA for identity

Spring Data JPA/Hibernate is useful for simple identity persistence. Claiming work requires explicit PostgreSQL locking, partial-index-aware predicates and conditional updates. `JdbcTemplate` makes `FOR UPDATE SKIP LOCKED`, `ON CONFLICT`, and fencing predicates visible and reviewable. Both persistence styles participate in Spring-managed local transactions.

## 5. At-least-once HTTP delivery

A destination can commit its business change before DispatchGrid records the response. A crash in that gap requires another attempt. Stable delivery IDs and a receiver's own atomic deduplication record address this ambiguity. The system does not claim exactly-once delivery to arbitrary HTTP endpoints.

## 6. PostgreSQL retry deadlines and expiring leases

Retries are indexed database rows, not delayed in-memory tasks. A distributed scheduler claims due rows and emits jobs through the same outbox. Leases recover abandoned work; random fencing tokens reject old jobs and old completions. Lease duration must exceed the bounded HTTP operation and persistence margin. An expiry is not proof that the old process has stopped, so receiver deduplication remains necessary.

## 7. Independent destinations and bounded concurrency

One event can produce several deliveries, each with its own attempt budget and state. Consumers and HTTP connections have configured limits. A destination timeout consumes a bounded worker slot. Queue and concurrency settings must be tuned against database pool size and measured HTTP latency.

## 8. Stateless short-lived access tokens, revocable credentials

Dashboard access uses signed JWTs and role/tenant authorization. Refresh tokens are persisted as hashes and rotated. Publishing API keys are random credentials stored as hashes, with explicit revocation. Revoking a refresh token prevents refresh; a previously issued access JWT remains valid until expiry unless the application separately checks current membership/account state.

## 9. Encrypted signing secrets

HMAC signing requires the original secret at delivery time, so a one-way password hash is insufficient. Authenticated encryption protects stored secrets using a separate environment-provided master key. Key management is an operational responsibility: database backups and the encryption key must have separate access control, and a production secret manager is preferred.

## 10. SSRF controls at validation and connection time

Destination URLs are restricted by scheme, credentials, host and resolved addresses. Redirects are not followed. DNS is validated when establishing connections rather than trusting only registration-time resolution. Local test destinations require explicit allowlisting and must be excluded from production configuration. Network egress policy provides an additional independent defense.

## 11. Atomic Redis quota enforcement

An atomic Redis script coordinates the per-tenant ingestion limit across API replicas. A Redis outage fails ingestion conservatively instead of silently removing the quota. This affects availability but prevents unbounded admission during a dependency incident. Delivery concurrency uses a separate limit and does not consume ingestion tokens.

## 12. Observability without payload disclosure or cardinality explosion

Logs carry correlation, event, delivery, attempt and status context, but omit credentials and payloads. Metrics label bounded categories such as outcome; tenant/event IDs remain log/query fields. Latency histograms support p50/p95/p99 and should be evaluated alongside backlog and error rates.

## 13. Deployment topology

Docker Compose is the reproducible development target. Production deployments require protected secrets, TLS, off-host backups and resilient data services. The deployment guide describes the single-host limitations and the configuration required for an EC2 host.

## Dependency baseline

Java 21, Spring Boot 3.5.x, PostgreSQL 16, Kafka, Redis, Flyway, Micrometer, Testcontainers, WireMock and JUnit 5 form the backend baseline. Exact versions are pinned in Maven and container configuration. The frontend uses React, TypeScript, Vite, Tailwind, React Router, TanStack Query and Recharts. Compatible dependency resolution and executed verification, rather than the version list alone, establish a working build.
