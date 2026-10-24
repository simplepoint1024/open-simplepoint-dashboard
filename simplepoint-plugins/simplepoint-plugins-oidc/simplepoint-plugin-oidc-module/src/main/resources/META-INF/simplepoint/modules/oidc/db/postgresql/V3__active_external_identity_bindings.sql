-- Unlinking preserves history. Uniqueness applies only to effective bindings.
alter table simpoint_ac_external_identity_link
    drop constraint uk_external_identity_subject;
alter table simpoint_ac_external_identity_link
    drop constraint uk_external_identity_provider_user;

create unique index uk_external_identity_subject
    on simpoint_ac_external_identity_link (provider_id, external_subject)
    where deleted_at is null;
create unique index uk_external_identity_provider_user
    on simpoint_ac_external_identity_link (provider_id, user_id)
    where deleted_at is null;
