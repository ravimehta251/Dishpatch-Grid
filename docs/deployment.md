# Deployment and operations

## Development

Run the setup script once, then `docker compose up -d --build`. It generates independent random passwords and keys in ignored `.env`; subsequent runs preserve them. The API and worker share PostgreSQL, Kafka and Redis but run separately. The receiver is explicitly allowed only in development. Persistent named volumes hold PostgreSQL, Kafka, Redis, receiver, Prometheus and Grafana data.

Environment variables:

| Variable | Meaning / default |
|---|---|
| DB_URL | JDBC PostgreSQL URL; Compose supplies service DNS |
| DB_USERNAME / DB_PASSWORD | Database identity and secret |
| JWT_SECRET | At least 32 bytes; generated independently |
| ENCRYPTION_KEY | Base64 of 32 random bytes; AES signing-secret master key |
| MONITORING_TOKEN | Separate bearer token, at least 32 characters |
| KAFKA_BOOTSTRAP_SERVERS | Compose `kafka:9092`; host `localhost:29092` |
| REDIS_HOST / REDIS_PORT | Redis address |
| COOKIE_SECURE | true by default; development Compose sets false |
| DISPATCHGRID_ALLOW_PRIVATE_DESTINATIONS | Empty by default; exact dev hostnames only |
| WORKER_CONCURRENCY | Kafka delivery consumer threads; default 8 |
| OUTBOX_POLL_MS | Delay between outbox batches; default 20 ms after measured tuning |
| KAFKA_PARTITIONS / KAFKA_REPLICATION_FACTOR | Topic settings; 12 / 1 locally |
| DB_POOL_SIZE | Hikari pool per process; default 16 |
| SCHEDULING_ENABLED / LISTENERS_ENABLED | Worker automation toggles for tests |
| GRAFANA_ADMIN_PASSWORD | Generated initial admin password |
| RECEIVER_CONTROL_TOKEN | Development receiver control authorization |
| API_PORT / DASHBOARD_PORT / GRAFANA_PORT / PROMETHEUS_PORT / RECEIVER_PORT | Local published ports |

HTTP connection/read timeouts are currently fixed at 3/10 seconds, with a 15-second total cancellation watchdog. Do not advertise a configuration flag unless the code consumes it.

## EC2 — optional, not deployed by this project setup

No AWS resources were created. Provision an Ubuntu host only after choosing and approving its cost. For an initial demonstration, allow at least 8 GB RAM for all local services. Expose 80/443, restrict SSH to an operator IP, and keep data/monitoring service ports private. A single-host deployment is not highly available.

1. Install supported Docker Engine/Compose and host Nginx using their official Ubuntu instructions. Create a non-root deployment user and check Docker group implications (Docker access grants host-level privilege).
2. Clone the reviewed repository into `/opt/dispatchgrid`. Generate `.env` with `bash scripts/setup-env.sh`, restrict it with `chmod 600 .env`, and replace development credentials with secrets from an operator-controlled store. Keep the encryption key backed up separately.
3. Configure DNS. Obtain a certificate with Certbot and adapt `infrastructure/nginx/dispatchgrid-https.conf` with the actual hostname. Run `nginx -t` before reload. The template proxies `/api/` directly to the API with sanitized forwarded headers.
4. Run `docker compose -f docker-compose.yml -f infrastructure/docker/compose.production.yml up -d --build`. The override enables Secure cookies, disables Swagger, clears private destination exceptions, and excludes the receiver unless explicitly requested.
5. Check `docker compose ps`, API/worker health, an HTTPS login, a signed webhook to a real public destination, and Prometheus scrape targets. Do not publish Redis, PostgreSQL, Kafka, Grafana or Prometheus on public interfaces.
6. For higher reliability use managed PostgreSQL with backups, a multi-broker Kafka cluster (replication factor >=3 with appropriate min ISR), protected Redis, TLS/service authentication and an egress firewall. Configure these external dependencies rather than claiming the development Compose topology is production HA.

## CI and optional deployment workflow

`ci.yml` builds Java, runs unit and Testcontainers integration tests, builds TypeScript, checks runtime npm advisories, runs receiver tests and validates Compose. It builds images, scans high/critical fixable vulnerabilities using Trivy, and publishes commit-SHA tags to GHCR only outside pull requests after successful checks.

`deploy.yml` is manual only. Configure a GitHub `production` environment with required reviewers and secrets `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`. It accepts a full reviewed commit SHA, checks out that revision on the pre-existing EC2 host, builds/restarts Compose and verifies API health. Pin the host key using a trusted channel, not an unverified runtime ssh-keyscan. No cloud credentials or SSH keys belong in Git.

These workflow files have not been executed on GitHub or EC2. A local build does not establish that cloud permissions, certificates or image registry configuration are correct.

## Backup and restore

Take consistent PostgreSQL backups, encrypt them off-host, and test restores. Example from a protected operator shell:

```bash
docker compose exec -T postgres pg_dump -U dispatchgrid -d dispatchgrid -Fc > dispatchgrid.dump
# Restore only into an explicitly selected empty recovery database:
docker compose exec -T postgres pg_restore -U dispatchgrid -d dispatchgrid_recovery < dispatchgrid.dump
```

Preserve Kafka data/retention and database backups as a coordinated recovery plan. Unpublished outbox records remain authoritative, but already-published event notifications cannot be reconstructed automatically after destructive Kafka loss without operator re-publication. Back up `.env` secrets separately from database dumps. Never rotate the encryption key by merely replacing the environment value.

## Rollback and migrations

Record the previous commit/image tags and take a database backup before deployment. Deploy additive schema changes compatible with the prior application. Roll back application images/commit when safe; do not auto-reverse Flyway SQL. For incompatible schema changes restore into a recovery database and plan data reconciliation. Flyway locks coordinate concurrent API/worker startup migrations.

## Retention

The worker removes up to 1,000 expired idempotency rows, old expired refresh tokens and published outbox rows per housekeeping cycle. Published outbox retention is seven days; expired refresh records retain a seven-day grace. Previous endpoint keys are cleared after their rotation overlap. Delivery/event/attempt/audit history has no destructive automatic retention; archive with tenant/legal retention requirements and dependency-aware transactions before deleting it.

## Local engine troubleshooting

If Docker fails before the engine starts, inspect Docker Desktop's startup logs and resolve the host issue before running Compose or Testcontainers. Host-specific runtime repairs are outside the project setup scripts. A Docker factory reset can erase unrelated volumes; use project-scoped container commands for routine development cleanup.
