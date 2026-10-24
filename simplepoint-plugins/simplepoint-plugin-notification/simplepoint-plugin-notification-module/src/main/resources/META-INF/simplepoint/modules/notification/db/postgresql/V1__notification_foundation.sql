-- PostgreSQL baseline owned by notification. No business data or cross-module backfills.

create table simpoint_notification_receipts (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    read_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone,
    notification_id varchar(64) not null,
    user_id varchar(64) not null,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id),
    constraint uk_simpoint_notification_receipt unique (notification_id, user_id)
);

create table simpoint_system_notifications (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    expire_at timestamp(6) with time zone,
    publish_at timestamp(6) with time zone,
    published_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    version bigint,
    audience_type varchar(16) not null check ((audience_type in ('ALL','PLATFORM','TENANT','USER'))),
    priority varchar(16) not null check ((priority in ('LOW','NORMAL','HIGH','URGENT'))),
    status varchar(16) not null check ((status in ('DRAFT','PUBLISHED','REVOKED'))),
    category varchar(32) not null check ((category in ('ANNOUNCEMENT','SYSTEM','MAINTENANCE','SECURITY'))),
    audience_id varchar(64),
    source varchar(64),
    external_key varchar(200),
    title varchar(200) not null,
    link_url varchar(1024),
    content varchar(8000) not null,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id),
    constraint uk_simpoint_notification_external unique (source, external_key)
);

create index idx_simpoint_notification_receipt_user
   on simpoint_notification_receipts (user_id, read_at);

create index idx_simpoint_notification_delivery
   on simpoint_system_notifications (status, publish_at, expire_at);

create index idx_simpoint_notification_audience
   on simpoint_system_notifications (audience_type, audience_id);
