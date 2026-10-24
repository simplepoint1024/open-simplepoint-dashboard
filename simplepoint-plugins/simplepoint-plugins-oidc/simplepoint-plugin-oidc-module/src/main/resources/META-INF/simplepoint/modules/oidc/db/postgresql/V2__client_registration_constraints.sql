-- Timestamps and encoded secrets are attributes, not client identities.
-- Resolve only this schema's table and its single-column UNIQUE constraints:
-- Hibernate-generated constraint names differ between existing installations.
do $migration$
declare
    obsolete record;
begin
    for obsolete in
        select c.conname
        from pg_constraint c
        join pg_attribute a
          on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
        where c.conrelid = 'simpoint_ac_oauth2_client'::regclass
          and c.contype = 'u'
          and cardinality(c.conkey) = 1
          and a.attname in ('client_id_issued_at', 'client_secret')
    loop
        execute format(
            'alter table simpoint_ac_oauth2_client drop constraint %I', obsolete.conname);
    end loop;
end
$migration$;

-- Public clients (for example authorization-code + PKCE) have no shared secret.
alter table simpoint_ac_oauth2_client alter column client_secret drop not null;
