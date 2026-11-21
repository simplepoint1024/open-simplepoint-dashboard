package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Adding the ownership binding must not silently certify active or retained legacy models. */
class PhysicalNamespaceBindingMigrationDatabaseTest {
  @Test
  void upgradeRetainsLegacyBindingsWithoutInventingAnOwnershipIdentity() throws Exception {
    String url = System.getenv("DNA_RUNTIME_TEST_POSTGRES_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "Disposable PostgreSQL is required");
    String namespace = "sp_binding_" + UUID.randomUUID().toString().replace("-", "");
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
            List.of(
                "V1__dna_runtime_foundation.sql",
                "V2__durable_publication_plan.sql",
                "V3__relation_catalog.sql")) {
          ScriptUtils.executeSqlScript(connection, new ClassPathResource(root + file));
        }
        statement.executeUpdate(
            "INSERT INTO simpoint_dna_physical_models"
                + " (id,tenant_id,app_id,form_id,display_name,status,latest_version,active_version,created_at,updated_at)"
                + " VALUES"
                + " ('model','tenant','app','form','客户','ACTIVE',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        statement.executeUpdate(
            "INSERT INTO simpoint_dna_physical_bindings "
                + "(id,tenant_id,physical_model_id,data_source_id,catalog_name,schema_name,table_name,"
                + "isolation_mode,created_at,updated_at,deleted_at) VALUES"
                + " ('binding','tenant','model','source','catalog','forms','records',"
                + "'SHARED_DATABASE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        ScriptUtils.executeSqlScript(
            connection, new ClassPathResource(root + "V4__physical_namespace_binding.sql"));
        try (var rows =
            statement.executeQuery(
                "SELECT tenant_id,data_source_id,table_name,physical_namespace_id,deleted_at "
                    + "FROM simpoint_dna_physical_bindings")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("tenant");
          assertThat(rows.getString(2)).isEqualTo("source");
          assertThat(rows.getString(3)).isEqualTo("records");
          assertThat(rows.getString(4)).isNull();
          assertThat(rows.getTimestamp(5)).isNotNull();
          assertThat(rows.next()).isFalse();
        }
        try (var rows =
            statement.executeQuery(
                "SELECT status,active_version FROM simpoint_dna_physical_models")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("ACTIVE");
          assertThat(rows.getInt(2)).isEqualTo(1);
        }
      } finally {
        statement.execute("SET search_path TO public");
        statement.execute("DROP SCHEMA " + namespace + " CASCADE");
      }
    }
  }
}
