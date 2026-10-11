-- Saved query-model drafts. Apply before starting the DNA service on installations without JPA DDL update.
CREATE TABLE IF NOT EXISTS simpoint_dna_data_models (
  id varchar(36) PRIMARY KEY,
  name varchar(120) NOT NULL,
  description varchar(500),
  model_type varchar(24) NOT NULL,
  data_source_id varchar(64),
  sql_text text,
  status varchar(20) NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  created_by varchar(64),
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  deleted_at timestamp with time zone,
  CONSTRAINT ck_dna_data_model_type CHECK (model_type IN ('DNA_SQL', 'NATIVE_SQL')),
  CONSTRAINT ck_dna_data_model_source CHECK (model_type <> 'NATIVE_SQL' OR data_source_id IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS idx_dna_model_updated ON simpoint_dna_data_models (deleted_at, updated_at);
CREATE INDEX IF NOT EXISTS idx_dna_model_type ON simpoint_dna_data_models (model_type, deleted_at);

-- The message initializer inserts missing keys; retain custom translations and update only the old built-in label.
UPDATE simpoint_i18n_messages SET message = '方言管理'
WHERE locale = 'zh-CN' AND code = 'menu.dna.dialects' AND message = '数据库方言管理';
UPDATE simpoint_i18n_messages SET message = 'Dialect Management'
WHERE locale = 'en-US' AND code = 'menu.dna.dialects' AND message = 'Database Dialect Management';
UPDATE simpoint_i18n_messages SET message = 'DNA SQL 模型'
WHERE locale = 'zh-CN' AND code = 'dna.models.type.dnaSql' AND message = 'DNA SQL';
UPDATE simpoint_i18n_messages SET message = 'Native SQL 原生模型'
WHERE locale = 'zh-CN' AND code = 'dna.models.type.nativeSql' AND message = 'Native SQL';
UPDATE simpoint_i18n_messages SET message = 'DNA SQL Model'
WHERE locale = 'en-US' AND code = 'dna.models.type.dnaSql' AND message = 'DNA SQL';
UPDATE simpoint_i18n_messages SET message = 'Native SQL Model'
WHERE locale = 'en-US' AND code = 'dna.models.type.nativeSql' AND message = 'Native SQL';
