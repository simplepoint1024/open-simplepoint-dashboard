-- PostgreSQL migration: remove the retired AI orchestration features.
-- Stop all old AI/Agent/Workflow replicas and take a verified database backup first.
-- Deploy code without these entities before starting AI again: ddl-auto=update
-- does not remove old tables, and an old replica would recreate them.
-- Run: psql -X -v ON_ERROR_STOP=1 -d <verified_database> -f this-file.sql
-- No CASCADE: unexpected external dependencies must fail and roll back.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TEMPORARY TABLE sp_removed_ai_resources ON COMMIT DROP AS
SELECT id, code FROM public.simpoint_ac_resources
WHERE code ~ '^ai\.(workbench\.)?(skills|skill-designer|agents|workflows)(\.|$)'
   OR component ~ '^ai/workbench/(Skills|Agents|Workflows)(/|$)'
   OR path ~ '^/ai/workbench/(skills|skill-designer|agents|workflows)(/|$)';

DELETE FROM public.simpoint_ac_role_resource_grants
WHERE resource_code IN (SELECT code FROM sp_removed_ai_resources)
   OR resource_code ~ '^ai\.(workbench\.)?(skills|skill-designer|agents|workflows)(\.|$)';
DELETE FROM public.simpoint_saas_application_resource_rel
WHERE resource_code IN (SELECT code FROM sp_removed_ai_resources)
   OR resource_code ~ '^ai\.(workbench\.)?(skills|skill-designer|agents|workflows)(\.|$)';
DELETE FROM public.simpoint_ac_resource_ancestors_rel
WHERE ancestor_id IN (SELECT id FROM sp_removed_ai_resources)
   OR child_id IN (SELECT id FROM sp_removed_ai_resources);
DELETE FROM public.simpoint_ac_resources
WHERE id IN (SELECT id FROM sp_removed_ai_resources);

DELETE FROM public.simpoint_i18n_messages
WHERE namespace IN ('ai-skills', 'ai-agents', 'ai-workflows')
   OR code ~ '^menu\.ai\.(skills|skillDesigner|agents|workflows)$'
   OR (namespace = 'ai-common' AND code ~ '^ai\.(dependencies|schemaEditor)\.');
DELETE FROM public.simpoint_i18n_namespaces
WHERE code IN ('ai-skills', 'ai-agents', 'ai-workflows');

DELETE FROM public.simpoint_ai_catalog_entries WHERE kind = 'SKILL';
DELETE FROM public.simpoint_ai_mcp_tool_invocations
WHERE client_id = 'simplepoint-skill-workflow';
ALTER TABLE public.simpoint_ai_catalog_entries
  DROP CONSTRAINT IF EXISTS simpoint_ai_catalog_entries_kind_check;
ALTER TABLE public.simpoint_ai_catalog_entries
  ADD CONSTRAINT simpoint_ai_catalog_entries_kind_check CHECK (kind = 'MCP_SERVER');

DROP TABLE IF EXISTS public.simpoint_ai_agent_execution_events;
DROP TABLE IF EXISTS public.simpoint_ai_agent_execution_traces;
DROP TABLE IF EXISTS public.simpoint_ai_agent_executions;
DROP TABLE IF EXISTS public.simpoint_ai_agent_human_interventions;
DROP TABLE IF EXISTS public.simpoint_ai_agent_memories;
DROP TABLE IF EXISTS public.simpoint_ai_agent_skill_bindings;
DROP TABLE IF EXISTS public.simpoint_ai_agent_versions;
DROP TABLE IF EXISTS public.simpoint_ai_agents;
DROP TABLE IF EXISTS public.simpoint_ai_skill_draft_revisions;
DROP TABLE IF EXISTS public.simpoint_ai_skill_drafts;
DROP TABLE IF EXISTS public.simpoint_ai_skill_execution_events;
DROP TABLE IF EXISTS public.simpoint_ai_skill_execution_steps;
DROP TABLE IF EXISTS public.simpoint_ai_skill_executions;
DROP TABLE IF EXISTS public.simpoint_ai_skill_prompt_bindings;
DROP TABLE IF EXISTS public.simpoint_ai_skill_publish_tasks;
DROP TABLE IF EXISTS public.simpoint_ai_skill_resource_bindings;
DROP TABLE IF EXISTS public.simpoint_ai_skill_tool_bindings;
DROP TABLE IF EXISTS public.simpoint_ai_skill_versions;
DROP TABLE IF EXISTS public.simpoint_ai_skills;
DROP TABLE IF EXISTS public.simpoint_ai_workflow_dependencies;
DROP TABLE IF EXISTS public.simpoint_ai_workflow_execution_events;
DROP TABLE IF EXISTS public.simpoint_ai_workflow_executions;
DROP TABLE IF EXISTS public.simpoint_ai_workflow_human_tasks;
DROP TABLE IF EXISTS public.simpoint_ai_workflow_node_executions;
DROP TABLE IF EXISTS public.simpoint_ai_workflow_versions;
DROP TABLE IF EXISTS public.simpoint_ai_workflows;
DROP INDEX IF EXISTS public.idx_simpoint_ai_model_dependency_options;
COMMIT;
