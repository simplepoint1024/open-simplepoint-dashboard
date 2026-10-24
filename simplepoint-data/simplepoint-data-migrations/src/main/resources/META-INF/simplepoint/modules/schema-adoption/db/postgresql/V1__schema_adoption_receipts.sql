create table sp_schema_adoptions_v1 (
    module_id varchar(128) primary key,
    operation_id varchar(36) not null,
    script_checksum char(64) not null,
    schema_checksum char(64) not null,
    evidence_checksum char(64) not null,
    state varchar(16) not null check (state in ('PREPARED', 'APPLIED')),
    created_at timestamp with time zone not null default current_timestamp
);
