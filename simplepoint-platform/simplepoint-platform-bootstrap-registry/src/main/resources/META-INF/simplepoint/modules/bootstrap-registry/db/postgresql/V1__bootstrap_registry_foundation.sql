-- PostgreSQL schema owned by bootstrap-registry.
create table simpoint_platform_contribution (
    applied_at timestamp(6),
    started_at timestamp(6),
    updated_at timestamp(6),
    status varchar(32),
    contribution_type varchar(64) not null,
    version varchar(64),
    module_code varchar(128) not null,
    service_name varchar(128) not null,
    contribution_key varchar(160) not null,
    checksum varchar(256),
    error varchar(1024),
    primary key (contribution_type, module_code, service_name, contribution_key)
);
