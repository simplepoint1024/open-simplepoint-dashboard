-- Cleanup script for the removed DNA data-lineage feature.
--
-- Intended use:
--   1. Back up the target database.
--   2. Run this script once after deploying the code that removes data lineage.
--   3. Re-open the DNA workbench and confirm the data-lineage menu is gone.
--
-- Notes:
--   - This project commonly uses Hibernate ddl-auto=update; that mode does not
--     drop tables or remove initialized RBAC rows when code/config disappears.
--   - The script uses temporary tables and standard DELETE/DROP statements that
--     work on MySQL 8+ and PostgreSQL. Adjust transaction syntax if your runtime
--     database has different DDL transaction behavior.

START TRANSACTION;

CREATE TEMPORARY TABLE IF NOT EXISTS sp_removed_dna_lineage_menus (
  id VARCHAR(36) PRIMARY KEY
);

CREATE TEMPORARY TABLE IF NOT EXISTS sp_removed_dna_lineage_permissions (
  authority VARCHAR(100) PRIMARY KEY
);

CREATE TEMPORARY TABLE IF NOT EXISTS sp_removed_dna_lineage_features (
  code VARCHAR(128) PRIMARY KEY
);

DELETE FROM sp_removed_dna_lineage_features;
DELETE FROM sp_removed_dna_lineage_permissions;
DELETE FROM sp_removed_dna_lineage_menus;

INSERT INTO sp_removed_dna_lineage_menus (id)
SELECT id
FROM simpoint_ac_menus
WHERE authority IN ('dna.data-lineage.view', 'dna.dataLineage.view')
   OR path = '/dna/data-lineage'
   OR component = 'dna/platform/DataLineage';

INSERT INTO sp_removed_dna_lineage_permissions (authority)
VALUES
  ('dna.data-lineage.view'),
  ('dna.data-lineage.create'),
  ('dna.data-lineage.edit'),
  ('dna.data-lineage.delete'),
  ('dna.dataLineage.view'),
  ('dna.dataLineage.create'),
  ('dna.dataLineage.edit'),
  ('dna.dataLineage.delete');

INSERT INTO sp_removed_dna_lineage_features (code)
VALUES
  ('dna-data-lineage'),
  ('dna.data-lineage'),
  ('dna.dataLineage'),
  ('dna.data-lineage.view'),
  ('dna.dataLineage.view');

DELETE FROM simpoint_ac_menu_ancestors_rel
WHERE child_id IN (SELECT id FROM sp_removed_dna_lineage_menus)
   OR ancestor_id IN (SELECT id FROM sp_removed_dna_lineage_menus);

DELETE FROM simpoint_ac_permissions_menu_rel
WHERE menu_id IN (SELECT id FROM sp_removed_dna_lineage_menus)
   OR permission_authority IN (SELECT code FROM sp_removed_dna_lineage_features);

DELETE FROM simpoint_saas_feature_permission_rel
WHERE feature_code IN (SELECT code FROM sp_removed_dna_lineage_features)
   OR permission_authority IN (SELECT authority FROM sp_removed_dna_lineage_permissions);

DELETE FROM simpoint_saas_application_feature_rel
WHERE feature_code IN (SELECT code FROM sp_removed_dna_lineage_features);

DELETE FROM simpoint_ac_permissions_role_rel
WHERE permission_authority IN (SELECT authority FROM sp_removed_dna_lineage_permissions);

DELETE FROM simpoint_ac_resources_rel
WHERE permission_authority IN (SELECT authority FROM sp_removed_dna_lineage_permissions);

DELETE FROM simpoint_ac_permissions
WHERE authority IN (SELECT authority FROM sp_removed_dna_lineage_permissions);

DELETE FROM simpoint_saas_features
WHERE code IN (SELECT code FROM sp_removed_dna_lineage_features);

DELETE FROM simpoint_ac_menus
WHERE id IN (SELECT id FROM sp_removed_dna_lineage_menus);

DROP TABLE IF EXISTS simpoint_dna_data_lineage_edges;
DROP TABLE IF EXISTS simpoint_dna_data_lineage_nodes;

DROP TABLE IF EXISTS sp_removed_dna_lineage_features;
DROP TABLE IF EXISTS sp_removed_dna_lineage_permissions;
DROP TABLE IF EXISTS sp_removed_dna_lineage_menus;

COMMIT;
