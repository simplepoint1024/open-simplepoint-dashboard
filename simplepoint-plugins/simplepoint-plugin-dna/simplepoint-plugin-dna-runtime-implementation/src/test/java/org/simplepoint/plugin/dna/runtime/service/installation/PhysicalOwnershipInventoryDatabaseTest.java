package org.simplepoint.plugin.dna.runtime.service.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Real owned PostgreSQL schema, including records ordinary tenant/soft-delete queries omit. */
class PhysicalOwnershipInventoryDatabaseTest {
  @Test
  void databaseRowSecurityCannotSilentlyHideOtherTenantsFromInventory() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.model("visible", "ACTIVE");
      fixture.model("hidden", "ACTIVE");
      String role = "own_inv_" + UUID.randomUUID().toString().replace("-", "");
      String password = UUID.randomUUID().toString();
      try (var connection = fixture.source.getConnection();
          var statement = connection.createStatement()) {
        statement.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
      }
      try {
        fixture.jdbc.execute("GRANT USAGE ON SCHEMA " + fixture.schema + " TO " + role);
        fixture.jdbc.execute(
            "GRANT SELECT ON ALL TABLES IN SCHEMA " + fixture.schema + " TO " + role);
        for (String table :
            new String[] {"simpoint_dna_physical_models", "simpoint_dna_physical_bindings"}) {
          fixture.jdbc.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
          fixture.jdbc.execute(
              "CREATE POLICY visible_tenant ON " + table + " USING (tenant_id='visible')");
        }
        var source = new DriverManagerDataSource(fixture.source.getUrl(), role, password);
        assertThat(
                new JdbcTemplate(source)
                    .queryForObject(
                        "SELECT COUNT(*) FROM simpoint_dna_physical_bindings", Integer.class))
            .isEqualTo(1);
        var adapter = new PhysicalOwnershipInstallationAdapter(source);
        assertThatThrownBy(adapter::inventory)
            .hasMessage("DNA_OWNERSHIP_INVENTORY_UNAVAILABLE")
            .satisfies(
                failure ->
                    assertThat(((SQLException) failure.getCause()).getSQLState())
                        .isEqualTo("42501"));
        for (String table :
            new String[] {"simpoint_dna_physical_models", "simpoint_dna_physical_bindings"}) {
          fixture.jdbc.execute("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY");
        }
        assertThat(adapter.inventory().bindings()).hasSize(2);
      } finally {
        fixture.jdbc.execute("DROP OWNED BY " + role);
        fixture.jdbc.execute("DROP ROLE " + role);
      }
    }
  }

  @Test
  void inventoryIncludesAllTenantsRetiredModelsAndDeletedBindingsWithoutWriting() throws Exception {
    try (var fixture = new Fixture()) {
      final String first = fixture.model("tenant-a", "ACTIVE");
      String retired = fixture.model("tenant-b", "RETIRED");
      fixture.jdbc.update(
          "UPDATE simpoint_dna_physical_bindings SET deleted_at=TIMESTAMPTZ '2026-10-28"
              + " 01:00:00+00' WHERE physical_model_id=?",
          retired);
      fixture.jdbc.update(
          "UPDATE simpoint_dna_physical_models SET deleted_at=TIMESTAMPTZ '2026-10-28 01:00:00+00'"
              + " WHERE id=?",
          retired);
      var before =
          fixture.jdbc.queryForList("SELECT * FROM simpoint_dna_physical_bindings ORDER BY id");
      var inventory = fixture.adapter.inventory();
      assertThat(inventory.bindings()).hasSize(2);
      assertThat(inventory.bindings())
          .extracting(PhysicalBindingInventory.Binding::tenantId)
          .containsExactlyInAnyOrder("tenant-a", "tenant-b");
      assertThat(inventory.bindings())
          .allSatisfy(binding -> assertThat(binding.namespaceId()).isNull());
      assertThat(
              inventory.bindings().stream()
                  .filter(binding -> binding.modelId().equals(retired))
                  .findFirst()
                  .orElseThrow())
          .satisfies(
              binding -> {
                assertThat(binding.deletedAt()).isNotNull();
                assertThat(binding.model().deletedAt()).isNotNull();
                assertThat(binding.model().status()).isEqualTo(PhysicalModelStatus.RETIRED);
              });
      assertThat(
              fixture.jdbc.queryForList("SELECT * FROM simpoint_dna_physical_bindings ORDER BY id"))
          .isEqualTo(before);
      assertThat(fixture.adapter.inventory().fingerprint()).isEqualTo(inventory.fingerprint());
      fixture.jdbc.update(
          "UPDATE simpoint_dna_physical_models SET display_name='新名称' WHERE id=?", first);
      assertThat(fixture.adapter.inventory().fingerprint()).isEqualTo(inventory.fingerprint());
      fixture.jdbc.update(
          "UPDATE simpoint_dna_physical_bindings SET physical_namespace_id=?,"
              + " lock_version=lock_version+1 WHERE physical_model_id=?",
          UUID.randomUUID().toString(),
          first);
      assertThat(fixture.adapter.inventory().fingerprint()).isNotEqualTo(inventory.fingerprint());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-binding", "orphan-binding", "tenant-mismatch"})
  void inconsistentControlOwnershipCannotProduceAnApparentlyCompleteSnapshot(String corruption)
      throws Exception {
    try (var fixture = new Fixture()) {
      String id = fixture.model("tenant", "DRAFT");
      switch (corruption) {
        case "missing-binding" ->
            fixture.jdbc.update(
                "DELETE FROM simpoint_dna_physical_bindings WHERE physical_model_id=?", id);
        case "orphan-binding" -> {
          fixture.jdbc.execute(
              "ALTER TABLE simpoint_dna_physical_bindings DROP CONSTRAINT"
                  + " fk_dna_physical_binding_model");
          fixture.jdbc.update("DELETE FROM simpoint_dna_physical_models WHERE id=?", id);
        }
        case "tenant-mismatch" ->
            fixture.jdbc.update(
                "UPDATE simpoint_dna_physical_bindings SET tenant_id='different' WHERE"
                    + " physical_model_id=?",
                id);
        default -> throw new AssertionError("Unknown fixture corruption");
      }
      assertThatThrownBy(fixture.adapter::inventory)
          .hasMessage("DNA_OWNERSHIP_INVENTORY_INCOMPLETE_BINDING");
    }
  }

  @Test
  void maximumInventoryIsCompleteAndOneMoreRecordFailsInsteadOfTruncating() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.jdbc.execute(
          """
          INSERT INTO simpoint_dna_physical_models
            (id,tenant_id,app_id,form_id,display_name,status,latest_version,lock_version,created_at,updated_at)
          SELECT md5('model-'||i),'tenant','app','form-'||i,'Model','DRAFT',1,0,now(),now()
          FROM generate_series(1,10000) i
          """);
      fixture.jdbc.execute(
          """
          INSERT INTO simpoint_dna_physical_bindings
            (id,tenant_id,physical_model_id,data_source_id,catalog_name,schema_name,table_name,isolation_mode,
             lock_version,created_at,updated_at)
          SELECT md5('binding-'||i),'tenant',md5('model-'||i),'source','database','public','records_'||i,
            'SHARED_DATABASE',0,now(),now() FROM generate_series(1,10000) i
          """);
      var inventory = fixture.adapter.inventory();
      assertThat(inventory.bindings()).hasSize(PhysicalBindingInventory.MAX_BINDINGS);
      assertThat(inventory.fingerprint()).hasSize(64);
      fixture.model("tenant", "DRAFT");
      assertThatThrownBy(fixture.adapter::inventory).hasMessage("DNA_OWNERSHIP_INVENTORY_LIMIT");
      assertThat(
              fixture.jdbc.queryForObject(
                  "SELECT COUNT(*) FROM simpoint_dna_physical_bindings", Integer.class))
          .isEqualTo(10001);
    }
  }

  @Test
  void transitionalModelsAndExactNamespaceNamesAreRetainedForLaterAdoptionReview()
      throws Exception {
    try (var fixture = new Fixture()) {
      for (var status : PhysicalModelStatus.values()) {
        fixture.model("tenant", status.name());
      }
      fixture.jdbc.execute(
          "UPDATE simpoint_dna_physical_bindings SET schema_name=' 顧客 ', catalog_name=''");
      var inventory = fixture.adapter.inventory();
      assertThat(inventory.bindings()).hasSize(PhysicalModelStatus.values().length);
      assertThat(inventory.bindings())
          .allSatisfy(
              binding -> {
                assertThat(binding.target().schemaName()).isEqualTo(" 顧客 ");
                assertThat(binding.target().catalogName()).isEmpty();
              });
      assertThat(inventory.bindings())
          .extracting(binding -> binding.model().status())
          .containsExactlyInAnyOrder(PhysicalModelStatus.values());
    }
  }

  static final class Fixture implements AutoCloseable {
    final String schema = "own_inventory_" + UUID.randomUUID().toString().replace("-", "");
    final DriverManagerDataSource base;
    final DriverManagerDataSource source;
    final JdbcTemplate jdbc;
    final PhysicalOwnershipInstallationAdapter adapter;

    Fixture() throws Exception {
      String prefix = "DNA_RUNTIME_TEST_POSTGRES";
      String url = System.getenv(prefix + "_URL");
      Assumptions.assumeTrue(url != null, "Disposable PostgreSQL required");
      String user = System.getenv(prefix + "_USER");
      String password = System.getenv(prefix + "_PASSWORD");
      base = new DriverManagerDataSource(url, user, password);
      new JdbcTemplate(base).execute("CREATE SCHEMA " + schema);
      source =
          new DriverManagerDataSource(
              url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
      jdbc = new JdbcTemplate(source);
      jdbc.setQueryTimeout(30);
      adapter = new PhysicalOwnershipInstallationAdapter(source);
      try {
        String root = "META-INF/simplepoint/modules/dna-runtime/db/";
        var manifest = new Properties();
        try (var input = new ClassPathResource(root + "manifest.properties").getInputStream()) {
          manifest.load(input);
        }
        try (var connection = source.getConnection()) {
          for (String migration : manifest.getProperty("migrations").split(",")) {
            ScriptUtils.executeSqlScript(
                connection, new ClassPathResource(root + "postgresql/" + migration));
          }
        }
      } catch (Exception failure) {
        close();
        throw failure;
      }
    }

    String model(String tenant, String status) {
      String id = UUID.randomUUID().toString();
      jdbc.update(
          """
          INSERT INTO simpoint_dna_physical_models
            (id,tenant_id,app_id,form_id,display_name,status,latest_version,active_version,lock_version,created_at,updated_at)
          VALUES (?,?,'app',?,'Model',?,1,1,0,now(),now())
          """,
          id,
          tenant,
          id,
          status);
      jdbc.update(
          """
          INSERT INTO simpoint_dna_physical_bindings
            (id,tenant_id,physical_model_id,data_source_id,catalog_name,schema_name,table_name,isolation_mode,
             lock_version,created_at,updated_at)
          VALUES (?,?,?,'source','database','public',?,'SHARED_DATABASE',0,now(),now())
          """,
          UUID.randomUUID().toString(),
          tenant,
          id,
          "records_" + id.replace("-", ""));
      return id;
    }

    @Override
    public void close() throws SQLException {
      new JdbcTemplate(base).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }
}
