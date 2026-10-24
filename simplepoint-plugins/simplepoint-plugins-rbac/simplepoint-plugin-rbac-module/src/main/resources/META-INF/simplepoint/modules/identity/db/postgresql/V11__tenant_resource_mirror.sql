-- Copies platform definition metadata; licenses/role grants are adopted in a separate reviewed step.
ALTER TABLE simpoint_saas_tenant_resources
    ADD COLUMN resource_alias varchar(120),
    ADD COLUMN title varchar(120),
    ADD COLUMN label varchar(120),
    ADD COLUMN plugin_id varchar(128),
    ADD COLUMN scope_types varchar(64),
    ADD COLUMN method varchar(16),
    ADD COLUMN pattern varchar(240),
    ADD COLUMN public_access boolean NOT NULL DEFAULT false,
    ADD COLUMN danger boolean NOT NULL DEFAULT false,
    ADD COLUMN source_available boolean NOT NULL DEFAULT true,
    ADD COLUMN baseline_entitled boolean NOT NULL DEFAULT false;

UPDATE simpoint_saas_tenant_resources SET source_available = false WHERE platform_resource_id IS NOT NULL;
UPDATE simpoint_saas_tenant_resources t SET
    resource_alias = p.resource_alias, title = p.title, label = p.label, plugin_id = p.plugin_id,
    scope_types = p.scope_types, method = p.method, pattern = p.pattern,
    public_access = p.public_access, danger = coalesce(p.danger, false),
    permission_metadata = p.permission_metadata,
    source_available = p.deleted_at IS NULL AND NOT p.disabled
FROM simpoint_ac_resources p WHERE t.platform_resource_id = p.id;
