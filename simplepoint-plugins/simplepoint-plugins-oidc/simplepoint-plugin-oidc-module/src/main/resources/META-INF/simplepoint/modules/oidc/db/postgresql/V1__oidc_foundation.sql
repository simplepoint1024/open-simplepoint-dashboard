-- PostgreSQL schema owned by oidc.
create table simpoint_ac_external_identity_link (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    last_login_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    provider_id varchar(64) not null,
    user_id varchar(64) not null,
    email_at_link varchar(320),
    external_subject varchar(512) not null,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id),
    constraint uk_external_identity_subject unique (provider_id, external_subject),
    constraint uk_external_identity_provider_user unique (provider_id, user_id)
);

create table simpoint_ac_external_identity_provider (
    allow_private_network boolean not null,
    enabled boolean not null,
    require_verified_email boolean not null,
    sort_order integer not null,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    protocol varchar(16) not null check ((protocol in ('OIDC','OAUTH2'))),
    match_strategy varchar(32) not null check ((match_strategy in ('VERIFIED_EMAIL','USER_ID'))),
    preset varchar(32) not null check ((preset in ('CUSTOM','GOOGLE','GITHUB','APPLE','MICROSOFT'))),
    client_authentication_method varchar(64) not null,
    registration_id varchar(64) not null unique,
    display_name varchar(128) not null,
    email_claim varchar(128),
    email_verified_claim varchar(128),
    subject_claim varchar(128) not null,
    user_name_attribute varchar(128) not null,
    client_id varchar(512) not null,
    description varchar(512),
    scopes varchar(2000) not null,
    authorization_uri varchar(2048),
    issuer_uri varchar(2048),
    jwk_set_uri varchar(2048),
    token_uri varchar(2048),
    user_info_uri varchar(2048),
    client_secret_ciphertext varchar(4096),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_ac_oauth2_authorization (
    access_token_expires_at timestamp(6) with time zone,
    access_token_issued_at timestamp(6) with time zone,
    authorization_code_expires_at timestamp(6) with time zone,
    authorization_code_issued_at timestamp(6) with time zone,
    device_code_expires_at timestamp(6) with time zone,
    device_code_issued_at timestamp(6) with time zone,
    oidc_id_token_expires_at timestamp(6) with time zone,
    oidc_id_token_issued_at timestamp(6) with time zone,
    refresh_token_expires_at timestamp(6) with time zone,
    refresh_token_issued_at timestamp(6) with time zone,
    user_code_expires_at timestamp(6) with time zone,
    user_code_issued_at timestamp(6) with time zone,
    state varchar(500),
    access_token_scopes varchar(1000),
    authorized_scopes text,
    access_token_metadata varchar(2000),
    device_code_metadata varchar(2000),
    oidc_id_token_claims varchar(2000),
    oidc_id_token_metadata varchar(2000),
    refresh_token_metadata varchar(2000),
    user_code_metadata varchar(2000),
    access_token_value text,
    attributes text,
    authorization_code_value text,
    device_code_value text,
    oidc_id_token_value text,
    refresh_token_value text,
    user_code_value text,
    access_token_type varchar(255),
    authorization_code_metadata varchar(255),
    authorization_grant_type varchar(255),
    id varchar(255) not null,
    principal_name varchar(255),
    registered_client_id varchar(255),
    primary key (id)
);

create table simpoint_ac_oauth2_client (
    client_id_issued_at timestamp(6) with time zone unique,
    client_secret_expires_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    authorization_grant_types varchar(1000),
    client_authentication_methods varchar(1000),
    post_logout_redirect_uris varchar(1000),
    redirect_uris varchar(1000),
    scopes varchar(1000),
    client_settings varchar(2000),
    token_settings varchar(2000),
    client_id varchar(255) not null unique,
    client_name varchar(255),
    client_secret varchar(255) not null unique,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_ac_oauth2_consent (
    authorities varchar(1000),
    principal_name varchar(255) not null,
    registered_client_id varchar(255) not null,
    primary key (principal_name, registered_client_id)
);

create index idx_external_identity_user
   on simpoint_ac_external_identity_link (user_id);

create index idx_external_identity_provider_user
   on simpoint_ac_external_identity_link (provider_id, user_id);

create index idx_external_idp_registration
   on simpoint_ac_external_identity_provider (registration_id);

create index idx_external_idp_enabled_sort
   on simpoint_ac_external_identity_provider (enabled, sort_order);
