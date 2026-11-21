package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Verifies the immutable V1 -> V2 control-schema upgrade against a real PostgreSQL database. */
class SchemaPublicationPlanMigrationDatabaseTest {
  @Test
  void upgradePreservesLegacyMigrationAndRequiresBothPlanColumns() throws Exception {
    String url = System.getenv("DNA_RUNTIME_TEST_POSTGRES_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "Disposable database is required");
    var source = new DriverManagerDataSource(url, System.getenv("DNA_RUNTIME_TEST_POSTGRES_USER"),
        System.getenv("DNA_RUNTIME_TEST_POSTGRES_PASSWORD"));
    String namespace = "sp_plan_" + UUID.randomUUID().toString().replace("-", "");
    String root = "META-INF/simplepoint/modules/dna-runtime/db/postgresql/";
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + namespace);
      try {
        statement.execute("SET search_path TO " + namespace);
        ScriptUtils.executeSqlScript(connection, new ClassPathResource(root + "V1__dna_runtime_foundation.sql"));
        statement.executeUpdate("INSERT INTO simpoint_dna_physical_models "
            + "(id,tenant_id,app_id,form_id,display_name,status,latest_version,created_at,updated_at) "
            + "VALUES ('model','tenant','app','form','客户','DRAFT',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        statement.executeUpdate("INSERT INTO simpoint_dna_schema_migrations "
            + "(id,tenant_id,physical_model_id,target_version,target_schema_checksum,status,execution_token,started_at,created_at,updated_at) "
            + "VALUES ('migration','tenant','model',1,'" + "a".repeat(64)
            + "','FAILED','old-worker',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        ScriptUtils.executeSqlScript(connection, new ClassPathResource(root + "V2__durable_publication_plan.sql"));
        try (var rows = statement.executeQuery("SELECT status,execution_token,plan_json,plan_checksum FROM simpoint_dna_schema_migrations")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("FAILED");
          assertThat(rows.getString(2)).isEqualTo("old-worker");
          assertThat(rows.getString(3)).isNull();
          assertThat(rows.getString(4)).isNull();
        }
        assertThatThrownBy(() -> statement.executeUpdate("UPDATE simpoint_dna_schema_migrations SET plan_json='{}'"))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> statement.executeUpdate("UPDATE simpoint_dna_schema_migrations SET plan_json='{}',plan_checksum='invalid'"))
            .isInstanceOf(SQLException.class);
        assertThat(statement.executeUpdate("UPDATE simpoint_dna_schema_migrations SET plan_json='{}',plan_checksum='"
            + "b".repeat(64) + "'")).isEqualTo(1);
      } finally {
        statement.execute("SET search_path TO public");
        statement.execute("DROP SCHEMA " + namespace + " CASCADE");
      }
    }
  }
}
