-- PostgreSQL baseline owned by personal. No business data or cross-module backfills.

create table simpoint_personal_item_events (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    version bigint,
    action varchar(32) not null,
    item_id varchar(64) not null,
    owner_id varchar(64) not null,
    workspace varchar(160) not null,
    detail varchar(2000),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_personal_items (
    archived boolean not null,
    pinned boolean not null,
    priority integer not null,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    due_at timestamp(6) with time zone,
    start_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    version bigint,
    status varchar(24) not null,
    category varchar(60),
    owner_id varchar(64) not null,
    workspace varchar(160) not null,
    title varchar(200) not null,
    description varchar(8000),
    checklist_json text not null,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_personal_messages (
    archived boolean not null,
    priority integer not null,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    read_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    version bigint,
    owner_id varchar(64) not null,
    source varchar(64) not null,
    source_key varchar(160) not null,
    workspace varchar(160) not null,
    title varchar(200) not null,
    link_path varchar(512),
    content varchar(8000),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id),
    constraint uk_personal_messages_source unique (owner_id, workspace, source, source_key)
);

create table simpoint_personal_todos (
    priority integer not null,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    due_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    version bigint,
    status varchar(24) not null,
    owner_id varchar(64) not null,
    source varchar(64) not null,
    source_key varchar(160) not null,
    workspace varchar(160) not null,
    title varchar(200) not null,
    link_path varchar(512),
    content varchar(8000),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id),
    constraint uk_personal_todos_source unique (owner_id, workspace, source, source_key)
);

create index idx_personal_item_event
   on simpoint_personal_item_events (owner_id, workspace, item_id, created_at);

create index idx_personal_item_owner
   on simpoint_personal_items (owner_id, workspace, deleted_at, archived, status, due_at);

create index idx_personal_item_updated
   on simpoint_personal_items (owner_id, workspace, updated_at);

create index idx_personal_messages_owner
   on simpoint_personal_messages (owner_id, workspace, created_at);

create index idx_personal_messages_unread
   on simpoint_personal_messages (owner_id, workspace, deleted_at, archived, read_at, created_at);

create index idx_personal_todos_owner
   on simpoint_personal_todos (owner_id, workspace, created_at);

create index idx_personal_todos_pending
   on simpoint_personal_todos (owner_id, workspace, deleted_at, status, due_at);
