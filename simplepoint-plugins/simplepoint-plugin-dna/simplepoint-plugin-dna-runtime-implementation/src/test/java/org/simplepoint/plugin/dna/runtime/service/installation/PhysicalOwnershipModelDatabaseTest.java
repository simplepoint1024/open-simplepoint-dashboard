package org.simplepoint.plugin.dna.runtime.service.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipInventoryDatabaseTest.Fixture;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Native owned control storage, immutable history, migration state and snapshot isolation. */
class PhysicalOwnershipModelDatabaseTest {
  private static final PhysicalSchemaCodec CODEC = new PhysicalSchemaCodec(new ObjectMapper());
  private static final PhysicalSchemaCodec.EncodedSchema SCHEMA =
      CODEC.encode(new PhysicalSchemaDefinition("v1", List.of(), List.of()));

  @Test
  void retainsDeletedHistoriesAndDistinguishesActiveSchemaFromNewDraft() throws Exception {
    try (var fixture = new Fixture()) {
      String active = fixture.model("tenant", "DRAFT");
      definition(fixture, active, "tenant", 1);
      publication(fixture, active, "tenant");
      definition(fixture, active, "tenant", 2);
      fixture.jdbc.update(
          "UPDATE simpoint_dna_physical_models SET latest_version=2 WHERE id=?", active);
      String retired = fixture.model("other", "RETIRED");
      definition(fixture, retired, "other", 1);
      publication(fixture, retired, "other");
      fixture.jdbc.update(
          "UPDATE simpoint_dna_physical_model_versions SET deleted_at=now() WHERE"
              + " physical_model_id=?",
          retired);
      String draft = fixture.model("new", "DRAFT");
      fixture.jdbc.update(
          "UPDATE simpoint_dna_physical_models SET active_version=NULL WHERE id=?", draft);
      definition(fixture, draft, "new", 1);
      var before =
          fixture.jdbc.queryForList(
              "SELECT * FROM simpoint_dna_physical_model_versions ORDER BY id");
      var inventory = fixture.adapter.inventory();
      var review = fixture.adapter.models(inventory);
      assertThat(review.definitions()).hasSize(4);
      assertThat(review.publications()).hasSize(2);
      assertThat(review.active(active).number()).isEqualTo(1);
      assertThat(review.active(retired).deletedAt()).isNotNull();
      assertThat(review.active(draft)).isNull();
      assertThat(fixture.adapter.models(inventory).fingerprint()).isEqualTo(review.fingerprint());
      assertThat(
              fixture.jdbc.queryForList(
                  "SELECT * FROM simpoint_dna_physical_model_versions ORDER BY id"))
          .isEqualTo(before);
      fixture.jdbc.update(
          "UPDATE simpoint_dna_schema_migrations SET lock_version=lock_version+1 WHERE"
              + " physical_model_id=?",
          active);
      assertThat(fixture.adapter.models(inventory).fingerprint())
          .isNotEqualTo(review.fingerprint());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing-definition",
        "wrong-tenant",
        "checksum",
        "invalid-json",
        "missing-publication",
        "failed-publication",
        "physical-checksum",
        "transitional-model",
        "unreleased-guard"
      })
  void refusesIncompleteOrUnsettledHistoryWithoutRepair(String corruption) throws Exception {
    try (var fixture = new Fixture()) {
      String model = fixture.model("tenant", "ACTIVE");
      definition(fixture, model, "tenant", 1);
      String migration = publication(fixture, model, "tenant");
      switch (corruption) {
        case "missing-definition" ->
            fixture.jdbc.update("DELETE FROM simpoint_dna_physical_model_versions");
        case "wrong-tenant" ->
            fixture.jdbc.update(
                "UPDATE simpoint_dna_physical_model_versions SET tenant_id='other'");
        case "checksum" ->
            fixture.jdbc.update(
                "UPDATE simpoint_dna_physical_model_versions SET schema_checksum=?",
                "0".repeat(64));
        case "invalid-json" -> {
          String bad = "{\"contractVersion\":\"v1\",\"fields\":[],\"fields\":[]}";
          fixture.jdbc.update(
              "UPDATE simpoint_dna_physical_model_versions SET schema_json=?,schema_checksum=?",
              bad,
              HexFormat.of()
                  .formatHex(
                      MessageDigest.getInstance("SHA-256")
                          .digest(bad.getBytes(StandardCharsets.UTF_8))));
        }
        case "missing-publication" ->
            fixture.jdbc.update("DELETE FROM simpoint_dna_schema_migrations");
        case "failed-publication" ->
            fixture.jdbc.update("UPDATE simpoint_dna_schema_migrations SET status='FAILED'");
        case "physical-checksum" ->
            fixture.jdbc.update(
                "UPDATE simpoint_dna_schema_migrations SET verified_schema_checksum=?",
                "c".repeat(64));
        case "transitional-model" ->
            fixture.jdbc.update("UPDATE simpoint_dna_physical_models SET status='MIGRATING'");
        case "unreleased-guard" -> {
          fixture.jdbc.update(
              "INSERT INTO simpoint_dna_relation_catalogs"
                  + " (tenant_id,revision,created_at,updated_at) VALUES ('tenant',0,now(),now())");
          fixture.jdbc.update(
              "INSERT INTO simpoint_dna_relation_guards"
                  + " (id,tenant_id,migration_id,model_id,created_at,updated_at) VALUES"
                  + " (?,'tenant',?,?,now(),now())",
              UUID.randomUUID().toString(),
              migration,
              model);
        }
        default -> throw new AssertionError(corruption);
      }
      var inventory = fixture.adapter.inventory();
      var before =
          fixture.jdbc.queryForList("SELECT * FROM simpoint_dna_schema_migrations ORDER BY id");
      assertThatThrownBy(() -> fixture.adapter.models(inventory))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageStartingWith("DNA_OWNERSHIP_MODEL_");
      assertThat(
              fixture.jdbc.queryForList("SELECT * FROM simpoint_dna_schema_migrations ORDER BY id"))
          .isEqualTo(before);
    }
  }

  @Test
  void concurrentDefinitionCommitCannotMixWithOlderBindingSnapshot() throws Exception {
    try (var fixture = new Fixture()) {
      String model = fixture.model("tenant", "ACTIVE");
      definition(fixture, model, "tenant", 1);
      publication(fixture, model, "tenant");
      var inventory = fixture.adapter.inventory();
      var before = fixture.adapter.models(inventory);
      var changed = new AtomicBoolean();
      DataSource source =
          new AbstractDataSource() {
            @Override
            public Connection getConnection() throws java.sql.SQLException {
              var real = fixture.source.getConnection();
              return (Connection)
                  Proxy.newProxyInstance(
                      Connection.class.getClassLoader(),
                      new Class<?>[] {Connection.class},
                      (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")
                            && args[0].toString().contains("CASE WHEN octet_length")
                            && changed.compareAndSet(false, true)) {
                          new TransactionTemplate(new DataSourceTransactionManager(fixture.source))
                              .executeWithoutResult(
                                  status -> {
                                    definition(fixture, model, "tenant", 2);
                                    fixture.jdbc.update(
                                        "UPDATE simpoint_dna_physical_models SET"
                                            + " latest_version=2,lock_version=lock_version+1 WHERE"
                                            + " id=?",
                                        model);
                                  });
                        }
                        try {
                          return method.invoke(real, args);
                        } catch (InvocationTargetException failure) {
                          throw failure.getCause();
                        }
                      });
            }

            @Override
            public Connection getConnection(String user, String password) {
              throw new UnsupportedOperationException();
            }
          };
      assertThat(new PhysicalOwnershipInstallationAdapter(source).models(inventory).fingerprint())
          .isEqualTo(before.fingerprint());
      assertThat(changed).isTrue();
      assertThatThrownBy(() -> fixture.adapter.models(inventory))
          .hasMessage("DNA_OWNERSHIP_INVENTORY_CHANGED");
      assertThat(fixture.adapter.models(fixture.adapter.inventory()).definitions()).hasSize(2);
    }
  }

  @Test
  void oversizedDefinitionIsRejectedBeforeReturningItsBody() throws Exception {
    try (var fixture = new Fixture()) {
      String model = fixture.model("tenant", "ACTIVE");
      definition(fixture, model, "tenant", 1);
      publication(fixture, model, "tenant");
      fixture.jdbc.update(
          "UPDATE simpoint_dna_physical_model_versions SET schema_json=repeat('x',?)",
          PhysicalOwnershipModelReview.MAX_SCHEMA_BYTES + 1);
      assertThatThrownBy(() -> fixture.adapter.models(fixture.adapter.inventory()))
          .hasMessage("DNA_OWNERSHIP_MODEL_LIMIT");
    }
  }

  static void definition(Fixture fixture, String model, String tenant, int number) {
    fixture.jdbc.update(
        "INSERT INTO simpoint_dna_physical_model_versions"
            + " (id,tenant_id,physical_model_id,version_number,contract_version,schema_json,"
            + "schema_checksum,created_at,updated_at)"
            + " VALUES (?,?,?,?,'v1',?,?,now(),now())",
        UUID.randomUUID().toString(),
        tenant,
        model,
        number,
        SCHEMA.json(),
        SCHEMA.checksum());
  }

  static String publication(Fixture fixture, String model, String tenant) {
    String id = UUID.randomUUID().toString();
    fixture.jdbc.update(
        "INSERT INTO simpoint_dna_schema_migrations"
            + " (id,tenant_id,physical_model_id,target_version,target_schema_checksum,"
            + "target_physical_checksum,"
            + "verified_schema_checksum,status,dialect_id,execution_token,started_at,"
            + "finished_at,created_at,updated_at)"
            + " VALUES (?,?,?,1,?, ?,?,'ACTIVE','postgresql',?,now(),now(),now(),now())",
        id,
        tenant,
        model,
        SCHEMA.checksum(),
        "a".repeat(64),
        "a".repeat(64),
        UUID.randomUUID().toString());
    return id;
  }
}
