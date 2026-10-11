-- PostgreSQL 17; run with psql -X -v ON_ERROR_STOP=1 --single-transaction -f ...
-- Maintenance window: stop old workflow delivery first; back up the database before running.
-- Only published, directed workflow notifications with matching durable delivery AND instance
-- ownership are moved. Unmatched/expired/revoked/deleted/global notices are deliberately untouched.
-- Old rows and receipts are retained. The ledger makes the exact migration targets auditable.
CREATE TABLE IF NOT EXISTS simpoint_workflow_message_migrations (
  notification_id VARCHAR(255) PRIMARY KEY,
  personal_message_id VARCHAR(255) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  previous_status VARCHAR(16) NOT NULL,
  previous_version BIGINT,
  personal_preexisting BOOLEAN NOT NULL,
  migrated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TEMPORARY TABLE workflow_message_candidates ON COMMIT DROP AS
SELECT n.id AS notification_id, n.version AS previous_version, n.status AS previous_status,
  COALESCE(m.id, CAST(CAST(md5('workflow-message:' || n.id) AS uuid) AS text)) AS personal_message_id,
  m.id IS NOT NULL AS personal_preexisting, o.tenant_id, o.target_user_id,
  n.title, n.content, n.priority,
  'notification:' || encode(sha256(convert_to(o.event_key, 'UTF8')), 'hex') AS source_key,
  COALESCE(n.published_at, n.created_at, o.created_at) AS created_at, receipt.read_at,
  CASE WHEN o.event_key LIKE 'task:%' THEN '/my/todos?task=' || task.ID_ || '&instance=' || i.process_instance_id
       WHEN split_part(o.event_key, ':', 3) = 'copied' THEN '/my/items?instance=' || i.process_instance_id || '&view=copied'
       ELSE '/my/items?instance=' || i.process_instance_id END AS link_path
FROM simpoint_system_notifications n
JOIN simpoint_workflow_notification_outbox o ON o.event_key = n.external_key
  AND o.target_user_id = n.audience_id AND o.status = 'SENT'
LEFT JOIN ACT_HI_TASKINST task ON o.event_key LIKE 'task:%'
  AND task.ID_ = split_part(o.event_key, ':', 2) AND task.TENANT_ID_ = o.tenant_id
JOIN simpoint_workflow_instances i ON i.tenant_id = o.tenant_id
  AND i.process_instance_id = CASE WHEN o.event_key LIKE 'task:%' THEN task.PROC_INST_ID_
                                  ELSE split_part(o.event_key, ':', 2) END
LEFT JOIN simpoint_notification_receipts receipt ON receipt.notification_id = n.id
  AND receipt.user_id = o.target_user_id AND receipt.deleted_at IS NULL
LEFT JOIN simpoint_personal_messages m ON m.owner_id = o.target_user_id
  AND m.workspace = 'TENANT:' || o.tenant_id AND m.source = 'workflow'
  AND m.source_key = 'notification:' || encode(sha256(convert_to(o.event_key, 'UTF8')), 'hex')
WHERE n.source = 'workflow' AND n.audience_type = 'USER' AND n.status = 'PUBLISHED'
  AND n.deleted_at IS NULL AND (n.expire_at IS NULL OR n.expire_at > CURRENT_TIMESTAMP)
  AND (n.publish_at IS NULL OR n.publish_at <= CURRENT_TIMESTAMP)
  AND NOT EXISTS (SELECT 1 FROM simpoint_workflow_message_migrations done WHERE done.notification_id = n.id)
  AND (o.event_key ~ '^task:[A-Za-z0-9_-]+:created:'
    OR o.event_key ~ '^instance:[A-Za-z0-9_-]+:(copied|completed|CANCELED|WITHDRAWN|REJECTED):')
  AND i.process_instance_id ~ '^[A-Za-z0-9_-]+$'
FOR UPDATE OF n;

INSERT INTO simpoint_personal_messages
  (id, owner_id, workspace, version, title, content, priority, source, source_key, link_path,
   read_at, archived, created_at, updated_at, created_by, updated_by)
SELECT personal_message_id, target_user_id, 'TENANT:' || tenant_id, 0, title, content,
  CASE priority WHEN 'LOW' THEN 0 WHEN 'HIGH' THEN 2 WHEN 'URGENT' THEN 3 ELSE 1 END,
  'workflow', source_key, link_path, read_at, false, created_at, CURRENT_TIMESTAMP,
  'workflow-message-migration', 'workflow-message-migration'
FROM workflow_message_candidates
ON CONFLICT (owner_id, workspace, source, source_key) DO UPDATE
  SET read_at = COALESCE(simpoint_personal_messages.read_at, EXCLUDED.read_at),
      version = simpoint_personal_messages.version + 1,
      updated_at = CURRENT_TIMESTAMP
  WHERE simpoint_personal_messages.read_at IS NULL AND EXCLUDED.read_at IS NOT NULL;

INSERT INTO simpoint_workflow_message_migrations
  (notification_id, personal_message_id, tenant_id, previous_status, previous_version, personal_preexisting, migrated_at)
SELECT c.notification_id, m.id, c.tenant_id, c.previous_status, c.previous_version, c.personal_preexisting, CURRENT_TIMESTAMP
FROM workflow_message_candidates c JOIN simpoint_personal_messages m
  ON m.owner_id = c.target_user_id AND m.workspace = 'TENANT:' || c.tenant_id
  AND m.source = 'workflow' AND m.source_key = c.source_key;

-- No deletion and no duplicate unread badge in the old global channel.
UPDATE simpoint_system_notifications n SET status = 'REVOKED', version = COALESCE(n.version, 0) + 1,
  updated_at = CURRENT_TIMESTAMP, updated_by = 'workflow-message-migration'
FROM workflow_message_candidates c JOIN simpoint_workflow_message_migrations done
  ON done.notification_id = c.notification_id
WHERE n.id = c.notification_id AND n.version IS NOT DISTINCT FROM c.previous_version;
