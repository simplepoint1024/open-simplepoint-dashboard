CREATE TABLE simpoint_dictionary_registration_operations (
    operation_id varchar(36) PRIMARY KEY,
    fingerprint varchar(64) NOT NULL
);

CREATE TABLE simpoint_dictionary_registration_approvals (
    module_code varchar(128) NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    operation_id varchar(36) NOT NULL REFERENCES simpoint_dictionary_registration_operations(operation_id),
    client_id varchar(256) UNIQUE,
    contribution_key varchar(128),
    version varchar(64),
    fingerprint varchar(64),
    applied boolean NOT NULL DEFAULT false,
    PRIMARY KEY (module_code, generation),
    UNIQUE (module_code, operation_id),
    CHECK ((client_id IS NULL AND contribution_key IS NULL AND version IS NULL AND fingerprint IS NULL AND applied=false)
        OR (client_id IS NOT NULL AND contribution_key IS NOT NULL AND version IS NOT NULL AND fingerprint IS NOT NULL))
);

CREATE TABLE simpoint_dictionary_registration_owners (
    dictionary_code varchar(128) PRIMARY KEY,
    module_code varchar(128) NOT NULL
);
