-- Display-only directory revision. It must not be used as an authorization version.
-- The epoch fences a fresh installation/rebuild; increment and source mutations commit together.
create table simpoint_identity_member_label_revision (
  singleton boolean primary key default true check (singleton),
  epoch varchar(36) not null,
  revision bigint not null default 0 check (revision >= 0)
);
insert into simpoint_identity_member_label_revision (epoch) values (gen_random_uuid()::text);

-- Lock before modifying directory rows. A singleton intentionally serializes the relatively rare
-- identity directory mutations, not workflow reads/writes or unrelated security/account updates.
-- Qualify through the trigger's owning schema: no caller search_path or public-schema fallback.
create function simplepoint_advance_member_label_revision() returns trigger
language plpgsql as $$
declare affected integer;
begin
  execute format(
    'update %I.simpoint_identity_member_label_revision set revision=revision+1 where singleton=true',
    TG_TABLE_SCHEMA);
  get diagnostics affected = row_count;
  if affected <> 1 then
    raise exception 'Member label revision is missing';
  end if;
  return null;
end;
$$;

create trigger simplepoint_member_labels_user
before insert or delete or update of id,name,family_name,middle_name,given_name,email,enabled,deleted_at
or truncate on simpoint_ac_users
for each statement execute function simplepoint_advance_member_label_revision();

create trigger simplepoint_member_labels_tenant
before insert or delete or update of id,owner_id,deleted_at or truncate on simpoint_saas_tenants
for each statement execute function simplepoint_advance_member_label_revision();

create trigger simplepoint_member_labels_membership
before insert or delete or update of tenant_id,user_id,enabled,deleted_at
or truncate on simpoint_saas_tenant_user_rel
for each statement execute function simplepoint_advance_member_label_revision();
