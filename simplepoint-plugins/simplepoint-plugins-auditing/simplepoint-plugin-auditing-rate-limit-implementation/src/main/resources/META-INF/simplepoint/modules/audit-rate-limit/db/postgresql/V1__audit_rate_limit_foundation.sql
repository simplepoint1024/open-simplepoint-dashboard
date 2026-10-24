-- PostgreSQL baseline owned by audit-rate-limit. No business data or cross-module backfills.

create table simpoint_aud_endpoint_rate_limit_rules (
    enabled boolean not null,
    replenish_rate integer not null,
    requested_tokens integer not null,
    sort integer,
    burst_capacity bigint not null,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    http_method varchar(16),
    key_strategy varchar(32) not null,
    name varchar(128) not null,
    service_id varchar(128) not null,
    description varchar(512),
    path_pattern varchar(512) not null,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_aud_service_rate_limit_rules (
    enabled boolean not null,
    replenish_rate integer not null,
    requested_tokens integer not null,
    burst_capacity bigint not null,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    key_strategy varchar(32) not null,
    name varchar(128) not null,
    service_id varchar(128) not null unique,
    description varchar(512),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create index idx_endpoint_rate_limit_rule_service_id
   on simpoint_aud_endpoint_rate_limit_rules (service_id);

create index idx_endpoint_rate_limit_rule_path_pattern
   on simpoint_aud_endpoint_rate_limit_rules (path_pattern);

create index idx_endpoint_rate_limit_rule_enabled
   on simpoint_aud_endpoint_rate_limit_rules (enabled);

create index idx_endpoint_rate_limit_rule_sort
   on simpoint_aud_endpoint_rate_limit_rules (sort);

create index idx_service_rate_limit_rule_service_id
   on simpoint_aud_service_rate_limit_rules (service_id);

create index idx_service_rate_limit_rule_enabled
   on simpoint_aud_service_rate_limit_rules (enabled);
