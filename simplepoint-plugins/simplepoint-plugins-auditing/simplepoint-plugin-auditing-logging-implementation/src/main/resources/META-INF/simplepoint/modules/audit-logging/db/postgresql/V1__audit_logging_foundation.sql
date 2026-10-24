-- PostgreSQL baseline owned by audit-logging. No business data or cross-module backfills.

create table simpoint_aud_error_logs (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    occurred_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone,
    level varchar(16) not null,
    client_ip varchar(64),
    context_id varchar(64),
    source_service varchar(64),
    tenant_id varchar(64),
    thread_name varchar(128),
    user_id varchar(128),
    exception_type varchar(256),
    logger_name varchar(256),
    request_uri varchar(512),
    exception_message varchar(4000),
    message varchar(4000),
    stack_trace varchar(16000),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_aud_login_logs (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    login_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone,
    status varchar(16) not null,
    login_type varchar(32),
    client_ip varchar(64),
    context_id varchar(64),
    remote_address varchar(64),
    source_service varchar(64),
    tenant_id varchar(64),
    user_id varchar(64),
    authentication_type varchar(128),
    display_name varchar(128),
    session_id varchar(128),
    username varchar(128),
    failure_reason varchar(512),
    forwarded_for varchar(512),
    request_uri varchar(512),
    user_agent varchar(2048),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_aud_resource_grant_logs (
    target_count integer,
    changed_at timestamp(6) with time zone not null,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    action varchar(32) not null,
    subject_type varchar(32) not null,
    target_type varchar(32) not null,
    change_type varchar(64) not null,
    context_id varchar(64),
    source_service varchar(64),
    tenant_id varchar(64),
    operator_id varchar(128),
    subject_id varchar(128) not null,
    subject_label varchar(256),
    description varchar(1024),
    target_summary varchar(2048),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create index idx_error_log_occurred_at
   on simpoint_aud_error_logs (occurred_at);

create index idx_error_log_level
   on simpoint_aud_error_logs (level);

create index idx_error_log_source_service
   on simpoint_aud_error_logs (source_service);

create index idx_error_log_tenant_id
   on simpoint_aud_error_logs (tenant_id);

create index idx_error_log_logger_name
   on simpoint_aud_error_logs (logger_name);

create index idx_error_log_exception_type
   on simpoint_aud_error_logs (exception_type);

create index idx_login_log_username
   on simpoint_aud_login_logs (username);

create index idx_login_log_status
   on simpoint_aud_login_logs (status);

create index idx_login_log_tenant_id
   on simpoint_aud_login_logs (tenant_id);

create index idx_login_log_login_at
   on simpoint_aud_login_logs (login_at);

create index idx_resource_grant_log_changed_at
   on simpoint_aud_resource_grant_logs (changed_at);

create index idx_resource_grant_log_change_type
   on simpoint_aud_resource_grant_logs (change_type);

create index idx_resource_grant_log_action
   on simpoint_aud_resource_grant_logs (action);

create index idx_resource_grant_log_tenant_id
   on simpoint_aud_resource_grant_logs (tenant_id);

create index idx_resource_grant_log_operator_id
   on simpoint_aud_resource_grant_logs (operator_id);
