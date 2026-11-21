CREATE TABLE simpoint_personal_workflow_message_fences (
  id VARCHAR(36) PRIMARY KEY,
  removed BOOLEAN NOT NULL
);
CREATE INDEX idx_personal_workflow_messages
  ON simpoint_personal_messages (workspace, source);
