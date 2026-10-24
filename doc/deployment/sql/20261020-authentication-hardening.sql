-- PostgreSQL. Apply with the migration identity before the new authentication binaries.
ALTER TABLE simpoint_ac_users ALTER COLUMN two_factor_secret TYPE varchar(512);
ALTER TABLE simpoint_ac_users ADD COLUMN IF NOT EXISTS two_factor_pending_until timestamptz;
-- Also apply META-INF/simplepoint/events/schema-v1.sql to BOTH authorization and auditing
-- databases before enabling their durable login-audit producer/subscription.
