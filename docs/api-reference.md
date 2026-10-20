# REST API reference

Base URL: `/api/v1`. Swagger/OpenAPI is available at `/swagger-ui.html` and `/v3/api-docs` in development. Errors use RFC 9457 Problem Details with a correlation ID. All lists use zero-based `page` and `size` (1–100) and return `{items,page,size,total}`. Date/time values are UTC ISO-8601. UUIDs identify resources.

## Authentication

`POST /auth/register` takes `{tenantName,email,password}`; password minimum is 12 characters. Returns 201 with `{accessToken,expiresIn:900,user:{id,email},tenant:{id,name,role,rateLimitPerMinute}}` and a refresh cookie. `POST /auth/login` takes `{email,password,tenantId?}` and returns the same session. `POST /auth/refresh` rotates the refresh cookie. `POST /auth/logout` revokes it. `GET /auth/me` returns current user and tenant. Send JWTs as `Authorization: Bearer <token>`.

## Endpoints and keys

| Method/path | Request / behavior |
|---|---|
| POST /api-keys | `{name}` → 201, `{id,name,keyPrefix,createdAt,key}`; raw key shown once |
| GET /api-keys | Paginated key metadata; OWNER/ADMIN |
| DELETE /api-keys/{id} | Revoke immediately, 204 |
| POST /endpoints | Input below → 201, resource plus one-time `signingSecret` |
| GET /endpoints | Paginated endpoints excluding soft-deleted rows |
| GET /endpoints/{id} | Endpoint and subscriptions, no secret |
| PUT /endpoints/{id} | Replace mutable configuration |
| DELETE /endpoints/{id} | Soft-delete and disable, 204; history remains |
| POST /endpoints/{id}/rotate-secret | One-time secret response; 24-hour overlap |

```json
{"url":"https://example.com/webhooks","description":"Order service","active":true,"subscribedEvents":["order.created"],"maxAttempts":5,"retryBaseSeconds":10,"retryMaxSeconds":600}
```

Subscriptions accept exact event types or `*`. Default retry policy, if omitted, is 5 attempts, 10-second base, 600-second cap. Configure `active` explicitly. Local mock URL for Compose is `http://receiver:8090/webhook`.

## Events

`POST /events` accepts publisher API keys or developer/admin JWTs and requires `Idempotency-Key` (1–200 characters). Body: `{eventType,payload}`; payload must be an object. Returns 202 with `{id,eventType,correlationId,createdAt,duplicate}`. Keys are tenant scoped for 24 hours. A matching canonical JSON request returns the original event; different content returns 409. Quota limits count all publish requests, including duplicate retries. Redis unavailability returns 503.

`GET /events?eventType=order.created&page=0&size=20` lists metadata. `GET /events/{id}` includes the payload. Foreign-tenant IDs return 404.

## Deliveries

`GET /deliveries` accepts `status`, `eventId`, `endpointId`, `page`, `size`. `GET /deliveries/{id}` includes payload and attempts. `GET /deliveries/{id}/attempts` returns an array ordered by replay and attempt number, including HTTP status, latency, error classification, timestamps and signature.

`POST /deliveries/{id}/replay` is valid only for FAILED/EXHAUSTED. It resets the current attempt budget, increments replay count, preserves old attempts and keeps the stable delivery ID. Other states return 409. Receiver deduplication may correctly suppress an already-processed replay.

`GET /dead-letters` lists historical terminal failures. It retains entries after replay, so historical dead-letter count can differ from the number of currently failed deliveries.

## Analytics and tenant management

- `GET /analytics/overview`: eventsReceived, successfulDeliveries, failedDeliveries, pendingDeliveries, successRate, averageDeliveryTimeMs, retryCount, deadLetterCount, activeEndpoints. Success percentage includes pending records in the denominator; latency is the mean of HTTP attempts.
- `GET /analytics/timeseries` or `/analytics/deliveries`: 14 daily cohorts with events, delivered and failed counts. Delivery cohorts use delivery creation time, with current status.
- `GET /analytics/failures`: top terminal failure reasons.
- `GET /tenant`, `PUT /tenant` with `{name,rateLimitPerMinute}` (1–60000).
- `GET /tenant/members`, `PUT /tenant/members` with `{email,password?,role}`, `DELETE /tenant/members/{userId}`. New users require an initial password; existing users keep their password.

Metrics: `/actuator/prometheus` with `Authorization: Bearer <MONITORING_TOKEN>`. Minimal `/actuator/health` is public. API keys cannot read metrics.
