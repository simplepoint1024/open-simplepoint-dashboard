package org.simplepoint.plugin.dna.runtime.service.relation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Legacy catalogs are adopted only when no governed physical reference has already been installed.
 */
class PhysicalRelationCatalogMigrationDatabaseTest {
  @ParameterizedTest
  @ValueSource(strings = {"absent", "null", "empty", "draft", "active", "planned"})
  void upgradeSeedsTenantBarrierAndRejectsUnverifiedPublishedReferences(String kind)
      throws Exception {
    String url = System.getenv("DNA_RUNTIME_TEST_POSTGRES_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "Disposable PostgreSQL is required");
    String namespace = "sp_catalog_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    String root = "META-INF/simplepoint/modules/dna-runtime/db/postgresql/";
    try (var connection =
            DriverManager.getConnection(
                url,
                System.getenv("DNA_RUNTIME_TEST_POSTGRES_USER"),
                System.getenv("DNA_RUNTIME_TEST_POSTGRES_PASSWORD"));
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + namespace);
      try {
        statement.execute("SET search_path TO " + namespace);
        for (String file :
            List.of("V1__dna_runtime_foundation.sql", "V2__durable_publication_plan.sql")) {
          ScriptUtils.executeSqlScript(connection, new ClassPathResource(root + file));
        }
        boolean active = !kind.equals("draft") && !kind.equals("planned");
        statement.executeUpdate(
            "INSERT INTO simpoint_dna_physical_models"
                + " (id,tenant_id,app_id,form_id,display_name,status,latest_version,active_version,created_at,updated_at)"
                + " VALUES ('model','tenant','app','form','客户','"
                + (active ? "ACTIVE" : "DRAFT")
                + "',1,"
                + (active ? "1" : "NULL")
                + ",CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        String refs =
            switch (kind) {
              case "absent" -> "{}";
              case "null" -> "{\"references\":null}";
              case "empty" -> "{\"references\":[]}";
              default -> "{\"references\":[{\"relationId\":\"existing\"}]}";
            };
        try (var insert =
            connection.prepareStatement(
                "INSERT INTO simpoint_dna_physical_model_versions"
                    + " (id,tenant_id,physical_model_id,version_number,contract_version,schema_json,schema_checksum,created_at,updated_at)"
                    + " VALUES"
                    + " ('version','tenant','model',1,'1',?,'legacy',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
          insert.setString(1, refs);
          insert.executeUpdate();
        }
        if (kind.equals("planned")) {
          statement.executeUpdate(
              "INSERT INTO simpoint_dna_schema_migrations "
                  + "(id,tenant_id,physical_model_id,target_version,target_schema_checksum,status,execution_token,started_at,created_at,updated_at,plan_json,plan_checksum)"
                  + " VALUES ('migration','tenant','model',1,'legacy','FAILED',"
                  + "'old-worker',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'{}','"
                  + "a".repeat(64)
                  + "')");
        }
        connection.setAutoCommit(false);
        var migration = new ClassPathResource(root + "V3__relation_catalog.sql");
        if (kind.equals("active") || kind.equals("planned")) {
          assertThatThrownBy(() -> ScriptUtils.executeSqlScript(connection, migration))
              .rootCause()
              .isInstanceOf(java.sql.SQLException.class)
              .hasMessageContaining(
                  "Existing published references require verified catalog adoption");
          connection.rollback();
          try (var rows =
              statement.executeQuery("SELECT to_regclass('simpoint_dna_relation_catalogs')")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isNull();
          }
        } else {
          ScriptUtils.executeSqlScript(connection, migration);
          connection.commit();
          try (var rows =
              statement.executeQuery(
                  "SELECT tenant_id, revision FROM simpoint_dna_relation_catalogs")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("tenant");
            assertThat(rows.getLong(2)).isZero();
            assertThat(rows.next()).isFalse();
          }
          try (var rows =
              statement.executeQuery(
                  "SELECT schema_json FROM simpoint_dna_physical_model_versions")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(refs);
          }
        }
        connection.commit();
        connection.setAutoCommit(true);
      } finally {
        if (!connection.getAutoCommit()) connection.rollback();
        connection.setAutoCommit(true);
        statement.execute("SET search_path TO public");
        statement.execute("DROP SCHEMA " + namespace + " CASCADE");
      }
    }
  }
}
