-- PostgreSQL: safe to reapply; one independent event store per service owner.
-- Apply once per service database before enabling simplepoint.events.enabled.
-- PostgreSQL, MySQL 8 and H2. Do not remove inbox identities while broker/replay may redeliver.
CREATE TABLE IF NOT EXISTS sp_event_jobs_v1 (
  id VARCHAR(36) PRIMARY KEY,
  owner VARCHAR(96) NOT NULL,
  direction VARCHAR(8) NOT NULL,
  subscription VARCHAR(96) NOT NULL,
  event_id VARCHAR(36) NOT NULL,
  envelope TEXT NOT NULL,
  state VARCHAR(16) NOT NULL,
  attempts INTEGER NOT NULL,
  next_at BIGINT NOT NULL,
  lease_token VARCHAR(36),
  lease_until BIGINT NOT NULL,
  last_error VARCHAR(160),
  created_at BIGINT NOT NULL,
  CONSTRAINT uq_sp_event_identity UNIQUE (owner, direction, subscription, event_id)
);
CREATE INDEX IF NOT EXISTS ix_sp_event_pending ON sp_event_jobs_v1 (owner, direction, state, next_at);
CREATE INDEX IF NOT EXISTS ix_sp_event_lease ON sp_event_jobs_v1 (owner, direction, state, lease_until);
CREATE TABLE IF NOT EXISTS sp_event_replays_v1 (
  id VARCHAR(36) PRIMARY KEY,
  job_id VARCHAR(36) NOT NULL,
  actor VARCHAR(160) NOT NULL,
  reason VARCHAR(512) NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS sp_event_quarantine_v1 (
  id VARCHAR(36) PRIMARY KEY,
  owner VARCHAR(96) NOT NULL,
  subscription VARCHAR(96) NOT NULL,
  body_base64 TEXT NOT NULL,
  original_bytes INTEGER NOT NULL,
  created_at BIGINT NOT NULL
);
