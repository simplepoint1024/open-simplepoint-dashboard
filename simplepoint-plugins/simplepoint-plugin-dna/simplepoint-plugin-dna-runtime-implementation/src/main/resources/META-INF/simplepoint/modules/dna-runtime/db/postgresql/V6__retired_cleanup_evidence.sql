CREATE TABLE simpoint_dna_retired_cleanup_evidence (
    model_id varchar(36) PRIMARY KEY REFERENCES simpoint_dna_physical_models(id),
    binding_id varchar(36) NOT NULL UNIQUE REFERENCES simpoint_dna_physical_bindings(id),
    evidence_checksum varchar(64) NOT NULL,
    operation_id varchar(36) NOT NULL,
    intent_fingerprint varchar(64) NOT NULL,
    completed_at timestamp with time zone NOT NULL
);
