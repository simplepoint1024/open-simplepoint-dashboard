CREATE TABLE @TABLE@ (
  entry_key VARCHAR(64) NOT NULL PRIMARY KEY,
  namespace_id VARCHAR(36) NOT NULL,
  contract_version INTEGER NOT NULL,
  schema_checksum VARCHAR(64) NOT NULL,
  object_name VARCHAR(255),
  owner_module VARCHAR(64),
  owner_tenant VARCHAR(255),
  owner_resource VARCHAR(128),
  created_at_ms BIGINT NOT NULL
)
