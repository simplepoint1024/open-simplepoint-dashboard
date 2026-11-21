package org.simplepoint.installation.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.ownership.PhysicalAdoptionManifest;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.installation.host.DnaOwnershipInventoryInstallationPostgresTest.Fixture;
import org.simplepoint.platform.installation.InstallationJournal;
import org.simplepoint.platform.installation.InstallationPlan;
import org.simplepoint.platform.installation.InstallationRunner;
import org.simplepoint.platform.installation.jdbc.InstallationJournalMigrations;
import org.simplepoint.platform.installation.jdbc.JdbcInstallationJournal;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalBindingTargetResolver;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipAdoptionPlan;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipInstallationAdapter;
import org.simplepoint.plugin.dna.runtime.service.schema.MysqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalOwnershipSchemaInspector;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaSnapshotCodec;
import org.simplepoint.plugin.dna.runtime.service.schema.PostgresqlRuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

/**
 * Real control journal and native namespace, including recovery of an unacknowledged control step.
 */
class DnaOwnershipAdoptionCoordinatorPostgresTest {
  @Test
  void journalResumesNativeAndControlReceiptsBeforeFinalActivation() throws Exception {
    try (var fixture = new Fixture()) {
      var retired = new AtomicBoolean(false);
      var prepared = prepare(fixture, retired);
      var actions = prepared.coordinator().actions();
      assertThatThrownBy(() -> actions.getFirst().apply()).hasMessage("writers still active");
      assertThat(actions.getFirst().completedReceipt()).isEmpty();
      retired.set(true);
      new ModuleMigrationEngine()
          .migrate(
              InstallationJournalMigrations.plan(fixture.store),
              Map.of(fixture.store, fixture.source));
      var journal = new JdbcInstallationJournal(fixture.source);
      var lost = new AtomicBoolean();
      var uncertain =
          (InstallationJournal)
              Proxy.newProxyInstance(
                  InstallationJournal.class.getClassLoader(),
                  new Class<?>[] {InstallationJournal.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("complete")
                        && ((InstallationJournal.Attempt) args[1]).index() == 1
                        && lost.compareAndSet(false, true)) {
                      throw new IllegalStateException("injected control acknowledgement loss");
                    }
                    try {
                      return method.invoke(journal, args);
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      var installation =
          new InstallationPlan(
              prepared.plan().operation(),
              "physical-adoption-test",
              0,
              "b".repeat(64),
              actions.stream().map(a -> a.definition()).toList());
      assertThatThrownBy(() -> new InstallationRunner(uncertain).run(installation, actions))
          .hasMessage("injected control acknowledgement loss");
      assertThat(lost).isTrue();
      assertThat(actions.get(1).completedReceipt()).isPresent();
      assertThat(actions.get(2).completedReceipt()).isEmpty();
      assertThat(new InstallationRunner(journal).run(installation, actions).complete()).isTrue();
      prepared.coordinator().verifyComplete();
      assertThat(new PhysicalOwnershipInstallationAdapter(fixture.source).inventory().fingerprint())
          .isEqualTo(prepared.plan().adoptedInventory().fingerprint());
      try (var connection = fixture.source.getConnection();
          var session = PhysicalOwnershipSession.open(connection, fixture.schema)) {
        var binding = prepared.plan().models().inventory().bindings().getFirst();
        assertThat(session.owner(binding.target().tableName(), true).orElseThrow().resourceId())
            .isEqualTo(binding.modelId());
      }
      assertThat(new InstallationRunner(journal).run(installation, actions).complete()).isTrue();
      assertThat(
              fixture.jdbc.queryForObject(
                  "SELECT lock_version FROM simpoint_dna_physical_bindings", Long.class))
          .isEqualTo(1);
    }
  }

  @Test
  void activationCannotPassMissingControlReceiptOrChangedStructure() throws Exception {
    try (var fixture = new Fixture()) {
      var prepared = prepare(fixture, new AtomicBoolean(true));
      var actions = prepared.coordinator().actions();
      actions.getFirst().apply();
      assertThatThrownBy(() -> actions.get(2).apply())
          .hasMessage("Physical adoption control receipt is absent");
      actions.get(1).apply();
      String table =
          prepared.plan().models().inventory().bindings().getFirst().target().tableName();
      fixture.jdbc.execute("CREATE TABLE " + table + " (id INTEGER)");
      assertThatThrownBy(() -> actions.get(2).apply())
          .hasMessage("DNA_OWNERSHIP_SCHEMA_MODEL_DRIFT");
      assertThat(actions.get(2).completedReceipt()).isEmpty();
      fixture.jdbc.execute("DROP TABLE " + table);
      actions.get(2).apply();
      prepared.coordinator().verifyComplete();
    }
  }

  private static Prepared prepare(Fixture fixture, AtomicBoolean retired) throws Exception {
    fixture.record();
    fixture.jdbc.execute(
        "UPDATE simpoint_dna_physical_models SET status='DRAFT',active_version=NULL");
    fixture.jdbc.execute(
        "UPDATE simpoint_dna_physical_bindings SET catalog_name='',schema_name=''");
    var encoded =
        new PhysicalSchemaCodec(new ObjectMapper())
            .encode(new PhysicalSchemaDefinition("v1", List.of(), List.of()));
    fixture.jdbc.update(
        "INSERT INTO simpoint_dna_physical_model_versions"
            + " (id,tenant_id,physical_model_id,version_number,contract_version,schema_json,"
            + "schema_checksum,created_at,updated_at)"
            + " SELECT ?,tenant_id,id,1,'v1',?,?,now(),now() FROM simpoint_dna_physical_models",
        UUID.randomUUID().toString(),
        encoded.json(),
        encoded.checksum());
    try (var connection = fixture.source.getConnection()) {
      PhysicalOwnershipInstaller.install(connection, fixture.schema, UUID.randomUUID());
    }
    var owner = new PhysicalOwnershipInstallationAdapter(fixture.source);
    var models = owner.models(owner.inventory());
    var sources =
        Map.<PhysicalBindingTargetResolver.Source, DataSource>of(
            new PhysicalBindingTargetResolver.Source("source", ""), fixture.source);
    var targets = PhysicalBindingTargetResolver.review(models.inventory(), sources);
    var codec = new PhysicalSchemaSnapshotCodec(new ObjectMapper());
    var inspector =
        new PhysicalOwnershipSchemaInspector(
            List.of(
                new PostgresqlRuntimeSchemaDialect(codec), new MysqlRuntimeSchemaDialect(codec)),
            ServiceLoader.load(JdbcDatabaseDialect.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList());
    var tables = inspector.inspect(models, targets, sources);
    var namespace = targets.bindings().getFirst().namespace();
    var operation = UUID.randomUUID();
    String review = "a".repeat(64);
    var manifest =
        new PhysicalAdoptionManifest(
            1,
            namespace.target(),
            namespace.namespaceId(),
            operation,
            review,
            tables.stream()
                .map(
                    t ->
                        new PhysicalAdoptionManifest.Claim(
                            t.table(), t.owner(), t.exists(), t.actualChecksum()))
                .toList());
    var plan =
        new PhysicalOwnershipAdoptionPlan(operation, review, models, targets, List.of(manifest));
    return new Prepared(
        plan,
        new DnaOwnershipAdoptionCoordinator(
            plan,
            fixture.source,
            sources,
            tables,
            inspector,
            () -> {
              if (!retired.get()) {
                throw new IllegalStateException("writers still active");
              }
            }));
  }

  private record Prepared(
      PhysicalOwnershipAdoptionPlan plan, DnaOwnershipAdoptionCoordinator coordinator) {}
}
