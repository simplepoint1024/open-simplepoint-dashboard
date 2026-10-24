CREATE TABLE simpoint_bootstrap_registry_revision (
    id varchar(16) PRIMARY KEY CHECK (id = 'catalog'),
    revision bigint NOT NULL CHECK (revision >= 0)
);
INSERT INTO simpoint_bootstrap_registry_revision(id, revision) VALUES ('catalog', 0);

CREATE TABLE simpoint_bootstrap_legacy_transfers (
    module_code varchar(128) NOT NULL,
    contribution_type varchar(64) NOT NULL,
    contribution_key varchar(160) NOT NULL,
    operation_id varchar(36) NOT NULL,
    target_scope varchar(128) NOT NULL,
    version varchar(64) NOT NULL,
    checksum varchar(256) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    PRIMARY KEY (module_code, contribution_type, contribution_key)
);
