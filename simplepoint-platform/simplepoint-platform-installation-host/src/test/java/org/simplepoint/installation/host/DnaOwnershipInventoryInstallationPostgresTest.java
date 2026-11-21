package org.simplepoint.installation.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Offline host review uses only the selected domain store and preserves all retained bindings. */
class DnaOwnershipInventoryInstallationPostgresTest {
  private static final String ARTIFACT = "a".repeat(64);
  @TempDir Path directory;

  @Test
  void privatePersistedReviewVerifiesExactDomainSnapshotWithoutActivation() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.record();
      var properties = properties();
      var unrelated = mock(DataSource.class);
      var sources = Map.of(fixture.store, (DataSource) fixture.source, "unrelated", unrelated);
      var review = execute(true, fixture, properties, sources);
      assertThat(review)
          .containsEntry("bindings", 1)
          .containsEntry("legacyAdopted", false)
          .containsEntry("activated", false)
          .containsEntry("controlBindingsVerified", false);
      Path file = Path.of(properties.getProperty("installation.ownership.inventory.review-file"));
      assertThat(Files.getPosixFilePermissions(file))
          .isEqualTo(PosixFilePermissions.fromString("rw-------"));
      String json = Files.readString(file);
      assertThat(json)
          .contains("RETIRED", "2026-10-28T01:00:00Z")
          .doesNotContain("jdbc:", "password", "username");
      properties.setProperty(
          "installation.ownership.inventory.review-fingerprint",
          review.get("reviewFingerprint").toString());
      assertThat(execute(false, fixture, properties, sources))
          .containsEntry("controlBindingsVerified", true);
      assertThat(execute(false, fixture, properties, sources)).containsEntry("activated", false);
      assertThatThrownBy(() -> execute(true, fixture, properties, sources))
          .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
      assertThat(Files.readString(file)).isEqualTo(json);
      assertThat(
              fixture.jdbc.queryForObject(
                  "SELECT COUNT(*) FROM simpoint_dna_physical_bindings WHERE physical_namespace_id"
                      + " IS NOT NULL",
                  Integer.class))
          .isZero();
      verifyNoInteractions(unrelated);
    }
  }

  @Test
  void targetReleaseOperationAndNewRetainedBindingsInvalidateReview() throws Exception {
    try (var fixture = new Fixture();
        var another = new Fixture()) {
      fixture.record();
      var properties = properties();
      var sources = Map.<String, DataSource>of(fixture.store, fixture.source);
      var review = execute(true, fixture, properties, sources);
      properties.setProperty(
          "installation.ownership.inventory.review-fingerprint",
          review.get("reviewFingerprint").toString());
      assertThatThrownBy(
              () ->
                  DnaOwnershipInventoryInstallation.execute(
                      false,
                      fixture.modules,
                      fixture.bindings,
                      properties,
                      sources,
                      "b".repeat(64)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> execute(false, fixture, properties, Map.of(fixture.store, another.source)))
          .isInstanceOf(IllegalArgumentException.class);
      var changed = new Properties();
      changed.putAll(properties);
      changed.setProperty("installation.operation", UUID.randomUUID().toString());
      assertThatThrownBy(() -> execute(false, fixture, changed, sources))
          .isInstanceOf(IllegalArgumentException.class);
      fixture.record();
      assertThatThrownBy(() -> execute(false, fixture, properties, sources))
          .hasMessage("Physical binding inventory changed after review");
      assertThat(
              fixture.jdbc.queryForObject(
                  "SELECT COUNT(*) FROM simpoint_dna_physical_bindings", Integer.class))
          .isEqualTo(2);
    }
  }

  @Test
  void unselectedDomainOrMissingOwnedBindingCannotBorrowHostStorage() {
    var source = mock(DataSource.class);
    var catalog = ModuleCatalog.discover(getClass().getClassLoader());
    assertThatThrownBy(
            () ->
                DnaOwnershipInventoryInstallation.execute(
                    true,
                    catalog.plan(List.of("identity")),
                    new ModuleStorageBindings(Map.of()),
                    properties(),
                    Map.of("default", source),
                    ARTIFACT))
        .hasMessageContaining("requires selected dna-runtime");
    assertThatThrownBy(
            () ->
                DnaOwnershipInventoryInstallation.execute(
                    true,
                    catalog.plan(List.of("dna-runtime")),
                    new ModuleStorageBindings(Map.of()),
                    properties(),
                    Map.of("default", source),
                    ARTIFACT))
        .hasMessageContaining("MISSING_MODULE_STORAGE");
    verifyNoInteractions(source);
  }

  private Properties properties() {
    var properties = new Properties();
    properties.setProperty("installation.operation", UUID.randomUUID().toString());
    properties.setProperty(
        "installation.ownership.inventory.review-file",
        directory.resolve("bindings.json").toString());
    return properties;
  }

  @Test
  void nativeReviewIsPrivateRepeatableAndRejectsChangedDefaultTarget() throws Exception {
    try (var fixture = new Fixture();
        var other = new Fixture()) {
      var properties = nativeProperties(fixture);
      final var result = nativeExecute(true, fixture, properties);
      assertThat(result)
          .containsEntry("bindings", 1)
          .containsEntry("nativeTargetsVerified", false)
          .containsEntry("legacyAdopted", false)
          .containsEntry("activated", false);
      final Path file =
          Path.of(properties.getProperty("installation.ownership.targets.review-file"));
      String document = Files.readString(file);
      assertThat(document).contains(fixture.schema).doesNotContain("jdbc:", "password", "username");
      assertThat(Files.getPosixFilePermissions(file))
          .isEqualTo(PosixFilePermissions.fromString("rw-------"));
      properties.setProperty(
          "installation.ownership.targets.review-fingerprint",
          result.get("reviewFingerprint").toString());
      assertThat(nativeExecute(false, fixture, properties))
          .containsEntry("nativeTargetsVerified", true);
      assertThat(nativeExecute(false, fixture, properties)).containsEntry("activated", false);
      assertThatThrownBy(() -> nativeExecute(true, fixture, properties))
          .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
      properties.setProperty(
          "installation.ownership.target-sources.main.url", other.source.getUrl());
      assertThatThrownBy(() -> nativeExecute(false, fixture, properties))
          .hasMessage("Physical binding targets changed after review");
      assertThat(Files.readString(file)).isEqualTo(document);
      assertThat(
              fixture.jdbc.queryForObject(
                  "SELECT COUNT(*) FROM simpoint_dna_physical_bindings WHERE physical_namespace_id"
                      + " IS NOT NULL",
                  Integer.class))
          .isZero();
    }
  }

  @Test
  void changedControlAndFingerprintFailBeforeAnyTargetConnection() throws Exception {
    try (var fixture = new Fixture()) {
      var properties = nativeProperties(fixture);
      final var result = nativeExecute(true, fixture, properties);
      properties.setProperty("installation.ownership.targets.review-fingerprint", "0".repeat(64));
      properties.setProperty("installation.ownership.target-sources.main.url", "jdbc:no-driver");
      assertThatThrownBy(() -> nativeExecute(false, fixture, properties))
          .hasMessage("Physical target review differs from installation inputs");
      properties.setProperty(
          "installation.ownership.targets.review-fingerprint",
          result.get("reviewFingerprint").toString());
      fixture.jdbc.update("UPDATE simpoint_dna_physical_bindings SET lock_version=lock_version+1");
      assertThatThrownBy(() -> nativeExecute(false, fixture, properties))
          .hasMessage("Physical binding inventory changed after review");
    }
  }

  @Test
  void missingOrDuplicateSourceMappingNeverProducesNativeReview() throws Exception {
    try (var fixture = new Fixture()) {
      var properties = nativeProperties(fixture);
      final Path file =
          Path.of(properties.getProperty("installation.ownership.targets.review-file"));
      properties.setProperty("installation.ownership.target-sources", "");
      assertThatThrownBy(() -> nativeExecute(true, fixture, properties))
          .hasMessage("DNA_OWNERSHIP_TARGET_SOURCE_BINDINGS");
      properties.setProperty("installation.ownership.target-sources", "main,duplicate");
      for (String suffix : List.of("data-source-id", "catalog", "url", "username", "password")) {
        properties.setProperty(
            "installation.ownership.target-sources.duplicate." + suffix,
            properties.getProperty("installation.ownership.target-sources.main." + suffix));
      }
      assertThatThrownBy(() -> nativeExecute(true, fixture, properties))
          .hasMessage("Duplicate physical target source binding");
      assertThat(Files.exists(file)).isFalse();
    }
  }

  private Properties nativeProperties(Fixture fixture) throws Exception {
    return nativeProperties(fixture, false);
  }

  private Properties nativeProperties(Fixture fixture, boolean unpublished) throws Exception {
    fixture.record();
    fixture.jdbc.update("UPDATE simpoint_dna_physical_bindings SET catalog_name='',schema_name=''");
    if (unpublished) {
      fixture.jdbc.update(
          "UPDATE simpoint_dna_physical_models SET status='DRAFT',active_version=NULL");
      var encoded =
          new org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec(
                  new com.fasterxml.jackson.databind.ObjectMapper())
              .encode(
                  new org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition(
                      "v1", List.of(), List.of()));
      fixture.jdbc.update(
          "INSERT INTO simpoint_dna_physical_model_versions"
              + " (id,tenant_id,physical_model_id,version_number,contract_version,schema_json,"
              + "schema_checksum,created_at,updated_at)"
              + " SELECT ?,tenant_id,id,1,'v1',?,?,now(),now() FROM simpoint_dna_physical_models",
          UUID.randomUUID().toString(),
          encoded.json(),
          encoded.checksum());
    }
    var properties = properties();
    var control = execute(true, fixture, properties, Map.of(fixture.store, fixture.source));
    properties.setProperty(
        "installation.ownership.inventory.review-fingerprint",
        control.get("reviewFingerprint").toString());
    properties.setProperty(
        "installation.ownership.targets.review-file", directory.resolve("targets.json").toString());
    properties.setProperty("installation.ownership.target-sources", "main");
    properties.setProperty("installation.ownership.target-sources.main.data-source-id", "source");
    properties.setProperty("installation.ownership.target-sources.main.catalog", "");
    properties.setProperty(
        "installation.ownership.target-sources.main.url", fixture.source.getUrl());
    properties.setProperty(
        "installation.ownership.target-sources.main.username", fixture.source.getUsername());
    properties.setProperty(
        "installation.ownership.target-sources.main.password", fixture.source.getPassword());
    return properties;
  }

  private static Map<String, Object> nativeExecute(
      boolean preview, Fixture fixture, Properties properties) throws Exception {
    return DnaOwnershipTargetInstallation.execute(
        preview,
        fixture.modules,
        fixture.bindings,
        properties,
        Map.of(fixture.store, fixture.source),
        ARTIFACT);
  }

  @Test
  void schemaReviewAuthenticatesModelHistoryAndDoesNotCreateAbsentTables() throws Exception {
    try (var fixture = new Fixture()) {
      var properties = nativeProperties(fixture, true);
      var targets = nativeExecute(true, fixture, properties);
      properties.setProperty(
          "installation.ownership.targets.review-fingerprint",
          targets.get("reviewFingerprint").toString());
      Path file = directory.resolve("schema.json");
      properties.setProperty("installation.ownership.schema.review-file", file.toString());
      var result = schemaExecute(true, fixture, properties);
      assertThat(result)
          .containsEntry("models", 1)
          .containsEntry(
              "tables",
              org.simplepoint.plugin.dna.runtime.service.schema.RuntimeInfrastructureTable.values()
                      .length
                  + 1)
          .containsEntry("schemaVerified", false)
          .containsEntry("legacyAdopted", false)
          .containsEntry("activated", false);
      properties.setProperty(
          "installation.ownership.schema.review-fingerprint",
          result.get("reviewFingerprint").toString());
      assertThat(schemaExecute(false, fixture, properties)).containsEntry("schemaVerified", true);
      assertThat(Files.getPosixFilePermissions(file))
          .isEqualTo(PosixFilePermissions.fromString("rw-------"));
      assertThat(Files.readString(file)).doesNotContain("jdbc:", "password", "username");
      String table =
          fixture.jdbc.queryForObject(
              "SELECT table_name FROM simpoint_dna_physical_bindings", String.class);
      fixture.jdbc.execute("CREATE TABLE " + table + " (id INTEGER)");
      assertThatThrownBy(() -> schemaExecute(false, fixture, properties))
          .hasMessage("DNA_OWNERSHIP_SCHEMA_UNPUBLISHED_OBJECT");
      assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
          .isZero();
    }
  }

  private static Map<String, Object> schemaExecute(
      boolean preview, Fixture fixture, Properties properties) throws Exception {
    return DnaOwnershipSchemaInstallation.execute(
        preview,
        fixture.modules,
        fixture.bindings,
        properties,
        Map.of(fixture.store, fixture.source),
        ARTIFACT);
  }

  @Test
  void adoptionCommandPersistsPrivatePlanAndResumesAfterControlInventoryChanges() throws Exception {
    try (var fixture = new Fixture()) {
      var properties = nativeProperties(fixture, true);
      try (var connection = fixture.source.getConnection()) {
        org.simplepoint.data.ownership.PhysicalOwnershipInstaller.install(
            connection, fixture.schema, UUID.randomUUID());
      }
      var targets = nativeExecute(true, fixture, properties);
      properties.setProperty(
          "installation.ownership.targets.review-fingerprint",
          targets.get("reviewFingerprint").toString());
      properties.setProperty(
          "installation.ownership.schema.review-file", directory.resolve("schema.json").toString());
      var schema = schemaExecute(true, fixture, properties);
      properties.setProperty(
          "installation.ownership.schema.review-fingerprint",
          schema.get("reviewFingerprint").toString());
      String prefix = "installation.ownership.adoption.";
      properties.setProperty(prefix + "review-file", directory.resolve("adoption.json").toString());
      properties.setProperty(prefix + "target", "test-adoption");
      properties.setProperty(prefix + "expected-generation", "0");
      properties.setProperty(prefix + "machine-id", "a".repeat(32));
      properties.setProperty(prefix + "retired-units", "dna.service");
      properties.setProperty("installation.journal.store", fixture.store);
      var source = Map.<String, DataSource>of(fixture.store, fixture.source);
      var probe =
          LocalServiceRetirementTest.probe(
              LocalServiceRetirementTest.stopped("dna.service"), "a".repeat(32), false);
      var result =
          DnaOwnershipAdoptionInstallation.execute(
              "ownership-adoption-review",
              fixture.modules,
              fixture.bindings,
              properties,
              source,
              ARTIFACT,
              probe);
      assertThat(result).containsEntry("legacyAdopted", false).containsEntry("activated", false);
      Path file = directory.resolve("adoption.json");
      assertThat(Files.getPosixFilePermissions(file))
          .isEqualTo(PosixFilePermissions.fromString("rw-------"));
      assertThat(Files.readString(file)).doesNotContain("jdbc:", "password", "username");
      properties.setProperty(
          prefix + "review-fingerprint", result.get("reviewFingerprint").toString());
      assertThatThrownBy(
              () ->
                  DnaOwnershipAdoptionInstallation.execute(
                      "ownership-adopt",
                      fixture.modules,
                      fixture.bindings,
                      properties,
                      source,
                      ARTIFACT,
                      LocalServiceRetirementTest.probe(
                          LocalServiceRetirementTest.stopped("dna.service"),
                          "b".repeat(32),
                          false)))
          .hasMessageContaining("not established");
      assertThat(
              DnaOwnershipAdoptionInstallation.execute(
                  "ownership-adopt",
                  fixture.modules,
                  fixture.bindings,
                  properties,
                  source,
                  ARTIFACT,
                  probe))
          .containsEntry("activated", true);
      assertThatThrownBy(() -> execute(false, fixture, properties, source))
          .hasMessage("Physical binding inventory changed after review");
      // Restart reads the saved adoption intent, not an impossible pre-adoption live inventory.
      assertThat(
              DnaOwnershipAdoptionInstallation.execute(
                  "ownership-adopt",
                  fixture.modules,
                  fixture.bindings,
                  properties,
                  source,
                  ARTIFACT,
                  probe))
          .containsEntry("activated", true);
      assertThat(
              DnaOwnershipAdoptionInstallation.execute(
                  "ownership-adoption-verify",
                  fixture.modules,
                  fixture.bindings,
                  properties,
                  source,
                  ARTIFACT,
                  probe))
          .containsEntry("legacyAdopted", true);
      assertThat(
              fixture.jdbc.queryForObject(
                  "SELECT lock_version FROM simpoint_dna_physical_bindings", Long.class))
          .isEqualTo(1);
    }
  }

  private static Map<String, Object> execute(
      boolean preview, Fixture fixture, Properties properties, Map<String, DataSource> sources)
      throws Exception {
    return DnaOwnershipInventoryInstallation.execute(
        preview, fixture.modules, fixture.bindings, properties, sources, ARTIFACT);
  }

  static final class Fixture implements AutoCloseable {
    final String schema = "host_inventory_" + UUID.randomUUID().toString().replace("-", "");
    final DriverManagerDataSource base;
    final DriverManagerDataSource source;
    final JdbcTemplate jdbc;
    final ModulePlan modules;
    final ModuleStorageBindings bindings;
    final String store;

    Fixture() throws Exception {
      String prefix = "PHYSICAL_OWNERSHIP_TEST_POSTGRES";
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
      try {
        modules = ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("dna-runtime"));
        bindings =
            ModulePersistencePlan.discover(
                    modules, new StandardEnvironment(), new DefaultResourceLoader())
                .bindings();
        store = bindings.require("dna-runtime").store();
        new ModuleMigrationEngine()
            .migrate(ModuleMigrationPlan.discover(modules, bindings), Map.of(store, source));
      } catch (Exception failure) {
        close();
        throw failure;
      }
    }

    void record() {
      String model = UUID.randomUUID().toString();
      jdbc.update(
          "INSERT INTO simpoint_dna_physical_models"
              + " (id,tenant_id,app_id,form_id,display_name,status,latest_version,active_version,"
              + "lock_version,created_at,updated_at,deleted_at)"
              + " VALUES (?,'tenant','app',?,'Model','RETIRED',1,1,0,now(),now(),TIMESTAMPTZ"
              + " '2026-10-28 01:00:00+00')",
          model,
          model);
      jdbc.update(
          "INSERT INTO simpoint_dna_physical_bindings"
              + " (id,tenant_id,physical_model_id,data_source_id,catalog_name,schema_name,"
              + "table_name,"
              + "isolation_mode,lock_version,created_at,updated_at) VALUES"
              + " (?,'tenant',?,'source','database','public',?,'SHARED_DATABASE',0,now(),now())",
          UUID.randomUUID().toString(),
          model,
          "records_" + model.replace("-", ""));
    }

    @Override
    public void close() {
      new JdbcTemplate(base).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }
}
