CREATE TABLE simpoint_resource_registration_operations (
    operation_id varchar(36) PRIMARY KEY,
    fingerprint varchar(64) NOT NULL CHECK (length(fingerprint) = 64),
    created_at timestamp(6) with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE simpoint_resource_registration_deployments (
    owner varchar(128) NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    operation_id varchar(36) NOT NULL,
    client_id varchar(256),
    created_at timestamp(6) with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner, generation),
    UNIQUE (owner, operation_id),
    UNIQUE (client_id),
    FOREIGN KEY (operation_id) REFERENCES simpoint_resource_registration_operations (operation_id),
    CHECK (length(owner) > 0),
    CHECK (client_id IS NULL OR length(client_id) > 0)
);
