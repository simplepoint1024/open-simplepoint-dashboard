CREATE TABLE simpoint_dna_ownership_adoptions (
    operation_id varchar(36) PRIMARY KEY,
    intent_fingerprint varchar(64) NOT NULL,
    review_fingerprint varchar(64) NOT NULL,
    previous_inventory_fingerprint varchar(64) NOT NULL,
    adopted_inventory_fingerprint varchar(64) NOT NULL,
    binding_count integer NOT NULL CHECK (binding_count >= 0 AND binding_count <= 10000),
    completed_at timestamp with time zone NOT NULL
);
