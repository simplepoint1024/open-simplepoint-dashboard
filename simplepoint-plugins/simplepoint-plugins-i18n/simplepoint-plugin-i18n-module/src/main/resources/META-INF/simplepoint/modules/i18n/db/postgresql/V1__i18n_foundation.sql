-- PostgreSQL schema owned by i18n.
create table simpoint_i18n_countries (
    enabled boolean not null,
    numeric_code integer not null unique,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    iso_code2 varchar(32) not null unique,
    iso_code3 varchar(32) not null unique,
    name_english varchar(32) not null unique,
    name_native varchar(32) not null unique,
    continent varchar(255),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    currency_code varchar(255),
    currency_name varchar(255),
    currency_numeric varchar(255),
    currency_symbol varchar(255),
    default_timezone varchar(255),
    flag_icon varchar(255),
    id varchar(255) not null,
    phone_code varchar(255),
    sub_region varchar(255),
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_i18n_languages (
    enabled boolean,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    code varchar(255),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    date_format varchar(255),
    description varchar(255),
    id varchar(255) not null,
    locale varchar(255),
    name_english varchar(255),
    name_native varchar(255),
    text_direction varchar(255),
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_i18n_messages (
    global boolean not null,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    locale varchar(128) not null,
    namespace varchar(128) not null,
    code varchar(256) not null,
    description varchar(2048),
    message varchar(2048) not null,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id),
    unique (namespace, code, locale)
);

create table simpoint_i18n_namespaces (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    code varchar(255),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    description varchar(255),
    id varchar(255) not null,
    module varchar(255),
    name varchar(255),
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_i18n_regions (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    country_code varchar(32) not null unique,
    code varchar(255),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    level varchar(255),
    name_english varchar(255),
    name_native varchar(255),
    parent_id varchar(255),
    postal_code varchar(255),
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_i18n_timezones (
    enabled boolean,
    is_dst boolean,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    code varchar(255),
    country_code varchar(255),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    name_english varchar(255),
    name_native varchar(255),
    updated_by varchar(255),
    utc_offset varchar(255),
    primary key (id)
);
