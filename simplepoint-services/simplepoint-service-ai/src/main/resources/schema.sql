CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Migrate legacy system/tenant AI resource codes before
-- classpath synchronization retires undeclared resources. Existing role and
-- application grants follow the same one-to-one mapping.
CREATE TEMPORARY TABLE IF NOT EXISTS sp_ai_workbench_resource_migration (
  old_code VARCHAR(120) PRIMARY KEY,
  new_code VARCHAR(120) NOT NULL
);
DELETE FROM sp_ai_workbench_resource_migration;
INSERT INTO sp_ai_workbench_resource_migration (old_code, new_code) VALUES
  ('ai.view', 'ai.workbench.view'),
  ('ai.system.view', 'ai.workbench.view'),
  ('ai.workspace.view', 'ai.workbench.workspace.view'),
  ('ai.api-keys.view', 'ai.workbench.api-keys.view'),
  ('ai.system.api-keys.view', 'ai.workbench.api-keys.view'),
  ('ai.api-keys.create', 'ai.workbench.api-keys.create'),
  ('ai.system.api-keys.create', 'ai.workbench.api-keys.create'),
  ('ai.api-keys.edit', 'ai.workbench.api-keys.edit'),
  ('ai.system.api-keys.edit', 'ai.workbench.api-keys.edit'),
  ('ai.api-keys.rotate', 'ai.workbench.api-keys.rotate'),
  ('ai.system.api-keys.rotate', 'ai.workbench.api-keys.rotate'),
  ('ai.api-keys.delete', 'ai.workbench.api-keys.delete'),
  ('ai.system.api-keys.delete', 'ai.workbench.api-keys.delete'),
  ('ai.providers.view', 'ai.workbench.providers.view'),
  ('ai.system.providers.view', 'ai.workbench.providers.view'),
  ('ai.providers.create', 'ai.workbench.providers.create'),
  ('ai.system.providers.create', 'ai.workbench.providers.create'),
  ('ai.providers.edit', 'ai.workbench.providers.edit'),
  ('ai.system.providers.edit', 'ai.workbench.providers.edit'),
  ('ai.providers.delete', 'ai.workbench.providers.delete'),
  ('ai.system.providers.delete', 'ai.workbench.providers.delete'),
  ('ai.providers.test', 'ai.workbench.providers.test'),
  ('ai.system.providers.test', 'ai.workbench.providers.test'),
  ('ai.providers.discover', 'ai.workbench.providers.discover'),
  ('ai.system.providers.discover', 'ai.workbench.providers.discover'),
  ('ai.providers.sync', 'ai.workbench.providers.sync'),
  ('ai.system.providers.sync', 'ai.workbench.providers.sync'),
  ('ai.models.view', 'ai.workbench.models.view'),
  ('ai.system.models.view', 'ai.workbench.models.view'),
  ('ai.models.create', 'ai.workbench.models.create'),
  ('ai.system.models.create', 'ai.workbench.models.create'),
  ('ai.models.edit', 'ai.workbench.models.edit'),
  ('ai.system.models.edit', 'ai.workbench.models.edit'),
  ('ai.models.delete', 'ai.workbench.models.delete'),
  ('ai.system.models.delete', 'ai.workbench.models.delete'),
  ('ai.inference.invoke', 'ai.workbench.models.debug'),
  ('ai.system.inference.invoke', 'ai.workbench.models.debug'),
  ('ai.playground.view', 'ai.workbench.models.view'),
  ('ai.system.playground.view', 'ai.workbench.models.view'),
  ('ai.knowledge-bases.view', 'ai.workbench.knowledge-bases.view'),
  ('ai.system.knowledge-bases.view', 'ai.workbench.knowledge-bases.view'),
  ('ai.knowledge-bases.create', 'ai.workbench.knowledge-bases.create'),
  ('ai.system.knowledge-bases.create', 'ai.workbench.knowledge-bases.create'),
  ('ai.knowledge-bases.edit', 'ai.workbench.knowledge-bases.edit'),
  ('ai.system.knowledge-bases.edit', 'ai.workbench.knowledge-bases.edit'),
  ('ai.knowledge-bases.delete', 'ai.workbench.knowledge-bases.delete'),
  ('ai.system.knowledge-bases.delete', 'ai.workbench.knowledge-bases.delete'),
  ('ai.knowledge-bases.documents', 'ai.workbench.knowledge-bases.documents'),
  ('ai.system.knowledge-bases.documents', 'ai.workbench.knowledge-bases.documents'),
  ('ai.knowledge-bases.retrieve', 'ai.workbench.knowledge-bases.retrieve'),
  ('ai.system.knowledge-bases.retrieve', 'ai.workbench.knowledge-bases.retrieve'),
  ('ai.billing.view', 'ai.workbench.billing.view'),
  ('ai.system.billing.view', 'ai.workbench.billing.view'),
  ('ai.invocations.view', 'ai.workbench.billing.view'),
  ('ai.system.invocations.view', 'ai.workbench.billing.view');
UPDATE simpoint_ac_role_resource_grants target
SET resource_code = migration.new_code
FROM sp_ai_workbench_resource_migration migration
WHERE target.resource_code = migration.old_code;
UPDATE simpoint_saas_application_resource_rel target
SET resource_code = migration.new_code
FROM sp_ai_workbench_resource_migration migration
WHERE target.resource_code = migration.old_code;
DELETE FROM simpoint_ac_role_resource_grants target
USING simpoint_ac_role_resource_grants duplicate
WHERE target.ctid > duplicate.ctid
  AND target.role_id = duplicate.role_id
  AND target.resource_code = duplicate.resource_code
  AND target.tenant_id IS NOT DISTINCT FROM duplicate.tenant_id
  AND target.data_scope_id IS NOT DISTINCT FROM duplicate.data_scope_id
  AND target.field_scope_id IS NOT DISTINCT FROM duplicate.field_scope_id;
DELETE FROM simpoint_saas_application_resource_rel target
USING simpoint_saas_application_resource_rel duplicate
WHERE target.ctid > duplicate.ctid
  AND target.application_code = duplicate.application_code
  AND target.resource_code = duplicate.resource_code;
DELETE FROM simpoint_ac_resource_ancestors_rel relation
WHERE relation.ancestor_id IN (
        SELECT resource.id
        FROM simpoint_ac_resources resource
        JOIN sp_ai_workbench_resource_migration migration
          ON migration.old_code = resource.code
      )
   OR relation.child_id IN (
        SELECT resource.id
        FROM simpoint_ac_resources resource
        JOIN sp_ai_workbench_resource_migration migration
          ON migration.old_code = resource.code
      );
DELETE FROM simpoint_ac_resources resource
USING sp_ai_workbench_resource_migration migration
WHERE resource.code = migration.old_code;
DROP TABLE IF EXISTS sp_ai_workbench_resource_migration;

-- Hibernate creates the entity tables before this deferred script runs. Partial indexes are used
-- instead of JPA unique constraints so SYSTEM rows with a NULL tenant remain unique and soft-deleted
-- provider/knowledge-base codes can be reused safely.
ALTER TABLE simpoint_ai_providers
  DROP CONSTRAINT IF EXISTS uk_simpoint_ai_provider_scope_code;
DROP INDEX IF EXISTS uk_simpoint_ai_provider_scope_code;
UPDATE simpoint_ai_providers SET scope_type = 'SYSTEM' WHERE scope_type IS NULL;
ALTER TABLE simpoint_ai_providers ALTER COLUMN scope_type SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_provider_active_system_code
  ON simpoint_ai_providers (code)
  WHERE scope_type = 'SYSTEM' AND tenant_id IS NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_provider_active_tenant_code
  ON simpoint_ai_providers (tenant_id, code)
  WHERE scope_type = 'TENANT' AND tenant_id IS NOT NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_api_key_active_system_name
  ON simpoint_ai_api_keys (lower(name))
  WHERE scope_type = 'SYSTEM' AND tenant_id IS NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_api_key_active_tenant_name
  ON simpoint_ai_api_keys (tenant_id, lower(name))
  WHERE scope_type = 'TENANT' AND tenant_id IS NOT NULL AND deleted_at IS NULL;

-- MCP registrations share the same platform/tenant ownership rules as the
-- rest of the AI workbench. Capability snapshots are immutable history and
-- tool invocations retain metadata and hashes only.
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_mcp_server_active_system_code
  ON simpoint_ai_mcp_servers (code)
  WHERE scope_type = 'SYSTEM' AND tenant_id IS NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_mcp_server_active_tenant_code
  ON simpoint_ai_mcp_servers (tenant_id, code)
  WHERE scope_type = 'TENANT' AND tenant_id IS NOT NULL AND deleted_at IS NULL;
ALTER TABLE simpoint_ai_mcp_servers
  ADD COLUMN IF NOT EXISTS deployment_type VARCHAR(32) DEFAULT 'REMOTE';
UPDATE simpoint_ai_mcp_servers
SET deployment_type = 'REMOTE'
WHERE deployment_type IS NULL;
ALTER TABLE simpoint_ai_mcp_servers
  ALTER COLUMN deployment_type SET DEFAULT 'REMOTE';
ALTER TABLE simpoint_ai_mcp_servers
  ALTER COLUMN deployment_type SET NOT NULL;
ALTER TABLE simpoint_ai_mcp_servers
  ALTER COLUMN endpoint_url DROP NOT NULL;
ALTER TABLE simpoint_ai_mcp_servers
  DROP CONSTRAINT IF EXISTS simpoint_ai_mcp_servers_transport_type_check;
ALTER TABLE simpoint_ai_mcp_servers
  ADD CONSTRAINT simpoint_ai_mcp_servers_transport_type_check
  CHECK (transport_type IN ('STREAMABLE_HTTP', 'STDIO'));
ALTER TABLE simpoint_ai_mcp_servers
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_mcp_server_deployment;
ALTER TABLE simpoint_ai_mcp_servers
  ADD CONSTRAINT ck_simpoint_ai_mcp_server_deployment
  CHECK (
    (
      deployment_type = 'REMOTE'
      AND transport_type = 'STREAMABLE_HTTP'
      AND endpoint_url IS NOT NULL
    )
    OR (
      deployment_type = 'MANAGED_OCI'
      AND transport_type = 'STDIO'
      AND endpoint_url IS NULL
      AND authentication_type = 'NONE'
      AND allow_private_network = FALSE
    )
  );
UPDATE simpoint_ai_mcp_capability_snapshots
SET resources_json = '[]'
WHERE resources_json IS NULL;
UPDATE simpoint_ai_mcp_capability_snapshots
SET resource_templates_json = '[]'
WHERE resource_templates_json IS NULL;
UPDATE simpoint_ai_mcp_capability_snapshots
SET prompts_json = '[]'
WHERE prompts_json IS NULL;
ALTER TABLE simpoint_ai_mcp_capability_snapshots
  ALTER COLUMN resources_json SET NOT NULL;
ALTER TABLE simpoint_ai_mcp_capability_snapshots
  ALTER COLUMN resource_templates_json SET NOT NULL;
ALTER TABLE simpoint_ai_mcp_capability_snapshots
  ALTER COLUMN prompts_json SET NOT NULL;
UPDATE simpoint_ai_mcp_servers
SET oauth_status = CASE
  WHEN authentication_type = 'OAUTH2' THEN 'CONFIGURED'
  ELSE 'NOT_CONFIGURED'
END
WHERE oauth_status IS NULL;
ALTER TABLE simpoint_ai_mcp_servers
  ALTER COLUMN oauth_status SET NOT NULL;
ALTER TABLE simpoint_ai_mcp_servers
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_mcp_server_scope;
ALTER TABLE simpoint_ai_mcp_servers
  ADD CONSTRAINT ck_simpoint_ai_mcp_server_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_mcp_capability_snapshots
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_mcp_snapshot_scope;
ALTER TABLE simpoint_ai_mcp_capability_snapshots
  ADD CONSTRAINT ck_simpoint_ai_mcp_snapshot_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_mcp_tool_invocations
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_mcp_invocation_scope;
ALTER TABLE simpoint_ai_mcp_tool_invocations
  ADD CONSTRAINT ck_simpoint_ai_mcp_invocation_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
UPDATE simpoint_ai_mcp_tool_invocations
SET capability_type = 'TOOL'
WHERE capability_type IS NULL;
UPDATE simpoint_ai_mcp_tool_invocations
SET capability_name = tool_name
WHERE capability_name IS NULL;
UPDATE simpoint_ai_mcp_tool_invocations
SET request_hash = arguments_hash
WHERE request_hash IS NULL;
ALTER TABLE simpoint_ai_mcp_tool_invocations
  ALTER COLUMN capability_type SET NOT NULL;
ALTER TABLE simpoint_ai_mcp_tool_invocations
  ALTER COLUMN capability_name SET NOT NULL;
ALTER TABLE simpoint_ai_mcp_tool_invocations
  ALTER COLUMN request_hash SET NOT NULL;
ALTER TABLE simpoint_ai_mcp_tool_invocations
  ALTER COLUMN tool_name DROP NOT NULL;
ALTER TABLE simpoint_ai_mcp_tool_invocations
  ALTER COLUMN arguments_hash DROP NOT NULL;
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_mcp_invocation_capability
  ON simpoint_ai_mcp_tool_invocations (capability_type, capability_name);
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_mcp_oauth_state
  ON simpoint_ai_mcp_oauth_authorizations (state_hash)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_mcp_oauth_authorizations
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_mcp_oauth_scope;
ALTER TABLE simpoint_ai_mcp_oauth_authorizations
  ADD CONSTRAINT ck_simpoint_ai_mcp_oauth_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_mcp_publication_active_code
  ON simpoint_ai_mcp_publications (code)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_mcp_publications
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_mcp_publication_scope;
ALTER TABLE simpoint_ai_mcp_publications
  ADD CONSTRAINT ck_simpoint_ai_mcp_publication_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_mcp_publications
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_mcp_publication_status;
ALTER TABLE simpoint_ai_mcp_publications
  ADD CONSTRAINT ck_simpoint_ai_mcp_publication_status
  CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED', 'ERROR'));

-- OCI Tool Runtime control-plane state. Nodes are platform-owned. Workloads
-- retain the originating platform/tenant scope, while leases carry fencing
-- tokens so stale schedulers and stale runtime process generations cannot
-- continue mutating an assignment.
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_runtime_node_id
  ON simpoint_ai_runtime_nodes (node_id);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_runtime_node_status
  ON simpoint_ai_runtime_nodes (status);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_runtime_node_heartbeat
  ON simpoint_ai_runtime_nodes (heartbeat_expires_at);
ALTER TABLE simpoint_ai_runtime_nodes
  ADD COLUMN IF NOT EXISTS max_workload_memory_bytes BIGINT
  DEFAULT 2147483648;
ALTER TABLE simpoint_ai_runtime_nodes
  ADD COLUMN IF NOT EXISTS max_workload_nano_cpus BIGINT
  DEFAULT 4000000000;
ALTER TABLE simpoint_ai_runtime_nodes
  ADD COLUMN IF NOT EXISTS max_workload_pids_limit BIGINT
  DEFAULT 512;
ALTER TABLE simpoint_ai_runtime_nodes
  ADD COLUMN IF NOT EXISTS allow_egress_network BOOLEAN
  DEFAULT FALSE;
ALTER TABLE simpoint_ai_runtime_nodes
  ADD COLUMN IF NOT EXISTS require_supply_chain_admission BOOLEAN
  DEFAULT FALSE;
ALTER TABLE simpoint_ai_runtime_nodes
  ADD COLUMN IF NOT EXISTS cached_image_digests_json TEXT DEFAULT '[]';
ALTER TABLE simpoint_ai_runtime_nodes
  ADD COLUMN IF NOT EXISTS seccomp_enforced BOOLEAN DEFAULT FALSE;
ALTER TABLE simpoint_ai_runtime_nodes
  ADD COLUMN IF NOT EXISTS seccomp_profile_hash VARCHAR(71);
ALTER TABLE simpoint_ai_runtime_nodes
  ADD COLUMN IF NOT EXISTS apparmor_enforced BOOLEAN DEFAULT FALSE;
ALTER TABLE simpoint_ai_runtime_nodes
  ADD COLUMN IF NOT EXISTS apparmor_profile VARCHAR(128);
UPDATE simpoint_ai_runtime_nodes
SET max_workload_memory_bytes = 2147483648
WHERE max_workload_memory_bytes IS NULL;
UPDATE simpoint_ai_runtime_nodes
SET max_workload_nano_cpus = 4000000000
WHERE max_workload_nano_cpus IS NULL;
UPDATE simpoint_ai_runtime_nodes
SET max_workload_pids_limit = 512
WHERE max_workload_pids_limit IS NULL;
UPDATE simpoint_ai_runtime_nodes
SET allow_egress_network = FALSE
WHERE allow_egress_network IS NULL;
UPDATE simpoint_ai_runtime_nodes
SET require_supply_chain_admission = FALSE
WHERE require_supply_chain_admission IS NULL;
UPDATE simpoint_ai_runtime_nodes
SET cached_image_digests_json = '[]'
WHERE cached_image_digests_json IS NULL;
UPDATE simpoint_ai_runtime_nodes
SET seccomp_enforced = FALSE
WHERE seccomp_enforced IS NULL;
UPDATE simpoint_ai_runtime_nodes
SET apparmor_enforced = FALSE
WHERE apparmor_enforced IS NULL;
ALTER TABLE simpoint_ai_runtime_nodes
  ALTER COLUMN max_workload_memory_bytes SET NOT NULL;
ALTER TABLE simpoint_ai_runtime_nodes
  ALTER COLUMN max_workload_nano_cpus SET NOT NULL;
ALTER TABLE simpoint_ai_runtime_nodes
  ALTER COLUMN max_workload_pids_limit SET NOT NULL;
ALTER TABLE simpoint_ai_runtime_nodes
  ALTER COLUMN allow_egress_network SET DEFAULT FALSE;
ALTER TABLE simpoint_ai_runtime_nodes
  ALTER COLUMN allow_egress_network SET NOT NULL;
ALTER TABLE simpoint_ai_runtime_nodes
  ALTER COLUMN require_supply_chain_admission SET DEFAULT FALSE;
ALTER TABLE simpoint_ai_runtime_nodes
  ALTER COLUMN require_supply_chain_admission SET NOT NULL;
ALTER TABLE simpoint_ai_runtime_nodes
  ALTER COLUMN cached_image_digests_json SET DEFAULT '[]';
ALTER TABLE simpoint_ai_runtime_nodes
  ALTER COLUMN cached_image_digests_json SET NOT NULL;
ALTER TABLE simpoint_ai_runtime_nodes
  ALTER COLUMN seccomp_enforced SET DEFAULT FALSE;
ALTER TABLE simpoint_ai_runtime_nodes
  ALTER COLUMN seccomp_enforced SET NOT NULL;
ALTER TABLE simpoint_ai_runtime_nodes
  ALTER COLUMN apparmor_enforced SET DEFAULT FALSE;
ALTER TABLE simpoint_ai_runtime_nodes
  ALTER COLUMN apparmor_enforced SET NOT NULL;
ALTER TABLE simpoint_ai_runtime_nodes
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_runtime_node_capacity;
ALTER TABLE simpoint_ai_runtime_nodes
  ADD CONSTRAINT ck_simpoint_ai_runtime_node_capacity
  CHECK (
    generation > 0
    AND cpu_cores > 0
    AND memory_bytes >= 33554432
    AND max_workloads > 0
    AND running_workloads >= 0
    AND running_workloads <= max_workloads
    AND max_workload_memory_bytes >= 33554432
    AND max_workload_memory_bytes <= memory_bytes
    AND max_workload_nano_cpus > 0
    AND max_workload_nano_cpus <= cpu_cores * 1000000000::BIGINT
    AND max_workload_pids_limit > 0
    AND max_workload_pids_limit <= 4096
  );

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_runtime_pool_system_code
  ON simpoint_ai_runtime_pools (code)
  WHERE scope_type = 'SYSTEM'
    AND tenant_id IS NULL
    AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_runtime_pool_tenant_code
  ON simpoint_ai_runtime_pools (tenant_id, code)
  WHERE scope_type = 'TENANT'
    AND tenant_id IS NOT NULL
    AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_runtime_pool_system_server
  ON simpoint_ai_runtime_pools (server_id)
  WHERE scope_type = 'SYSTEM'
    AND tenant_id IS NULL
    AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_runtime_pool_tenant_server
  ON simpoint_ai_runtime_pools (tenant_id, server_id)
  WHERE scope_type = 'TENANT'
    AND tenant_id IS NOT NULL
    AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_runtime_pool_scope
  ON simpoint_ai_runtime_pools (scope_type, tenant_id);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_runtime_pool_status
  ON simpoint_ai_runtime_pools (status);
ALTER TABLE simpoint_ai_runtime_pools
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_runtime_pool_scope;
ALTER TABLE simpoint_ai_runtime_pools
  ADD CONSTRAINT ck_simpoint_ai_runtime_pool_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_runtime_pools
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_runtime_pool_policy;
ALTER TABLE simpoint_ai_runtime_pools
  ADD CONSTRAINT ck_simpoint_ai_runtime_pool_policy
  CHECK (
    min_replicas >= 0
    AND max_replicas > 0
    AND min_replicas <= max_replicas
    AND desired_replicas >= 0
    AND desired_replicas <= max_replicas
    AND activation_replicas > 0
    AND activation_replicas <= max_replicas
    AND prewarm_nodes >= 0
    AND prewarm_nodes <= max_replicas
    AND idle_timeout_seconds >= 0
    AND replica_lifetime_seconds BETWEEN 60 AND 86400
    AND current_replicas >= 0
    AND ready_replicas >= 0
    AND prewarmed_nodes >= 0
    AND next_replica_sequence > 0
  );
ALTER TABLE simpoint_ai_runtime_pools
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_runtime_pool_network;
ALTER TABLE simpoint_ai_runtime_pools
  ADD CONSTRAINT ck_simpoint_ai_runtime_pool_network
  CHECK (
    (network_mode = 'egress' AND egress_allowlist_json <> '[]')
    OR (
      network_mode IN ('none', 'bridge')
      AND egress_allowlist_json = '[]'
    )
  );
ALTER TABLE simpoint_ai_runtime_pools
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_runtime_pool_status;
ALTER TABLE simpoint_ai_runtime_pools
  ADD CONSTRAINT ck_simpoint_ai_runtime_pool_status
  CHECK (status IN ('SCALING', 'READY', 'IDLE', 'ERROR', 'DISABLED'));

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_runtime_workload_execution
  ON simpoint_ai_runtime_workloads (execution_id)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_runtime_workloads
  ADD COLUMN IF NOT EXISTS secret_references_json TEXT DEFAULT '[]';
ALTER TABLE simpoint_ai_runtime_workloads
  ADD COLUMN IF NOT EXISTS egress_allowlist_json TEXT DEFAULT '[]';
UPDATE simpoint_ai_runtime_workloads
SET secret_references_json = '[]'
WHERE secret_references_json IS NULL;
UPDATE simpoint_ai_runtime_workloads
SET egress_allowlist_json = '[]'
WHERE egress_allowlist_json IS NULL;
ALTER TABLE simpoint_ai_runtime_workloads
  ALTER COLUMN secret_references_json SET DEFAULT '[]';
ALTER TABLE simpoint_ai_runtime_workloads
  ALTER COLUMN secret_references_json SET NOT NULL;
ALTER TABLE simpoint_ai_runtime_workloads
  ALTER COLUMN egress_allowlist_json SET DEFAULT '[]';
ALTER TABLE simpoint_ai_runtime_workloads
  ALTER COLUMN egress_allowlist_json SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_runtime_workload_scope
  ON simpoint_ai_runtime_workloads (scope_type, tenant_id);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_runtime_workload_status
  ON simpoint_ai_runtime_workloads (status);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_runtime_workload_node
  ON simpoint_ai_runtime_workloads (assigned_node_id);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_runtime_workload_pool
  ON simpoint_ai_runtime_workloads (pool_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_runtime_pool_replica
  ON simpoint_ai_runtime_workloads (pool_id, replica_sequence)
  WHERE pool_id IS NOT NULL;
ALTER TABLE simpoint_ai_runtime_workloads
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_runtime_workload_scope;
ALTER TABLE simpoint_ai_runtime_workloads
  ADD CONSTRAINT ck_simpoint_ai_runtime_workload_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_runtime_workloads
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_runtime_workload_fence;
ALTER TABLE simpoint_ai_runtime_workloads
  ADD CONSTRAINT ck_simpoint_ai_runtime_workload_fence
  CHECK (fencing_token >= 0);
ALTER TABLE simpoint_ai_runtime_workloads
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_runtime_workload_network;
ALTER TABLE simpoint_ai_runtime_workloads
  ADD CONSTRAINT ck_simpoint_ai_runtime_workload_network
  CHECK (
    (network_mode = 'egress' AND egress_allowlist_json <> '[]')
    OR (
      network_mode IN ('none', 'bridge')
      AND egress_allowlist_json = '[]'
    )
  );
ALTER TABLE simpoint_ai_runtime_workloads
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_runtime_workload_status;
ALTER TABLE simpoint_ai_runtime_workloads
  ADD CONSTRAINT ck_simpoint_ai_runtime_workload_status
  CHECK (status IN ('PENDING', 'ASSIGNED', 'STARTING', 'RUNNING',
                    'STOPPING', 'SUCCEEDED', 'FAILED', 'LOST', 'CANCELLED'));

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_runtime_secret_system_code
  ON simpoint_ai_runtime_secrets (code)
  WHERE scope_type = 'SYSTEM'
    AND tenant_id IS NULL
    AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_runtime_secret_tenant_code
  ON simpoint_ai_runtime_secrets (tenant_id, code)
  WHERE scope_type = 'TENANT'
    AND tenant_id IS NOT NULL
    AND deleted_at IS NULL;
ALTER TABLE simpoint_ai_runtime_secrets
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_runtime_secret_scope;
ALTER TABLE simpoint_ai_runtime_secrets
  ADD CONSTRAINT ck_simpoint_ai_runtime_secret_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_runtime_lease_active_workload
  ON simpoint_ai_runtime_leases (workload_id)
  WHERE status = 'ACTIVE' AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_runtime_lease_node
  ON simpoint_ai_runtime_leases (node_id);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_runtime_lease_expiry
  ON simpoint_ai_runtime_leases (status, expires_at);
ALTER TABLE simpoint_ai_runtime_leases
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_runtime_lease_fence;
ALTER TABLE simpoint_ai_runtime_leases
  ADD CONSTRAINT ck_simpoint_ai_runtime_lease_fence
  CHECK (fencing_token > 0);
ALTER TABLE simpoint_ai_runtime_leases
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_runtime_lease_status;
ALTER TABLE simpoint_ai_runtime_leases
  ADD CONSTRAINT ck_simpoint_ai_runtime_lease_status
  CHECK (status IN ('ACTIVE', 'RELEASED', 'EXPIRED', 'FENCED'));

ALTER TABLE simpoint_ai_knowledge_bases
  DROP CONSTRAINT IF EXISTS uk_simpoint_ai_kb_scope_code;
DROP INDEX IF EXISTS uk_simpoint_ai_kb_scope_code;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_kb_active_system_code
  ON simpoint_ai_knowledge_bases (code)
  WHERE scope_type = 'SYSTEM' AND tenant_id IS NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_kb_active_tenant_code
  ON simpoint_ai_knowledge_bases (tenant_id, code)
  WHERE scope_type = 'TENANT' AND tenant_id IS NOT NULL AND deleted_at IS NULL;

-- Refresh enum check constraints when an existing Hibernate-managed table predates new states.
-- Keep these as plain statements because Spring's SQL initializer splits statements on semicolons.
ALTER TABLE simpoint_ai_knowledge_documents
  DROP CONSTRAINT IF EXISTS simpoint_ai_knowledge_documents_status_check;
ALTER TABLE simpoint_ai_knowledge_documents
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_kb_document_status;
ALTER TABLE simpoint_ai_knowledge_documents
  ADD CONSTRAINT ck_simpoint_ai_kb_document_status
  CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED',
                    'REINDEXING', 'REINDEX_FAILED'));

-- The business resource scope and the storage tenant are not always the same (for example,
-- a platform knowledge base can still store its source file in the operator's personal tenant).
ALTER TABLE simpoint_ai_knowledge_documents
  ADD COLUMN IF NOT EXISTS storage_tenant_id VARCHAR(64);
UPDATE simpoint_ai_knowledge_documents document
SET storage_tenant_id = storage_object.tenant_id
FROM simpoint_storage_objects storage_object
WHERE document.storage_object_id = storage_object.id
  AND document.storage_tenant_id IS NULL;

ALTER TABLE simpoint_ai_invocations
  DROP CONSTRAINT IF EXISTS simpoint_ai_invocations_status_check;
ALTER TABLE simpoint_ai_invocations
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_invocation_status;
ALTER TABLE simpoint_ai_invocations
  ADD CONSTRAINT ck_simpoint_ai_invocation_status
  CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'));

CREATE TABLE IF NOT EXISTS simpoint_ai_knowledge_index_jobs (
  document_id VARCHAR(64) PRIMARY KEY,
  knowledge_base_id VARCHAR(64) NOT NULL,
  scope_type VARCHAR(16) NOT NULL,
  tenant_id VARCHAR(64),
  job_generation BIGINT NOT NULL DEFAULT 1,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  preserve_existing_index BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(16) NOT NULL,
  next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  lease_owner VARCHAR(64),
  lease_until TIMESTAMP WITH TIME ZONE,
  last_error TEXT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_kb_index_job_available
  ON simpoint_ai_knowledge_index_jobs (status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_kb_index_job_base
  ON simpoint_ai_knowledge_index_jobs (knowledge_base_id);

-- Resume documents left in the legacy synchronous PROCESSING state after an interrupted request.
INSERT INTO simpoint_ai_knowledge_index_jobs (
  document_id, knowledge_base_id, scope_type, tenant_id, preserve_existing_index,
  status, next_attempt_at, created_at, updated_at
)
SELECT id, knowledge_base_id, scope_type, tenant_id, FALSE,
       'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM simpoint_ai_knowledge_documents
WHERE status = 'PROCESSING' AND deleted_at IS NULL
ON CONFLICT (document_id) DO NOTHING;
UPDATE simpoint_ai_knowledge_documents
SET status = 'PENDING'
WHERE status = 'PROCESSING' AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS simpoint_ai_knowledge_chunks (
  id VARCHAR(64) PRIMARY KEY,
  knowledge_base_id VARCHAR(64) NOT NULL,
  document_id VARCHAR(64) NOT NULL,
  scope_type VARCHAR(16) NOT NULL,
  tenant_id VARCHAR(64),
  chunk_index INTEGER NOT NULL,
  content TEXT NOT NULL,
  content_tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
  metadata_json TEXT,
  character_count INTEGER NOT NULL,
  embedding VECTOR(2000),
  embedding_dimensions INTEGER,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_kb_chunk_document_index
  ON simpoint_ai_knowledge_chunks (document_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_kb_chunk_base
  ON simpoint_ai_knowledge_chunks (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_kb_chunk_scope
  ON simpoint_ai_knowledge_chunks (scope_type, tenant_id);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_kb_chunk_tsv
  ON simpoint_ai_knowledge_chunks USING GIN (content_tsv);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_kb_chunk_trgm
  ON simpoint_ai_knowledge_chunks USING GIN (content gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_kb_chunk_embedding
  ON simpoint_ai_knowledge_chunks USING HNSW (embedding vector_cosine_ops);

-- Declarative Skill Registry. Hibernate owns table creation; this section adds
-- cross-row uniqueness and ownership invariants that cannot be expressed by JPA.
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_skill_active_system_code
  ON simpoint_ai_skills (code)
  WHERE scope_type = 'SYSTEM'
    AND tenant_id IS NULL
    AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_skill_active_tenant_code
  ON simpoint_ai_skills (tenant_id, code)
  WHERE scope_type = 'TENANT'
    AND tenant_id IS NOT NULL
    AND deleted_at IS NULL;
ALTER TABLE simpoint_ai_skills
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_scope;
ALTER TABLE simpoint_ai_skills
  ADD CONSTRAINT ck_simpoint_ai_skill_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_skills
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_status;
ALTER TABLE simpoint_ai_skills
  ADD CONSTRAINT ck_simpoint_ai_skill_status
  CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED'));

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_skill_version_name
  ON simpoint_ai_skill_versions (skill_id, version_name)
  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_skill_version_digest
  ON simpoint_ai_skill_versions (artifact_digest);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_skill_version_content_digest
  ON simpoint_ai_skill_versions (artifact_content_digest);
ALTER TABLE simpoint_ai_skill_versions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_version_scope;
ALTER TABLE simpoint_ai_skill_versions
  ADD CONSTRAINT ck_simpoint_ai_skill_version_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_skill_versions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_version_status;
ALTER TABLE simpoint_ai_skill_versions
  ADD CONSTRAINT ck_simpoint_ai_skill_version_status
  CHECK (status IN ('DRAFT', 'PUBLISHED', 'DEPRECATED'));
ALTER TABLE simpoint_ai_skill_versions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_version_digest;
ALTER TABLE simpoint_ai_skill_versions
  ADD CONSTRAINT ck_simpoint_ai_skill_version_digest
  CHECK (artifact_digest ~ '^sha256:[0-9a-f]{64}$');
ALTER TABLE simpoint_ai_skill_versions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_version_supply_chain;
ALTER TABLE simpoint_ai_skill_versions
  ADD CONSTRAINT ck_simpoint_ai_skill_version_supply_chain
  CHECK (
    artifact_config_digest ~ '^sha256:[0-9a-f]{64}$'
    AND artifact_content_digest ~ '^sha256:[0-9a-f]{64}$'
    AND artifact_verification_policy_hash ~ '^sha256:[0-9a-f]{64}$'
    AND (
      artifact_signature_required = FALSE
      OR artifact_signature_verified = TRUE
    )
  );

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_skill_binding_alias
  ON simpoint_ai_skill_tool_bindings (skill_version_id, tool_alias)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_skill_tool_bindings
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_binding_scope;
ALTER TABLE simpoint_ai_skill_tool_bindings
  ADD CONSTRAINT ck_simpoint_ai_skill_binding_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_skill_tool_bindings
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_binding_order;
ALTER TABLE simpoint_ai_skill_tool_bindings
  ADD CONSTRAINT ck_simpoint_ai_skill_binding_order
  CHECK (binding_order >= 0);

-- Durable Skill Workflow queue. Idempotency is scoped to one Skill and
-- ownership context; leases allow multiple AI service replicas to use
-- skip-locked claims without holding a database transaction during MCP I/O.
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_skill_execution_system_idem
  ON simpoint_ai_skill_executions (skill_id, idempotency_key_hash)
  WHERE scope_type = 'SYSTEM'
    AND tenant_id IS NULL
    AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_skill_execution_tenant_idem
  ON simpoint_ai_skill_executions (
    tenant_id, skill_id, idempotency_key_hash
  )
  WHERE scope_type = 'TENANT'
    AND tenant_id IS NOT NULL
    AND deleted_at IS NULL;
ALTER TABLE simpoint_ai_skill_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_execution_scope;
ALTER TABLE simpoint_ai_skill_executions
  ADD CONSTRAINT ck_simpoint_ai_skill_execution_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_skill_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_execution_status;
ALTER TABLE simpoint_ai_skill_executions
  ADD CONSTRAINT ck_simpoint_ai_skill_execution_status
  CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'));
ALTER TABLE simpoint_ai_skill_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_execution_lease;
ALTER TABLE simpoint_ai_skill_executions
  ADD CONSTRAINT ck_simpoint_ai_skill_execution_lease
  CHECK (
    attempt_count >= 0
    AND lease_token >= 0
    AND (
      status <> 'RUNNING'
      OR (
        lease_owner IS NOT NULL
        AND lease_expires_at IS NOT NULL
      )
    )
  );

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_skill_execution_step
  ON simpoint_ai_skill_execution_steps (execution_id, step_id)
  WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_skill_execution_step_order
  ON simpoint_ai_skill_execution_steps (execution_id, step_order)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_skill_execution_steps
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_execution_step_status;
ALTER TABLE simpoint_ai_skill_execution_steps
  ADD CONSTRAINT ck_simpoint_ai_skill_execution_step_status
  CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED'));
ALTER TABLE simpoint_ai_skill_execution_steps
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_execution_step_order;
ALTER TABLE simpoint_ai_skill_execution_steps
  ADD CONSTRAINT ck_simpoint_ai_skill_execution_step_order
  CHECK (step_order >= 0 AND attempt_count >= 0);
