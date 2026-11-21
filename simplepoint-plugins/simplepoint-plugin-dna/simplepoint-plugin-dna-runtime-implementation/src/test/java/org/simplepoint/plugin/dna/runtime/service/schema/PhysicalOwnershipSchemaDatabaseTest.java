package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalIsolationMode;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalBindingTargetResolver;
import org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipModelReview;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Real physical tables checked without installing ownership or repairing drift. */
class PhysicalOwnershipSchemaDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void adoptionRechecksFrozenStructureOnTheLockedNativeConnection(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres)) {
      final var published = fixture.create("records", "业务模型", fixture.schema);
      try (var connection = fixture.source.getConnection()) {
        PhysicalOwnershipInstaller.install(
            connection, fixture.namespace, java.util.UUID.randomUUID());
      }
      var review = fixture.review(published.targetSnapshot().checksum());
      var tables = fixture.inspect(review);
      try (var connection = fixture.source.getConnection()) {
        var inspected = PhysicalOwnershipInstaller.inspect(connection, fixture.namespace);
        var manifest =
            new org.simplepoint.data.ownership.PhysicalAdoptionManifest(
                1,
                inspected.target(),
                inspected.namespaceId(),
                java.util.UUID.randomUUID(),
                "a".repeat(64),
                tables.stream()
                    .map(
                        t ->
                            new org.simplepoint.data.ownership.PhysicalAdoptionManifest.Claim(
                                t.table(), t.owner(), t.exists(), t.actualChecksum()))
                    .toList());
        var changed = tables.stream().filter(t -> !t.table().equals("records")).toList();
        assertThatThrownBy(() -> fixture.inspector.verifyReviewed(connection, manifest, changed))
            .hasMessage("DNA_OWNERSHIP_SCHEMA_INVALID_EVIDENCE");
        PhysicalOwnershipInstaller.adoptReviewed(
            connection, manifest, (c, m) -> fixture.inspector.verifyReviewed(c, m, tables));
        assertThat(PhysicalOwnershipInstaller.hasAdoptionReceipt(connection, manifest)).isTrue();
        fixture.base.execute(
            connection,
            "ALTER TABLE " + fixture.base.table("records") + " ADD COLUMN rogue INTEGER");
        assertThatThrownBy(
                () ->
                    PhysicalOwnershipInstaller.activateAdopted(
                        connection,
                        manifest,
                        (c, m) -> fixture.inspector.verifyReviewed(c, m, tables)))
            .hasMessage("DNA_OWNERSHIP_SCHEMA_MODEL_DRIFT");
        assertThat(PhysicalOwnershipInstaller.hasActivationReceipt(connection, manifest)).isFalse();
        fixture.base.execute(
            connection, "ALTER TABLE " + fixture.base.table("records") + " DROP COLUMN rogue");
        PhysicalOwnershipInstaller.activateAdopted(
            connection, manifest, (c, m) -> fixture.inspector.verifyReviewed(c, m, tables));
        assertThat(PhysicalOwnershipInstaller.hasActivationReceipt(connection, manifest)).isTrue();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void verifiesPublishedAndAbsentDraftTablesAndUsesTheSharedInfrastructureRegistry(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres)) {
      var draft = fixture.review(null);
      var empty = fixture.inspect(draft);
      assertThat(empty)
          .hasSize(RuntimeInfrastructureTable.values().length + 1)
          .allSatisfy(table -> assertThat(table.exists()).isFalse());
      assertThat(empty)
          .extracting(PhysicalOwnershipSchemaInspector.Table::table)
          .containsAll(
              java.util.Arrays.stream(RuntimeInfrastructureTable.values())
                  .map(RuntimeInfrastructureTable::table)
                  .toList());
      var published = fixture.create("records", "业务模型", fixture.schema);
      var result = fixture.inspect(fixture.review(published.targetSnapshot().checksum()));
      assertThat(result.stream().filter(PhysicalOwnershipSchemaInspector.Table::exists)).hasSize(1);
      var table = RuntimeInfrastructureTable.OPERATIONS;
      fixture.create(table.table(), table.description(), table.definition());
      result = fixture.inspect(fixture.review(published.targetSnapshot().checksum()));
      assertThat(result.stream().filter(PhysicalOwnershipSchemaInspector.Table::exists)).hasSize(2);
      assertThat(
              result.stream()
                  .filter(t -> t.table().equals(table.table()))
                  .findFirst()
                  .orElseThrow()
                  .owner())
          .isEqualTo(table.owner());
      try (var connection = fixture.source.getConnection()) {
        assertThat(PhysicalOwnershipInstaller.inspect(connection, fixture.namespace).namespaceId())
            .isNull();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void rejectsChangedPublishedStructureAndNeverRepairsIt(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres)) {
      final var plan = fixture.create("records", "业务模型", fixture.schema);
      var review = fixture.review(plan.targetSnapshot().checksum());
      try (var connection = fixture.source.getConnection()) {
        fixture.base.execute(
            connection,
            "ALTER TABLE " + fixture.base.table("records") + " ADD COLUMN rogue INTEGER");
      }
      assertThatThrownBy(() -> fixture.inspect(review))
          .hasMessage("DNA_OWNERSHIP_SCHEMA_MODEL_DRIFT");
      try (var connection = fixture.source.getConnection()) {
        assertThat(
                fixture
                    .dialect
                    .inspect(connection, fixture.core, fixture.context, fixture.target("records"))
                    .columns())
            .anySatisfy(column -> assertThat(column.name()).isEqualTo("rogue"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void missingPublishedTablesAndViewsAtUnpublishedNamesAreRejected(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres)) {
      final var plan = fixture.create("records", "业务模型", fixture.schema);
      var review = fixture.review(plan.targetSnapshot().checksum());
      try (var connection = fixture.source.getConnection()) {
        fixture.base.execute(connection, "DROP TABLE " + fixture.base.table("records"));
      }
      assertThatThrownBy(() -> fixture.inspect(review))
          .hasMessage("DNA_OWNERSHIP_SCHEMA_MODEL_DRIFT");
      try (var connection = fixture.source.getConnection()) {
        fixture.base.execute(
            connection, "CREATE VIEW " + fixture.base.table("records") + " AS SELECT 1 AS id");
      }
      assertThatThrownBy(() -> fixture.inspect(fixture.review(null)))
          .hasMessage("DNA_OWNERSHIP_SCHEMA_OBJECT_KIND");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void existingUnpublishedTableAndDamagedSharedTableCannotBeAdopted(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres)) {
      final var plan = fixture.create("records", "业务模型", fixture.schema);
      assertThatThrownBy(() -> fixture.inspect(fixture.review(null)))
          .hasMessage("DNA_OWNERSHIP_SCHEMA_UNPUBLISHED_OBJECT");
      var shared = RuntimeInfrastructureTable.OPERATIONS;
      fixture.create(shared.table(), shared.description(), shared.definition());
      try (var connection = fixture.source.getConnection()) {
        fixture.base.execute(
            connection,
            "ALTER TABLE " + fixture.base.table(shared.table()) + " ADD COLUMN rogue INTEGER");
      }
      assertThatThrownBy(() -> fixture.inspect(fixture.review(plan.targetSnapshot().checksum())))
          .isInstanceOf(RuntimeException.class);
      try (var connection = fixture.source.getConnection()) {
        assertThat(
                fixture
                    .dialect
                    .inspect(
                        connection, fixture.core, fixture.context, fixture.target(shared.table()))
                    .columns())
            .anySatisfy(column -> assertThat(column.name()).isEqualTo("rogue"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void metadataPatternsCannotSelectAnotherSchemaOrTable(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres)) {
      fixture.create("case_sensitive", "exact", fixture.schema);
      try (var connection = fixture.source.getConnection()) {
        if (postgres) {
          String confusing = fixture.namespace.replace('_', 'x');
          fixture.base.execute(connection, "CREATE SCHEMA " + fixture.base.quote(confusing));
          try {
            fixture.base.execute(
                connection,
                "CREATE TABLE "
                    + fixture.base.quote(confusing)
                    + ".case_sensitive (wrong INTEGER)");
            var actual =
                fixture.dialect.inspect(
                    connection, fixture.core, fixture.context, fixture.target("case_sensitive"));
            assertThat(actual.columns())
                .noneSatisfy(column -> assertThat(column.name()).isEqualTo("wrong"));
            assertThat(actual.columns()).hasSize(9);
          } finally {
            fixture.base.execute(
                connection, "DROP SCHEMA " + fixture.base.quote(confusing) + " CASCADE");
          }
        }
        assertThat(
                fixture
                    .dialect
                    .inspect(
                        connection, fixture.core, fixture.context, fixture.target("case%sensitive"))
                    .exists())
            .isFalse();
      }
    }
  }

  private static final class Fixture implements AutoCloseable {
    final PhysicalOwnershipDatabaseFixture base;
    final String namespace;
    final DataSource source;
    final JdbcDatabaseDialect core;
    final RuntimeSchemaDialect dialect;
    final JdbcDatabaseDialect.SupportContext context;
    final DatabaseIdentity database;
    final PhysicalSchemaDefinition schema =
        new PhysicalSchemaDefinition("v1", List.of(), List.of());
    final PhysicalOwnershipSchemaInspector inspector;

    Fixture(boolean postgres) throws Exception {
      base = new PhysicalOwnershipDatabaseFixture(postgres);
      namespace = base.namespace;
      try {
        source =
            new AbstractDataSource() {
              @Override
              public Connection getConnection() throws SQLException {
                var connection = base.source.getConnection();
                try {
                  if (postgres) {
                    connection.setSchema(namespace);
                  } else {
                    connection.setCatalog(namespace);
                  }
                  return connection;
                } catch (SQLException failure) {
                  connection.close();
                  throw failure;
                }
              }

              @Override
              public Connection getConnection(String user, String password) {
                throw new UnsupportedOperationException();
              }
            };
        core = postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
        var codec = new PhysicalSchemaSnapshotCodec(new ObjectMapper());
        dialect =
            postgres
                ? new PostgresqlRuntimeSchemaDialect(codec)
                : new MysqlRuntimeSchemaDialect(codec);
        try (var connection = source.getConnection()) {
          var metadata = connection.getMetaData();
          context =
              new JdbcDatabaseDialect.SupportContext(
                  core.code(),
                  metadata.getDriverName(),
                  metadata.getDatabaseProductName(),
                  metadata.getDatabaseProductVersion(),
                  connection.getCatalog(),
                  postgres ? namespace : null,
                  metadata.getIdentifierQuoteString(),
                  Map.of());
          database =
              new DatabaseIdentity(
                  metadata.getDatabaseProductName(),
                  metadata.getDatabaseProductVersion(),
                  metadata.getDatabaseMajorVersion(),
                  metadata.getDatabaseMinorVersion());
        }
        inspector = new PhysicalOwnershipSchemaInspector(List.of(dialect), List.of(core));
      } catch (Exception failure) {
        base.close();
        throw failure;
      }
    }

    PhysicalSchemaTarget target(String table) {
      return new PhysicalSchemaTarget(
          "model",
          new RuntimeMetadataTarget(
              "source", context.currentCatalog(), context.currentSchema(), table));
    }

    InitialSchemaPlan create(String table, String comment, PhysicalSchemaDefinition definition)
        throws SQLException {
      try (var connection = source.getConnection()) {
        var plan =
            dialect.planInitial(
                connection, core, context, database, target(table), comment, definition);
        for (var step : plan.steps()) {
          base.execute(connection, step.statementSql());
        }
        assertThat(dialect.inspect(connection, core, context, target(table)).checksum())
            .isEqualTo(plan.targetSnapshot().checksum());
        return plan;
      }
    }

    PhysicalOwnershipModelReview review(String physicalChecksum) {
      var inventory =
          new PhysicalBindingInventory(
              1,
              List.of(
                  new PhysicalBindingInventory.Binding(
                      "binding",
                      "model",
                      "tenant",
                      new PhysicalBindingInventory.Target(
                          "source", "", "", "records", PhysicalIsolationMode.SHARED_DATABASE),
                      null,
                      0,
                      null,
                      new PhysicalBindingInventory.Model(
                          "app",
                          "form",
                          physicalChecksum == null
                              ? PhysicalModelStatus.DRAFT
                              : PhysicalModelStatus.ACTIVE,
                          1,
                          physicalChecksum == null ? null : 1,
                          0,
                          null))));
      var encoded = new PhysicalSchemaCodec(new ObjectMapper()).encode(schema);
      var definition =
          new PhysicalOwnershipModelReview.Definition(
              "version", "model", "tenant", 1, "v1", encoded.json(), encoded.checksum(), null);
      var publications =
          physicalChecksum == null
              ? List.<PhysicalOwnershipModelReview.Publication>of()
              : List.of(
                  new PhysicalOwnershipModelReview.Publication(
                      "migration",
                      "model",
                      "tenant",
                      null,
                      1,
                      encoded.checksum(),
                      physicalChecksum,
                      dialect.dialectId(),
                      0,
                      Instant.now(),
                      null));
      return new PhysicalOwnershipModelReview(1, inventory, List.of(definition), publications);
    }

    List<PhysicalOwnershipSchemaInspector.Table> inspect(PhysicalOwnershipModelReview review) {
      var sources = Map.of(new PhysicalBindingTargetResolver.Source("source", ""), source);
      return inspector.inspect(
          review, PhysicalBindingTargetResolver.review(review.inventory(), sources), sources);
    }

    @Override
    public void close() throws SQLException {
      base.close();
    }
  }
}
