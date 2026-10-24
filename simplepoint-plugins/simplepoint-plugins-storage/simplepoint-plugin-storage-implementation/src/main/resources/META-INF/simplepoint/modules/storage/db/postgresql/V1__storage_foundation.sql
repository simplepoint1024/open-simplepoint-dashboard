-- PostgreSQL baseline owned by storage. No business data or cross-module backfills.

create table simpoint_storage_objects (
    content_length bigint,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    provider_type varchar(32) not null check ((provider_type in ('MINIO','S3','ALIYUN_OSS','TENCENT_COS','QINIU_KODO','CEPH'))),
    provider_code varchar(64) not null,
    bucket varchar(128) not null,
    source_service_name varchar(128),
    object_key varchar(768) not null,
    access_url varchar(1024),
    content_type varchar(255),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    etag varchar(255),
    id varchar(255) not null,
    original_file_name varchar(255) not null,
    tenant_id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_storage_provider_configs (
    default_provider boolean not null,
    enabled boolean not null,
    path_style_access boolean,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    platform_type varchar(32) not null check ((platform_type in ('MINIO','S3','ALIYUN_OSS','TENCENT_COS','QINIU_KODO','CEPH'))),
    code varchar(64) not null,
    name varchar(128) not null,
    region varchar(128),
    access_key varchar(512) not null,
    base_path varchar(512),
    description varchar(512),
    endpoint varchar(2048),
    public_base_url varchar(2048),
    secret_key_ciphertext varchar(4096),
    bucket varchar(255) not null,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_storage_tenant_quotas (
    enabled boolean,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    quota_bytes bigint,
    updated_at timestamp(6) with time zone,
    tenant_id varchar(64) not null,
    description varchar(512),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create index idx_simpoint_storage_provider_code
   on simpoint_storage_provider_configs (code);

create index idx_simpoint_storage_provider_default
   on simpoint_storage_provider_configs (default_provider);
