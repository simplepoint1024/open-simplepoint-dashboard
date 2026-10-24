CREATE TABLE simpoint_dictionary_legacy_adoptions (
    module_code varchar(128) PRIMARY KEY,
    operation_id varchar(36) NOT NULL,
    contribution_key varchar(128) NOT NULL,
    version varchar(64) NOT NULL,
    legacy_checksum varchar(256) NOT NULL,
    source_fingerprint varchar(64) NOT NULL,
    declaration_fingerprint varchar(64) NOT NULL,
    data_fingerprint varchar(64) NOT NULL,
    dictionary_count integer NOT NULL CHECK (dictionary_count BETWEEN 1 AND 64),
    item_count integer NOT NULL CHECK (item_count BETWEEN 0 AND 8192)
);
