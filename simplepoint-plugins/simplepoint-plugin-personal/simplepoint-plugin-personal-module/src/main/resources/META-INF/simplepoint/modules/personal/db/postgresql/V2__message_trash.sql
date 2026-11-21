CREATE INDEX idx_personal_messages_trash_expiration
  ON simpoint_personal_messages (deleted_at, id)
  WHERE deleted_at IS NOT NULL;
