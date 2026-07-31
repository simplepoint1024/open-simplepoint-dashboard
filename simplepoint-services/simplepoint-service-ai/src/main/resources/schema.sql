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

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_skill_prompt_binding_alias
  ON simpoint_ai_skill_prompt_bindings (skill_version_id, prompt_alias)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_skill_prompt_bindings
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_prompt_binding_scope;
ALTER TABLE simpoint_ai_skill_prompt_bindings
  ADD CONSTRAINT ck_simpoint_ai_skill_prompt_binding_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_skill_prompt_bindings
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_prompt_binding_order;
ALTER TABLE simpoint_ai_skill_prompt_bindings
  ADD CONSTRAINT ck_simpoint_ai_skill_prompt_binding_order
  CHECK (binding_order >= 0);

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_skill_resource_binding_alias
  ON simpoint_ai_skill_resource_bindings (skill_version_id, resource_alias)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_skill_resource_bindings
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_resource_binding_scope;
ALTER TABLE simpoint_ai_skill_resource_bindings
  ADD CONSTRAINT ck_simpoint_ai_skill_resource_binding_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_skill_resource_bindings
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_resource_binding_order;
ALTER TABLE simpoint_ai_skill_resource_bindings
  ADD CONSTRAINT ck_simpoint_ai_skill_resource_binding_order
  CHECK (binding_order >= 0);

-- Durable Skill Workflow queue. Idempotency is scoped to one Skill and
-- ownership context; leases allow multiple AI service replicas to use
-- skip-locked claims without holding a database transaction during MCP I/O.
ALTER TABLE simpoint_ai_skill_versions
  ADD COLUMN IF NOT EXISTS budget_json TEXT;
UPDATE simpoint_ai_skill_versions
SET budget_json = '{"maximumToolCalls":128,"maximumDurationSeconds":300,"maximumPayloadBytes":1048576}'
WHERE budget_json IS NULL;
ALTER TABLE simpoint_ai_skill_versions
  ALTER COLUMN budget_json SET NOT NULL;

ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS maximum_tool_calls INTEGER;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS maximum_duration_seconds INTEGER;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS maximum_payload_bytes BIGINT;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS consumed_tool_calls INTEGER;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS consumed_payload_bytes BIGINT;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS deadline_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS approval_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS self_approval_allowed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS approval_instructions VARCHAR(512);
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS approval_requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS approved_by VARCHAR(64);
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS approval_comment VARCHAR(1024);
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS rejected_by VARCHAR(64);
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(1024);
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS pause_requested BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS pause_requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS pause_requested_by VARCHAR(64);
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS pause_reason VARCHAR(1024);
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS paused_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS resumed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS resumed_by VARCHAR(64);
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS inactive_since TIMESTAMP WITH TIME ZONE;
ALTER TABLE simpoint_ai_skill_executions
  ADD COLUMN IF NOT EXISTS workflow_plan_json TEXT;
UPDATE simpoint_ai_skill_executions
SET maximum_tool_calls = COALESCE(maximum_tool_calls, 128),
    maximum_duration_seconds = COALESCE(maximum_duration_seconds, 300),
    maximum_payload_bytes = COALESCE(maximum_payload_bytes, 4194304),
    consumed_tool_calls = COALESCE(consumed_tool_calls, 0),
    consumed_payload_bytes = COALESCE(consumed_payload_bytes, 0),
    deadline_at = COALESCE(
      deadline_at,
      completed_at,
      started_at + INTERVAL '300 seconds',
      created_at + INTERVAL '300 seconds',
      CURRENT_TIMESTAMP + INTERVAL '300 seconds'
    ),
    approval_required = COALESCE(approval_required, FALSE),
    self_approval_allowed = COALESCE(self_approval_allowed, FALSE),
    pause_requested = COALESCE(pause_requested, FALSE);
ALTER TABLE simpoint_ai_skill_executions
  ALTER COLUMN maximum_tool_calls SET NOT NULL,
  ALTER COLUMN maximum_duration_seconds SET NOT NULL,
  ALTER COLUMN maximum_payload_bytes SET NOT NULL,
  ALTER COLUMN consumed_tool_calls SET NOT NULL,
  ALTER COLUMN consumed_payload_bytes SET NOT NULL,
  ALTER COLUMN deadline_at SET NOT NULL;
ALTER TABLE simpoint_ai_skill_executions
  ALTER COLUMN approval_required SET DEFAULT FALSE,
  ALTER COLUMN approval_required SET NOT NULL,
  ALTER COLUMN self_approval_allowed SET DEFAULT FALSE,
  ALTER COLUMN self_approval_allowed SET NOT NULL,
  ALTER COLUMN pause_requested SET DEFAULT FALSE,
  ALTER COLUMN pause_requested SET NOT NULL;

ALTER TABLE simpoint_ai_skill_execution_steps
  ADD COLUMN IF NOT EXISTS capability_token_id_hash VARCHAR(64);
ALTER TABLE simpoint_ai_skill_execution_steps
  ADD COLUMN IF NOT EXISTS binding_id VARCHAR(64);
ALTER TABLE simpoint_ai_skill_execution_steps
  ADD COLUMN IF NOT EXISTS capability_alias VARCHAR(64);
ALTER TABLE simpoint_ai_skill_execution_steps
  ADD COLUMN IF NOT EXISTS capability_name VARCHAR(1024);
ALTER TABLE simpoint_ai_skill_execution_steps
  ADD COLUMN IF NOT EXISTS capability_schema_hash VARCHAR(64);
ALTER TABLE simpoint_ai_skill_execution_steps
  ADD COLUMN IF NOT EXISTS capability_template BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE simpoint_ai_skill_execution_steps
  ADD COLUMN IF NOT EXISTS input_template_json TEXT;
-- Recreate missing legacy columns as nullable migration inputs so this block is
-- safe both before and after the cleanup, and on a completely new database.
ALTER TABLE simpoint_ai_skill_execution_steps
  ADD COLUMN IF NOT EXISTS tool_binding_id VARCHAR(64),
  ADD COLUMN IF NOT EXISTS tool_alias VARCHAR(64),
  ADD COLUMN IF NOT EXISTS tool_name VARCHAR(128),
  ADD COLUMN IF NOT EXISTS input_schema_hash VARCHAR(64),
  ADD COLUMN IF NOT EXISTS arguments_template_json TEXT;
UPDATE simpoint_ai_skill_execution_steps
SET binding_id = COALESCE(binding_id, tool_binding_id),
    capability_alias = COALESCE(capability_alias, tool_alias),
    capability_name = COALESCE(capability_name, tool_name),
    capability_schema_hash = COALESCE(
      capability_schema_hash,
      input_schema_hash
    ),
    input_template_json = COALESCE(
      input_template_json,
      arguments_template_json
    );
ALTER TABLE simpoint_ai_skill_execution_steps
  DROP COLUMN IF EXISTS tool_binding_id,
  DROP COLUMN IF EXISTS tool_alias,
  DROP COLUMN IF EXISTS tool_name,
  DROP COLUMN IF EXISTS input_schema_hash,
  DROP COLUMN IF EXISTS arguments_template_json;

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
  DROP CONSTRAINT IF EXISTS simpoint_ai_skill_executions_status_check;
ALTER TABLE simpoint_ai_skill_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_execution_status;
ALTER TABLE simpoint_ai_skill_executions
  ADD CONSTRAINT ck_simpoint_ai_skill_execution_status
  CHECK (status IN (
    'WAITING_APPROVAL', 'PENDING', 'RUNNING', 'PAUSED',
    'SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED'
  ));
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
ALTER TABLE simpoint_ai_skill_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_execution_budget;
ALTER TABLE simpoint_ai_skill_executions
  ADD CONSTRAINT ck_simpoint_ai_skill_execution_budget
  CHECK (
    maximum_tool_calls > 0
    AND maximum_duration_seconds > 0
    AND maximum_payload_bytes >= 1024
    AND consumed_tool_calls >= 0
    AND consumed_tool_calls <= maximum_tool_calls
    AND consumed_payload_bytes >= 0
    AND consumed_payload_bytes <= maximum_payload_bytes
    AND deadline_at IS NOT NULL
  );
ALTER TABLE simpoint_ai_skill_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_execution_approval;
ALTER TABLE simpoint_ai_skill_executions
  ADD CONSTRAINT ck_simpoint_ai_skill_execution_approval
  CHECK (
    (self_approval_allowed = FALSE OR approval_required = TRUE)
    AND (
      status <> 'WAITING_APPROVAL'
      OR (
        approval_required = TRUE
        AND approval_requested_at IS NOT NULL
        AND approved_at IS NULL
        AND rejected_at IS NULL
        AND inactive_since IS NOT NULL
      )
    )
    AND (
      status <> 'REJECTED'
      OR (
        approval_required = TRUE
        AND rejected_at IS NOT NULL
        AND rejected_by IS NOT NULL
      )
    )
    AND (approved_at IS NULL OR approved_by IS NOT NULL)
    AND (rejected_at IS NULL OR rejected_by IS NOT NULL)
    AND NOT (approved_at IS NOT NULL AND rejected_at IS NOT NULL)
  );
ALTER TABLE simpoint_ai_skill_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_execution_pause;
ALTER TABLE simpoint_ai_skill_executions
  ADD CONSTRAINT ck_simpoint_ai_skill_execution_pause
  CHECK (
    (
      pause_requested = FALSE
      OR (
        pause_requested_at IS NOT NULL
        AND status IN ('RUNNING', 'PAUSED')
      )
    )
    AND (
      status <> 'PAUSED'
      OR (
        pause_requested = TRUE
        AND paused_at IS NOT NULL
        AND inactive_since IS NOT NULL
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
ALTER TABLE simpoint_ai_skill_execution_steps
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_skill_execution_step_capability;
ALTER TABLE simpoint_ai_skill_execution_steps
  ADD CONSTRAINT ck_simpoint_ai_skill_execution_step_capability
  CHECK (
    step_type IN ('tool', 'prompt', 'resource')
    AND binding_id IS NOT NULL
    AND capability_alias IS NOT NULL
    AND mcp_server_id IS NOT NULL
    AND capability_snapshot_id IS NOT NULL
    AND capability_name IS NOT NULL
    AND capability_schema_hash IS NOT NULL
    AND capability_template IS NOT NULL
    AND (step_type = 'resource' OR capability_template = FALSE)
  );

-- Declarative Agent Registry. Agent versions pin model selectors and immutable
-- published Skill versions; execution state is claimed by Agent Runtime.
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_agent_active_system_code
  ON simpoint_ai_agents (code)
  WHERE scope_type = 'SYSTEM'
    AND tenant_id IS NULL
    AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_agent_active_tenant_code
  ON simpoint_ai_agents (tenant_id, code)
  WHERE scope_type = 'TENANT'
    AND tenant_id IS NOT NULL
    AND deleted_at IS NULL;
ALTER TABLE simpoint_ai_agents
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_scope;
ALTER TABLE simpoint_ai_agents
  ADD CONSTRAINT ck_simpoint_ai_agent_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_agents
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_status;
ALTER TABLE simpoint_ai_agents
  ADD CONSTRAINT ck_simpoint_ai_agent_status
  CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED'));

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_agent_version_name
  ON simpoint_ai_agent_versions (agent_id, version_name)
  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_agent_version_content_hash
  ON simpoint_ai_agent_versions (content_hash);
ALTER TABLE simpoint_ai_agent_versions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_version_scope;
ALTER TABLE simpoint_ai_agent_versions
  ADD CONSTRAINT ck_simpoint_ai_agent_version_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_agent_versions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_version_status;
ALTER TABLE simpoint_ai_agent_versions
  ADD CONSTRAINT ck_simpoint_ai_agent_version_status
  CHECK (status IN ('DRAFT', 'PUBLISHED', 'DEPRECATED'));
ALTER TABLE simpoint_ai_agent_versions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_version_content_hash;
ALTER TABLE simpoint_ai_agent_versions
  ADD CONSTRAINT ck_simpoint_ai_agent_version_content_hash
  CHECK (content_hash ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_agent_skill_binding_alias
  ON simpoint_ai_agent_skill_bindings (agent_version_id, skill_alias)
  WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_agent_skill_binding_version
  ON simpoint_ai_agent_skill_bindings (agent_version_id, skill_version_id)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_agent_skill_bindings
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_skill_binding_scope;
ALTER TABLE simpoint_ai_agent_skill_bindings
  ADD CONSTRAINT ck_simpoint_ai_agent_skill_binding_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_agent_skill_bindings
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_skill_binding_order;
ALTER TABLE simpoint_ai_agent_skill_bindings
  ADD CONSTRAINT ck_simpoint_ai_agent_skill_binding_order
  CHECK (binding_order >= 0);
ALTER TABLE simpoint_ai_agent_skill_bindings
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_skill_binding_hash;
ALTER TABLE simpoint_ai_agent_skill_bindings
  ADD CONSTRAINT ck_simpoint_ai_agent_skill_binding_hash
  CHECK (skill_content_hash ~ '^[0-9a-f]{64}$');

ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS short_term_memory_enabled BOOLEAN;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS long_term_memory_enabled BOOLEAN;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS maximum_memory_messages INTEGER;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS maximum_memory_summary_characters INTEGER;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS compacted_message_count INTEGER;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS memory_revision INTEGER;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS memory_summary_hash VARCHAR(64);
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS last_memory_compacted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS long_term_memory_scope VARCHAR(16);
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS maximum_long_term_memory_entries INTEGER;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS long_term_memory_retrieval_top_k INTEGER;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS long_term_memory_score_threshold DOUBLE PRECISION;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS
    maximum_long_term_memory_injection_characters INTEGER;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS maximum_long_term_memory_record_characters INTEGER;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS long_term_memory_retention_days INTEGER;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS long_term_memory_context_json TEXT;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS long_term_memory_retrieved_count INTEGER;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS long_term_memory_injected_characters INTEGER;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS long_term_memory_snapshot_hash VARCHAR(64);
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS
    long_term_memory_retrieved_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS long_term_memory_written_id VARCHAR(64);
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS
    long_term_memory_written_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS human_intervention_enabled BOOLEAN;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS maximum_human_interventions INTEGER;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS human_intervention_timeout_seconds INTEGER;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS human_intervention_timeout_action VARCHAR(16);
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS human_intervention_count INTEGER;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS current_human_intervention_id VARCHAR(64);
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS pause_requested BOOLEAN;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS pause_requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS pause_requested_by VARCHAR(64);
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS pause_reason VARCHAR(1024);
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS paused_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS resumed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE simpoint_ai_agent_executions
  ADD COLUMN IF NOT EXISTS resumed_by VARCHAR(64);
UPDATE simpoint_ai_agent_executions
SET short_term_memory_enabled =
      COALESCE(short_term_memory_enabled, TRUE),
    long_term_memory_enabled =
      COALESCE(long_term_memory_enabled, FALSE),
    maximum_memory_messages =
      GREATEST(
        COALESCE(maximum_memory_messages, 20),
        COALESCE(maximum_concurrency, 1) + 2
      ),
    maximum_memory_summary_characters =
      COALESCE(maximum_memory_summary_characters, 8192),
    compacted_message_count =
      COALESCE(compacted_message_count, 0),
    memory_revision =
      COALESCE(memory_revision, 0),
    long_term_memory_scope =
      COALESCE(long_term_memory_scope, 'SUBJECT'),
    maximum_long_term_memory_entries =
      COALESCE(maximum_long_term_memory_entries, 500),
    long_term_memory_retrieval_top_k =
      COALESCE(long_term_memory_retrieval_top_k, 5),
    long_term_memory_score_threshold =
      COALESCE(long_term_memory_score_threshold, 0.05),
    maximum_long_term_memory_injection_characters =
      COALESCE(maximum_long_term_memory_injection_characters, 6000),
    maximum_long_term_memory_record_characters =
      COALESCE(maximum_long_term_memory_record_characters, 8192),
    long_term_memory_retention_days =
      COALESCE(long_term_memory_retention_days, 365),
    long_term_memory_retrieved_count =
      COALESCE(long_term_memory_retrieved_count, 0),
    long_term_memory_injected_characters =
      COALESCE(long_term_memory_injected_characters, 0),
    human_intervention_enabled =
      COALESCE(human_intervention_enabled, FALSE),
    maximum_human_interventions =
      COALESCE(maximum_human_interventions, 4),
    human_intervention_timeout_seconds =
      COALESCE(human_intervention_timeout_seconds, 3600),
    human_intervention_timeout_action =
      COALESCE(human_intervention_timeout_action, 'FAIL'),
    human_intervention_count =
      COALESCE(human_intervention_count, 0),
    pause_requested =
      COALESCE(pause_requested, FALSE);
ALTER TABLE simpoint_ai_agent_executions
  ALTER COLUMN short_term_memory_enabled SET DEFAULT TRUE,
  ALTER COLUMN short_term_memory_enabled SET NOT NULL,
  ALTER COLUMN long_term_memory_enabled SET DEFAULT FALSE,
  ALTER COLUMN long_term_memory_enabled SET NOT NULL,
  ALTER COLUMN maximum_memory_messages SET DEFAULT 20,
  ALTER COLUMN maximum_memory_messages SET NOT NULL,
  ALTER COLUMN maximum_memory_summary_characters SET DEFAULT 8192,
  ALTER COLUMN maximum_memory_summary_characters SET NOT NULL,
  ALTER COLUMN compacted_message_count SET DEFAULT 0,
  ALTER COLUMN compacted_message_count SET NOT NULL,
  ALTER COLUMN memory_revision SET DEFAULT 0,
  ALTER COLUMN memory_revision SET NOT NULL,
  ALTER COLUMN long_term_memory_scope SET DEFAULT 'SUBJECT',
  ALTER COLUMN long_term_memory_scope SET NOT NULL,
  ALTER COLUMN maximum_long_term_memory_entries SET DEFAULT 500,
  ALTER COLUMN maximum_long_term_memory_entries SET NOT NULL,
  ALTER COLUMN long_term_memory_retrieval_top_k SET DEFAULT 5,
  ALTER COLUMN long_term_memory_retrieval_top_k SET NOT NULL,
  ALTER COLUMN long_term_memory_score_threshold SET DEFAULT 0.05,
  ALTER COLUMN long_term_memory_score_threshold SET NOT NULL,
  ALTER COLUMN maximum_long_term_memory_injection_characters
    SET DEFAULT 6000,
  ALTER COLUMN maximum_long_term_memory_injection_characters SET NOT NULL,
  ALTER COLUMN maximum_long_term_memory_record_characters SET DEFAULT 8192,
  ALTER COLUMN maximum_long_term_memory_record_characters SET NOT NULL,
  ALTER COLUMN long_term_memory_retention_days SET DEFAULT 365,
  ALTER COLUMN long_term_memory_retention_days SET NOT NULL,
  ALTER COLUMN long_term_memory_retrieved_count SET DEFAULT 0,
  ALTER COLUMN long_term_memory_retrieved_count SET NOT NULL,
  ALTER COLUMN long_term_memory_injected_characters SET DEFAULT 0,
  ALTER COLUMN long_term_memory_injected_characters SET NOT NULL,
  ALTER COLUMN human_intervention_enabled SET DEFAULT FALSE,
  ALTER COLUMN human_intervention_enabled SET NOT NULL,
  ALTER COLUMN maximum_human_interventions SET DEFAULT 4,
  ALTER COLUMN maximum_human_interventions SET NOT NULL,
  ALTER COLUMN human_intervention_timeout_seconds SET DEFAULT 3600,
  ALTER COLUMN human_intervention_timeout_seconds SET NOT NULL,
  ALTER COLUMN human_intervention_timeout_action SET DEFAULT 'FAIL',
  ALTER COLUMN human_intervention_timeout_action SET NOT NULL,
  ALTER COLUMN human_intervention_count SET DEFAULT 0,
  ALTER COLUMN human_intervention_count SET NOT NULL,
  ALTER COLUMN pause_requested SET DEFAULT FALSE,
  ALTER COLUMN pause_requested SET NOT NULL;

CREATE TABLE IF NOT EXISTS simpoint_ai_agent_memories (
  id VARCHAR(64) PRIMARY KEY,
  agent_id VARCHAR(64) NOT NULL,
  agent_version_id VARCHAR(64) NOT NULL,
  source_execution_id VARCHAR(64) NOT NULL,
  scope_type VARCHAR(16) NOT NULL,
  tenant_id VARCHAR(64),
  memory_scope VARCHAR(16) NOT NULL,
  subject_id VARCHAR(64) NOT NULL,
  content TEXT NOT NULL,
  content_tsv TSVECTOR GENERATED ALWAYS AS (
    to_tsvector('simple', content)
  ) STORED,
  content_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  deleted_at TIMESTAMP WITH TIME ZONE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_agent_memory_execution
  ON simpoint_ai_agent_memories (source_execution_id)
  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_agent_memory_boundary
  ON simpoint_ai_agent_memories (
    agent_id, scope_type, tenant_id, memory_scope, subject_id, created_at
  )
  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_agent_memory_tsv
  ON simpoint_ai_agent_memories USING GIN (content_tsv);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_agent_memory_trgm
  ON simpoint_ai_agent_memories USING GIN (content gin_trgm_ops);
ALTER TABLE simpoint_ai_agent_memories
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_memory_scope;
ALTER TABLE simpoint_ai_agent_memories
  ADD CONSTRAINT ck_simpoint_ai_agent_memory_scope
  CHECK (
    (
      scope_type = 'SYSTEM'
      AND tenant_id IS NULL
    )
    OR (
      scope_type = 'TENANT'
      AND tenant_id IS NOT NULL
    )
  );

CREATE TABLE IF NOT EXISTS simpoint_ai_agent_human_interventions (
  id VARCHAR(64) PRIMARY KEY,
  agent_id VARCHAR(64) NOT NULL,
  execution_id VARCHAR(64) NOT NULL,
  scope_type VARCHAR(16) NOT NULL,
  tenant_id VARCHAR(64),
  status VARCHAR(16) NOT NULL,
  prompt VARCHAR(1024) NOT NULL,
  requested_by VARCHAR(64) NOT NULL,
  requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
  waiting_at TIMESTAMP WITH TIME ZONE,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  outcome VARCHAR(16),
  response_json TEXT,
  response_comment VARCHAR(1024),
  responded_by VARCHAR(64),
  responded_at TIMESTAMP WITH TIME ZONE,
  completion_reason VARCHAR(1024),
  create_org_dept_id VARCHAR(64),
  created_by VARCHAR(64),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(64),
  updated_at TIMESTAMP WITH TIME ZONE,
  deleted_at TIMESTAMP WITH TIME ZONE,
  lock_version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS simpoint_ai_agent_execution_events (
  id VARCHAR(64) PRIMARY KEY,
  agent_id VARCHAR(64) NOT NULL,
  agent_version_id VARCHAR(64) NOT NULL,
  execution_id VARCHAR(64) NOT NULL,
  scope_type VARCHAR(16) NOT NULL,
  tenant_id VARCHAR(64),
  event_sequence INTEGER NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  execution_status VARCHAR(24) NOT NULL,
  trace_id VARCHAR(64),
  intervention_id VARCHAR(64),
  actor_id VARCHAR(64),
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  payload_json TEXT,
  create_org_dept_id VARCHAR(64),
  created_by VARCHAR(64),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(64),
  updated_at TIMESTAMP WITH TIME ZONE,
  deleted_at TIMESTAMP WITH TIME ZONE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_agent_event_sequence
  ON simpoint_ai_agent_execution_events (execution_id, event_sequence)
  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_agent_event_agent
  ON simpoint_ai_agent_execution_events (agent_id, occurred_at)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_agent_execution_events
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_event_scope;
ALTER TABLE simpoint_ai_agent_execution_events
  ADD CONSTRAINT ck_simpoint_ai_agent_event_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_agent_execution_events
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_event_type;
ALTER TABLE simpoint_ai_agent_execution_events
  ADD CONSTRAINT ck_simpoint_ai_agent_event_type
  CHECK (
    event_type IN (
      'EXECUTION_CREATED',
      'EXECUTION_STARTED',
      'EXECUTION_SUCCEEDED',
      'EXECUTION_FAILED',
      'EXECUTION_CANCELLED',
      'APPROVAL_GRANTED',
      'APPROVAL_REJECTED',
      'PAUSE_REQUESTED',
      'PAUSED',
      'RESUMED',
      'MODEL_STARTED',
      'MODEL_SUCCEEDED',
      'MODEL_FAILED',
      'SKILL_STARTED',
      'SKILL_SUCCEEDED',
      'SKILL_FAILED',
      'MEMORY_RETRIEVED',
      'MEMORY_WRITTEN',
      'HUMAN_INTERVENTION_REQUESTED',
      'HUMAN_INTERVENTION_WAITING',
      'HUMAN_INTERVENTION_COMPLETED',
      'HUMAN_INTERVENTION_CANCELLED',
      'HUMAN_INTERVENTION_EXPIRED'
    )
  );
ALTER TABLE simpoint_ai_agent_execution_events
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_event_state;
ALTER TABLE simpoint_ai_agent_execution_events
  ADD CONSTRAINT ck_simpoint_ai_agent_event_state
  CHECK (
    event_sequence >= 0
    AND execution_status IN (
      'WAITING_APPROVAL',
      'PENDING',
      'RUNNING',
      'WAITING_SKILL',
      'WAITING_HUMAN',
      'PAUSED',
      'SUCCEEDED',
      'FAILED',
      'REJECTED',
      'CANCELLED'
    )
  );
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_agent_intervention_execution
  ON simpoint_ai_agent_human_interventions (execution_id, requested_at)
  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_agent_intervention_status
  ON simpoint_ai_agent_human_interventions (status, expires_at)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_agent_human_interventions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_intervention_scope;
ALTER TABLE simpoint_ai_agent_human_interventions
  ADD CONSTRAINT ck_simpoint_ai_agent_intervention_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_agent_human_interventions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_intervention_state;
ALTER TABLE simpoint_ai_agent_human_interventions
  ADD CONSTRAINT ck_simpoint_ai_agent_intervention_state
  CHECK (
    status IN ('REQUESTED', 'WAITING', 'COMPLETED', 'EXPIRED', 'CANCELLED')
    AND expires_at > requested_at
    AND (
      status <> 'WAITING'
      OR waiting_at IS NOT NULL
    )
    AND (
      status <> 'COMPLETED'
      OR (
        outcome = 'CONTINUE'
        AND response_json IS NOT NULL
        AND responded_by IS NOT NULL
        AND responded_at IS NOT NULL
      )
    )
    AND (
      outcome IS NULL
      OR outcome IN ('CONTINUE', 'CANCEL')
    )
  );
ALTER TABLE simpoint_ai_agent_memories
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_memory_boundary;
ALTER TABLE simpoint_ai_agent_memories
  ADD CONSTRAINT ck_simpoint_ai_agent_memory_boundary
  CHECK (
    memory_scope = 'SUBJECT'
    AND length(subject_id) > 0
    AND content_hash ~ '^[0-9a-f]{64}$'
    AND expires_at > created_at
  );

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_agent_execution_idempotency
  ON simpoint_ai_agent_executions
    (agent_id, scope_type, COALESCE(tenant_id, ''), idempotency_key_hash)
  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_agent_execution_version
  ON simpoint_ai_agent_executions (agent_version_id, created_at);
ALTER TABLE simpoint_ai_agent_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_execution_scope;
ALTER TABLE simpoint_ai_agent_executions
  ADD CONSTRAINT ck_simpoint_ai_agent_execution_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_agent_executions
  DROP CONSTRAINT IF EXISTS simpoint_ai_agent_executions_status_check;
ALTER TABLE simpoint_ai_agent_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_execution_status;
ALTER TABLE simpoint_ai_agent_executions
  ADD CONSTRAINT ck_simpoint_ai_agent_execution_status
  CHECK (
    status IN (
      'WAITING_APPROVAL',
      'PENDING',
      'RUNNING',
      'WAITING_SKILL',
      'WAITING_HUMAN',
      'PAUSED',
      'SUCCEEDED',
      'FAILED',
      'REJECTED',
      'CANCELLED'
    )
  );
ALTER TABLE simpoint_ai_agent_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_execution_hashes;
ALTER TABLE simpoint_ai_agent_executions
  ADD CONSTRAINT ck_simpoint_ai_agent_execution_hashes
  CHECK (
    agent_version_content_hash ~ '^[0-9a-f]{64}$'
    AND idempotency_key_hash ~ '^[0-9a-f]{64}$'
    AND input_hash ~ '^[0-9a-f]{64}$'
  );
ALTER TABLE simpoint_ai_agent_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_execution_budget;
ALTER TABLE simpoint_ai_agent_executions
  ADD CONSTRAINT ck_simpoint_ai_agent_execution_budget
  CHECK (
    step_count >= 0
    AND loop_depth >= 0
    AND maximum_steps > 0
    AND maximum_loop_depth >= 0
    AND maximum_concurrency > 0
    AND maximum_input_tokens > 0
    AND maximum_output_tokens > 0
    AND maximum_cost >= 0
    AND consumed_input_tokens >= 0
    AND consumed_output_tokens >= 0
    AND consumed_cost >= 0
    AND maximum_memory_messages > 0
    AND maximum_memory_summary_characters >= 1024
    AND compacted_message_count >= 0
    AND memory_revision >= 0
    AND long_term_memory_scope = 'SUBJECT'
    AND maximum_long_term_memory_entries BETWEEN 1 AND 10000
    AND long_term_memory_retrieval_top_k BETWEEN 1 AND 20
    AND long_term_memory_score_threshold BETWEEN 0 AND 1
    AND maximum_long_term_memory_injection_characters
      BETWEEN 512 AND 32768
    AND maximum_long_term_memory_record_characters
      BETWEEN 512 AND 32768
    AND long_term_memory_retention_days BETWEEN 1 AND 3650
    AND long_term_memory_retrieved_count >= 0
    AND long_term_memory_injected_characters >= 0
    AND maximum_human_interventions BETWEEN 1 AND 32
    AND human_intervention_timeout_seconds BETWEEN 60 AND 604800
    AND human_intervention_timeout_action IN ('FAIL', 'CANCEL')
    AND human_intervention_count BETWEEN 0 AND maximum_human_interventions
    AND attempt_count >= 0
    AND lease_token >= 0
  );
ALTER TABLE simpoint_ai_agent_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_execution_lease;
ALTER TABLE simpoint_ai_agent_executions
  ADD CONSTRAINT ck_simpoint_ai_agent_execution_lease
  CHECK (
    status <> 'RUNNING'
    OR (
      lease_owner IS NOT NULL
      AND lease_expires_at IS NOT NULL
    )
  );
ALTER TABLE simpoint_ai_agent_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_execution_memory;
ALTER TABLE simpoint_ai_agent_executions
  ADD CONSTRAINT ck_simpoint_ai_agent_execution_memory
  CHECK (
    maximum_memory_messages >= maximum_concurrency + 2
    AND (
      memory_summary_hash IS NULL
      OR memory_summary_hash ~ '^[0-9a-f]{64}$'
    )
    AND (
      (
        long_term_memory_context_json IS NULL
        AND long_term_memory_snapshot_hash IS NULL
        AND long_term_memory_retrieved_at IS NULL
        AND long_term_memory_retrieved_count = 0
        AND long_term_memory_injected_characters = 0
      )
      OR (
        long_term_memory_context_json IS NOT NULL
        AND long_term_memory_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND long_term_memory_retrieved_at IS NOT NULL
      )
    )
    AND (
      (
        long_term_memory_written_id IS NULL
        AND long_term_memory_written_at IS NULL
      )
      OR (
        long_term_memory_written_id IS NOT NULL
        AND long_term_memory_written_at IS NOT NULL
      )
    )
  );
ALTER TABLE simpoint_ai_agent_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_execution_pause;
ALTER TABLE simpoint_ai_agent_executions
  ADD CONSTRAINT ck_simpoint_ai_agent_execution_pause
  CHECK (
    (
      pause_requested = FALSE
      OR (
        pause_requested_at IS NOT NULL
        AND pause_requested_by IS NOT NULL
        AND status IN ('RUNNING', 'PAUSED')
      )
    )
    AND (
      status <> 'PAUSED'
      OR (
        pause_requested = TRUE
        AND paused_at IS NOT NULL
      )
    )
  );
ALTER TABLE simpoint_ai_agent_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_execution_intervention;
ALTER TABLE simpoint_ai_agent_executions
  ADD CONSTRAINT ck_simpoint_ai_agent_execution_intervention
  CHECK (
    (
      current_human_intervention_id IS NULL
      OR human_intervention_enabled = TRUE
    )
    AND (
      status <> 'WAITING_HUMAN'
      OR (
        human_intervention_enabled = TRUE
        AND current_human_intervention_id IS NOT NULL
        AND next_poll_at IS NOT NULL
        AND lease_owner IS NULL
        AND lease_expires_at IS NULL
      )
    )
  );
ALTER TABLE simpoint_ai_agent_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_execution_approval;
ALTER TABLE simpoint_ai_agent_executions
  ADD CONSTRAINT ck_simpoint_ai_agent_execution_approval
  CHECK (
    (self_approval_allowed = FALSE OR approval_required = TRUE)
    AND (
      status <> 'WAITING_APPROVAL'
      OR (
        approval_required = TRUE
        AND approval_requested_at IS NOT NULL
        AND approved_at IS NULL
        AND rejected_at IS NULL
      )
    )
    AND (
      status <> 'REJECTED'
      OR (
        approval_required = TRUE
        AND rejected_at IS NOT NULL
        AND rejected_by IS NOT NULL
      )
    )
    AND (approved_at IS NULL OR approved_by IS NOT NULL)
    AND (rejected_at IS NULL OR rejected_by IS NOT NULL)
    AND NOT (approved_at IS NOT NULL AND rejected_at IS NOT NULL)
  );
ALTER TABLE simpoint_ai_agent_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_execution_skill_wait;
ALTER TABLE simpoint_ai_agent_executions
  ADD CONSTRAINT ck_simpoint_ai_agent_execution_skill_wait
  CHECK (
    status <> 'WAITING_SKILL'
    OR (
      current_skill_binding_id IS NOT NULL
      AND current_skill_execution_id IS NOT NULL
      AND current_tool_call_id IS NOT NULL
      AND current_trace_id IS NOT NULL
      AND next_poll_at IS NOT NULL
    )
  );

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_agent_trace_sequence
  ON simpoint_ai_agent_execution_traces (execution_id, trace_sequence)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_agent_execution_traces
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_trace_type;
ALTER TABLE simpoint_ai_agent_execution_traces
  ADD CONSTRAINT ck_simpoint_ai_agent_trace_type
  CHECK (trace_type IN ('MODEL', 'SKILL'));
ALTER TABLE simpoint_ai_agent_execution_traces
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_trace_status;
ALTER TABLE simpoint_ai_agent_execution_traces
  ADD CONSTRAINT ck_simpoint_ai_agent_trace_status
  CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED'));
ALTER TABLE simpoint_ai_agent_execution_traces
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_trace_hashes;
ALTER TABLE simpoint_ai_agent_execution_traces
  ADD CONSTRAINT ck_simpoint_ai_agent_trace_hashes
  CHECK (
    request_hash ~ '^[0-9a-f]{64}$'
    AND (
      response_hash IS NULL
      OR response_hash ~ '^[0-9a-f]{64}$'
    )
  );
ALTER TABLE simpoint_ai_agent_execution_traces
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_agent_trace_target;
ALTER TABLE simpoint_ai_agent_execution_traces
  ADD CONSTRAINT ck_simpoint_ai_agent_trace_target
  CHECK (
    (
      trace_type = 'MODEL'
      AND model_definition_id IS NOT NULL
      AND skill_execution_id IS NULL
    )
    OR (
      trace_type = 'SKILL'
      AND model_definition_id IS NULL
      AND skill_binding_id IS NOT NULL
      AND skill_id IS NOT NULL
      AND skill_version_id IS NOT NULL
      AND (
        skill_execution_id IS NOT NULL
        OR (
          skill_execution_id IS NULL
          AND status = 'FAILED'
          AND error_code = 'AGENT_SKILL_ARGUMENTS_INVALID'
        )
      )
      AND capability_alias IS NOT NULL
      AND tool_call_id IS NOT NULL
    )
  );

-- Agent Workflow registry. Definitions own mutable display metadata, while
-- versions and exact Agent/Skill dependency bindings are immutable.
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_workflow_system_code
  ON simpoint_ai_workflows (code)
  WHERE scope_type = 'SYSTEM'
    AND tenant_id IS NULL
    AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_workflow_tenant_code
  ON simpoint_ai_workflows (tenant_id, code)
  WHERE scope_type = 'TENANT'
    AND tenant_id IS NOT NULL
    AND deleted_at IS NULL;
ALTER TABLE simpoint_ai_workflows
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_scope;
ALTER TABLE simpoint_ai_workflows
  ADD CONSTRAINT ck_simpoint_ai_workflow_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_workflows
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_status;
ALTER TABLE simpoint_ai_workflows
  ADD CONSTRAINT ck_simpoint_ai_workflow_status
  CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED'));

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_workflow_version_name
  ON simpoint_ai_workflow_versions (workflow_id, version_name)
  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_workflow_version_hash
  ON simpoint_ai_workflow_versions (content_hash);
ALTER TABLE simpoint_ai_workflow_versions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_version_scope;
ALTER TABLE simpoint_ai_workflow_versions
  ADD CONSTRAINT ck_simpoint_ai_workflow_version_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_workflow_versions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_version_status;
ALTER TABLE simpoint_ai_workflow_versions
  ADD CONSTRAINT ck_simpoint_ai_workflow_version_status
  CHECK (status IN ('DRAFT', 'PUBLISHED', 'DEPRECATED'));
ALTER TABLE simpoint_ai_workflow_versions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_version_hash;
ALTER TABLE simpoint_ai_workflow_versions
  ADD CONSTRAINT ck_simpoint_ai_workflow_version_hash
  CHECK (content_hash ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_workflow_dependency_order
  ON simpoint_ai_workflow_dependencies (workflow_version_id, binding_order)
  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_workflow_dependency_version
  ON simpoint_ai_workflow_dependencies (resource_version_id);
ALTER TABLE simpoint_ai_workflow_dependencies
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_dependency_scope;
ALTER TABLE simpoint_ai_workflow_dependencies
  ADD CONSTRAINT ck_simpoint_ai_workflow_dependency_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_workflow_dependencies
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_dependency_type;
ALTER TABLE simpoint_ai_workflow_dependencies
  ADD CONSTRAINT ck_simpoint_ai_workflow_dependency_type
  CHECK (dependency_type IN ('AGENT', 'SKILL', 'COMPENSATION_SKILL'));
ALTER TABLE simpoint_ai_workflow_dependencies
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_dependency_hash;
ALTER TABLE simpoint_ai_workflow_dependencies
  ADD CONSTRAINT ck_simpoint_ai_workflow_dependency_hash
  CHECK (
    binding_order >= 0
    AND resource_content_hash ~ '^[0-9a-f]{64}$'
  );

-- Durable Workflow executions. Idempotency is scope-aware, leases are fenced,
-- node checkpoints are unique, and the event stream is append-only by sequence.
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_workflow_execution_system_key
  ON simpoint_ai_workflow_executions (workflow_id, idempotency_key_hash)
  WHERE scope_type = 'SYSTEM'
    AND tenant_id IS NULL
    AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_workflow_execution_tenant_key
  ON simpoint_ai_workflow_executions
    (workflow_id, tenant_id, idempotency_key_hash)
  WHERE scope_type = 'TENANT'
    AND tenant_id IS NOT NULL
    AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_workflow_execution_due
  ON simpoint_ai_workflow_executions
    (status, next_poll_at, lease_expires_at);
ALTER TABLE simpoint_ai_workflow_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_execution_scope;
ALTER TABLE simpoint_ai_workflow_executions
  ADD CONSTRAINT ck_simpoint_ai_workflow_execution_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_workflow_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_execution_status;
ALTER TABLE simpoint_ai_workflow_executions
  ADD CONSTRAINT ck_simpoint_ai_workflow_execution_status
  CHECK (
    status IN (
      'PENDING', 'RUNNING', 'WAITING_CHILD', 'WAITING_HUMAN',
      'WAITING_TIMER', 'PAUSED', 'COMPENSATING', 'SUCCEEDED',
      'FAILED', 'CANCELLED'
    )
  );
ALTER TABLE simpoint_ai_workflow_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_execution_integrity;
ALTER TABLE simpoint_ai_workflow_executions
  ADD CONSTRAINT ck_simpoint_ai_workflow_execution_integrity
  CHECK (
    workflow_version_content_hash ~ '^[0-9a-f]{64}$'
    AND idempotency_key_hash ~ '^[0-9a-f]{64}$'
    AND input_hash ~ '^[0-9a-f]{64}$'
    AND maximum_duration_seconds > 0
    AND maximum_node_executions > 0
    AND maximum_parallelism > 0
    AND consumed_node_executions >= 0
    AND consumed_node_executions <= maximum_node_executions
    AND attempt_count >= 0
    AND lease_token >= 0
  );
ALTER TABLE simpoint_ai_workflow_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_execution_lease;
ALTER TABLE simpoint_ai_workflow_executions
  ADD CONSTRAINT ck_simpoint_ai_workflow_execution_lease
  CHECK (
    (lease_owner IS NULL AND lease_expires_at IS NULL)
    OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
  );
ALTER TABLE simpoint_ai_workflow_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_execution_pause;
ALTER TABLE simpoint_ai_workflow_executions
  ADD CONSTRAINT ck_simpoint_ai_workflow_execution_pause
  CHECK (
    (
      pause_requested = FALSE
      OR (
        pause_requested_at IS NOT NULL
        AND pause_requested_by IS NOT NULL
      )
    )
    AND (
      status <> 'PAUSED'
      OR (
        pause_requested = TRUE
        AND paused_at IS NOT NULL
      )
    )
  );

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_workflow_node_checkpoint
  ON simpoint_ai_workflow_node_executions (execution_id, node_id)
  WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_workflow_node_order
  ON simpoint_ai_workflow_node_executions (execution_id, node_order)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_workflow_node_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_node_status;
ALTER TABLE simpoint_ai_workflow_node_executions
  ADD CONSTRAINT ck_simpoint_ai_workflow_node_status
  CHECK (
    status IN (
      'PENDING', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED',
      'SKIPPED', 'COMPENSATING', 'COMPENSATED',
      'COMPENSATION_FAILED'
    )
  );
ALTER TABLE simpoint_ai_workflow_node_executions
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_node_integrity;
ALTER TABLE simpoint_ai_workflow_node_executions
  ADD CONSTRAINT ck_simpoint_ai_workflow_node_integrity
  CHECK (
    node_order >= 0
    AND attempt_count >= 0
    AND node_type IN (
      'agent', 'skill', 'human', 'wait', 'condition', 'parallel', 'end'
    )
    AND (output_hash IS NULL OR output_hash ~ '^[0-9a-f]{64}$')
    AND (
      child_type IS NULL
      OR child_type IN ('AGENT', 'SKILL')
    )
    AND (
      compensation_content_hash IS NULL
      OR compensation_content_hash ~ '^[0-9a-f]{64}$'
    )
  );

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_workflow_human_node
  ON simpoint_ai_workflow_human_tasks (node_execution_id)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_workflow_human_tasks
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_human_scope;
ALTER TABLE simpoint_ai_workflow_human_tasks
  ADD CONSTRAINT ck_simpoint_ai_workflow_human_scope
  CHECK (
    (scope_type = 'SYSTEM' AND tenant_id IS NULL)
    OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL)
  );
ALTER TABLE simpoint_ai_workflow_human_tasks
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_human_state;
ALTER TABLE simpoint_ai_workflow_human_tasks
  ADD CONSTRAINT ck_simpoint_ai_workflow_human_state
  CHECK (
    status IN ('OPEN', 'COMPLETED', 'TIMED_OUT', 'CANCELLED')
    AND timeout_action IN ('FAIL', 'CANCEL', 'CONTINUE')
    AND (output_hash IS NULL OR output_hash ~ '^[0-9a-f]{64}$')
    AND (
      status = 'OPEN'
      OR (resolved_at IS NOT NULL AND resolved_by IS NOT NULL)
    )
  );

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_workflow_event_sequence
  ON simpoint_ai_workflow_execution_events (execution_id, event_sequence)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_workflow_execution_events
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_workflow_event_integrity;
ALTER TABLE simpoint_ai_workflow_execution_events
  ADD CONSTRAINT ck_simpoint_ai_workflow_event_integrity
  CHECK (
    event_sequence > 0
    AND execution_status IN (
      'PENDING', 'RUNNING', 'WAITING_CHILD', 'WAITING_HUMAN',
      'WAITING_TIMER', 'PAUSED', 'COMPENSATING', 'SUCCEEDED',
      'FAILED', 'CANCELLED'
    )
  );

-- MCP Tasks is a northbound compatibility projection. Authorization ownership,
-- expiry, terminal-state immutability, and horizontal worker fencing are
-- enforced independently from the internal Workflow state machine.
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_mcp_task_due
  ON simpoint_ai_mcp_tasks (status, next_attempt_at, lease_expires_at);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_mcp_task_owner_cursor
  ON simpoint_ai_mcp_tasks
    (publication_code, subject_hash, client_hash, created_at DESC, id DESC);
ALTER TABLE simpoint_ai_mcp_tasks
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_mcp_task_status;
ALTER TABLE simpoint_ai_mcp_tasks
  ADD CONSTRAINT ck_simpoint_ai_mcp_task_status
  CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'));
ALTER TABLE simpoint_ai_mcp_tasks
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_mcp_task_integrity;
ALTER TABLE simpoint_ai_mcp_tasks
  ADD CONSTRAINT ck_simpoint_ai_mcp_task_integrity
  CHECK (
    subject_hash ~ '^[0-9a-f]{64}$'
    AND client_hash ~ '^[0-9a-f]{64}$'
    AND ttl_millis >= 1000
    AND poll_interval_millis > 0
    AND lease_token >= 0
    AND attempt_count >= 0
    AND (
      (lease_owner IS NULL AND lease_expires_at IS NULL)
      OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
    AND (
      status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
      OR completed_at IS NOT NULL
    )
  );

-- External extension catalog. Internal MCP Servers and Skills remain the source
-- of truth in their owning modules; only external Registry metadata is cached.
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_catalog_external_key
  ON simpoint_ai_catalog_entries (external_key)
  WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_catalog_sync_source
  ON simpoint_ai_catalog_sync_states (source)
  WHERE deleted_at IS NULL;
ALTER TABLE simpoint_ai_catalog_entries
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_catalog_entry_status;
ALTER TABLE simpoint_ai_catalog_entries
  ADD CONSTRAINT ck_simpoint_ai_catalog_entry_status
  CHECK (status IN ('ACTIVE', 'DELETED'));
ALTER TABLE simpoint_ai_catalog_sync_states
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_catalog_sync_status;
ALTER TABLE simpoint_ai_catalog_sync_states
  ADD CONSTRAINT ck_simpoint_ai_catalog_sync_status
  CHECK (status IN ('NEVER', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED'));
