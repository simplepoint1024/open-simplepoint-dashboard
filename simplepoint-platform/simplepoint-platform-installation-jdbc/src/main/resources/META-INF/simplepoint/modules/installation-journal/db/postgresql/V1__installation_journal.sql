CREATE TABLE simpoint_installation_targets (
    target_id varchar(128) PRIMARY KEY,
    generation bigint NOT NULL CHECK (generation >= 0),
    current_operation varchar(36)
);
CREATE TABLE simpoint_installation_operations (
    operation_id varchar(36) PRIMARY KEY,
    target_id varchar(128) NOT NULL REFERENCES simpoint_installation_targets(target_id),
    generation bigint NOT NULL CHECK (generation > 0),
    plan_fingerprint varchar(64) NOT NULL,
    release_fingerprint varchar(64) NOT NULL,
    complete boolean NOT NULL DEFAULT false,
    created_at timestamp with time zone NOT NULL DEFAULT current_timestamp,
    completed_at timestamp with time zone,
    UNIQUE(target_id,generation)
);
CREATE TABLE simpoint_installation_steps (
    operation_id varchar(36) NOT NULL REFERENCES simpoint_installation_operations(operation_id),
    step_index integer NOT NULL CHECK (step_index BETWEEN 0 AND 511),
    step_id varchar(128) NOT NULL,
    adapter_kind varchar(128) NOT NULL,
    adapter_version integer NOT NULL CHECK (adapter_version > 0),
    input_fingerprint varchar(64) NOT NULL,
    retry_mode varchar(32) NOT NULL CHECK (retry_mode IN ('RECONCILE_ONLY','DURABLE_IDEMPOTENT')),
    state varchar(16) NOT NULL CHECK (state IN ('PENDING','RUNNING','FAILED','COMPLETE')),
    attempt_id varchar(36),
    receipt_fingerprint varchar(64),
    PRIMARY KEY(operation_id,step_index),
    UNIQUE(operation_id,step_id),
    CHECK ((state='PENDING' AND attempt_id IS NULL AND receipt_fingerprint IS NULL)
        OR (state IN ('RUNNING','FAILED') AND attempt_id IS NOT NULL AND receipt_fingerprint IS NULL)
        OR (state='COMPLETE' AND attempt_id IS NOT NULL AND receipt_fingerprint IS NOT NULL))
);
CREATE TABLE simpoint_installation_attempts (
    attempt_id varchar(36) PRIMARY KEY,
    operation_id varchar(36) NOT NULL,
    step_index integer NOT NULL,
    state varchar(32) NOT NULL CHECK (state IN ('RUNNING','FAILED','COMPLETE','RETRY_AUTHORIZED')),
    receipt_fingerprint varchar(64),
    error_code varchar(32),
    retirement_evidence varchar(64),
    started_at timestamp with time zone NOT NULL DEFAULT current_timestamp,
    completed_at timestamp with time zone,
    FOREIGN KEY(operation_id,step_index) REFERENCES simpoint_installation_steps(operation_id,step_index)
);
