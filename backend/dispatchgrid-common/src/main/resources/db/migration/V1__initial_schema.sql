CREATE TABLE tenants (
 id UUID PRIMARY KEY, name VARCHAR(120) NOT NULL,
 rate_limit_per_minute INTEGER NOT NULL DEFAULT 600 CHECK (rate_limit_per_minute BETWEEN 1 AND 60000),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE users (
 id UUID PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE tenant_memberships (
 tenant_id UUID NOT NULL REFERENCES tenants(id), user_id UUID NOT NULL REFERENCES users(id),
 role VARCHAR(20) NOT NULL CHECK (role IN ('OWNER','ADMIN','DEVELOPER','VIEWER')),
 PRIMARY KEY (tenant_id,user_id)
);
CREATE TABLE refresh_tokens (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), tenant_id UUID NOT NULL REFERENCES tenants(id),
 token_hash VARCHAR(64) NOT NULL UNIQUE, expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE api_keys (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), name VARCHAR(100) NOT NULL,
 key_prefix VARCHAR(20) NOT NULL, key_hash VARCHAR(64) NOT NULL UNIQUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), revoked_at TIMESTAMPTZ
);
CREATE INDEX api_keys_tenant_idx ON api_keys(tenant_id,created_at DESC);
CREATE TABLE webhook_endpoints (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), url VARCHAR(2048) NOT NULL,
 description VARCHAR(500) NOT NULL DEFAULT '', active BOOLEAN NOT NULL DEFAULT true, deleted_at TIMESTAMPTZ,
 signing_secret TEXT NOT NULL, previous_secret TEXT, previous_secret_expires_at TIMESTAMPTZ,
 max_attempts INTEGER NOT NULL DEFAULT 5 CHECK (max_attempts BETWEEN 1 AND 10),
 retry_base_seconds INTEGER NOT NULL DEFAULT 10 CHECK (retry_base_seconds BETWEEN 1 AND 3600),
 retry_max_seconds INTEGER NOT NULL DEFAULT 600 CHECK (retry_max_seconds BETWEEN 1 AND 86400),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE (tenant_id,id), CHECK (retry_max_seconds >= retry_base_seconds)
);
CREATE TABLE webhook_subscriptions (
 endpoint_id UUID NOT NULL REFERENCES webhook_endpoints(id), event_type VARCHAR(120) NOT NULL,
 PRIMARY KEY(endpoint_id,event_type)
);
CREATE INDEX endpoints_tenant_idx ON webhook_endpoints(tenant_id,created_at DESC) WHERE deleted_at IS NULL;
CREATE TABLE events (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), event_type VARCHAR(120) NOT NULL,
 payload JSONB NOT NULL, correlation_id VARCHAR(100) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id)
);
CREATE INDEX events_tenant_created_idx ON events(tenant_id,created_at DESC);
CREATE INDEX events_tenant_type_idx ON events(tenant_id,event_type,created_at DESC);
CREATE TABLE idempotency_records (
 tenant_id UUID NOT NULL REFERENCES tenants(id), idempotency_key VARCHAR(200) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, event_id UUID NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(tenant_id,idempotency_key), FOREIGN KEY(tenant_id,event_id) REFERENCES events(tenant_id,id)
);
CREATE INDEX idempotency_expiry_idx ON idempotency_records(expires_at);
CREATE TABLE outbox_messages (
 id UUID PRIMARY KEY, topic VARCHAR(100) NOT NULL, message_key VARCHAR(100) NOT NULL, payload TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), published_at TIMESTAMPTZ,
 lease_token UUID, lease_until TIMESTAMPTZ, attempts INTEGER NOT NULL DEFAULT 0, last_error VARCHAR(500)
);
CREATE INDEX outbox_pending_idx ON outbox_messages(created_at) WHERE published_at IS NULL;
CREATE TABLE webhook_deliveries (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), event_id UUID NOT NULL, endpoint_id UUID NOT NULL,
 status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSING','RETRY_SCHEDULED','DELIVERED','FAILED','EXHAUSTED')),
 attempt_count INTEGER NOT NULL DEFAULT 0, replay_count INTEGER NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(), lease_token UUID, lease_until TIMESTAMPTZ,
 last_status_code INTEGER, last_error VARCHAR(500), delivered_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(event_id,endpoint_id), UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,event_id) REFERENCES events(tenant_id,id),
 FOREIGN KEY(tenant_id,endpoint_id) REFERENCES webhook_endpoints(tenant_id,id)
);
CREATE INDEX delivery_due_idx ON webhook_deliveries(next_attempt_at) WHERE status IN ('PENDING','RETRY_SCHEDULED');
CREATE INDEX delivery_lease_idx ON webhook_deliveries(lease_until) WHERE status='PROCESSING';
CREATE INDEX delivery_tenant_idx ON webhook_deliveries(tenant_id,created_at DESC);
CREATE INDEX delivery_endpoint_idx ON webhook_deliveries(tenant_id,endpoint_id,created_at DESC);
CREATE TABLE delivery_attempts (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, delivery_id UUID NOT NULL,
 attempt_number INTEGER NOT NULL, replay_number INTEGER NOT NULL DEFAULT 0,
 status_code INTEGER, latency_ms BIGINT NOT NULL, outcome VARCHAR(40) NOT NULL, error VARCHAR(500),
 signature VARCHAR(200), started_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,delivery_id) REFERENCES webhook_deliveries(tenant_id,id),
 UNIQUE(delivery_id,replay_number,attempt_number)
);
CREATE TABLE dead_letters (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, delivery_id UUID NOT NULL, replay_number INTEGER NOT NULL,
 reason VARCHAR(500) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,delivery_id) REFERENCES webhook_deliveries(tenant_id,id), UNIQUE(delivery_id,replay_number)
);
CREATE INDEX dead_letters_tenant_idx ON dead_letters(tenant_id,created_at DESC);
CREATE TABLE audit_logs (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), actor_id UUID,
 action VARCHAR(100) NOT NULL, resource_id UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
