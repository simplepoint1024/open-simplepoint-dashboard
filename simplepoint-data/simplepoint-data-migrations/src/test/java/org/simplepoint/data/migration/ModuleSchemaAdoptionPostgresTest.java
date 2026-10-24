package org.simplepoint.data.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.simplepoint.data.migration.ModuleMigrationPlan.ModuleSchema;
import org.simplepoint.data.migration.ModuleMigrationPlan.Script;

@EnabledIfEnvironmentVariable(named = "SP_ADOPTION_TEST_URL", matches = ".+")
class ModuleSchemaAdoptionPostgresTest {
  private static final Script V1 = new Script("V1__foundation.sql",
      "create table adoption_fixture (id bigint primary key, label varchar(80) not null);");
  private static final Script V2 = new Script("V2__upgrade.sql",
      "alter table adoption_fixture add column revision integer not null default 1;"
          + "update adoption_fixture set label=upper(label);");
  private final ModuleMigrationEngine engine = new ModuleMigrationEngine();

  @Test
  void approvedAdoptionRetainsDataRunsLaterVersionsAndPinsTheBaselineArtifact() throws Exception {
    try (var database = new Database()) {
      var schema = schema(V1, V2);
      var source = database.source;
      database.execute(V1.sql());
      database.execute("insert into adoption_fixture values (7,'retained')");
      engine.migrate(ModuleSchemaAdoption.receiptMigrations("default"), Map.of("default", source));
      var approval = approval(database, schema);
      var adopter = new ModuleSchemaAdoption();
      assertFalse(adopter.completed(schema, source, approval));
      adopter.adopt(schema, source, approval);
      assertTrue(adopter.completed(schema, source, approval));
      assertEquals(1, engine.migrate(new ModuleMigrationPlan(List.of(schema)),
          Map.of("default", source)).getFirst().applied());
      assertEquals("RETAINED", database.scalar("select label from adoption_fixture where id=7"));
      assertEquals("1", database.scalar("select revision from adoption_fixture where id=7"));
      adopter.adopt(schema, source, approval);
      assertEquals(0, engine.migrate(new ModuleMigrationPlan(List.of(schema)),
          Map.of("default", source)).getFirst().applied());
      var changed = schema(new Script(V1.name(), V1.sql() + "-- changed baseline"), V2);
      assertEquals("ADOPTION_RECEIPT_MISMATCH", assertThrows(ModuleMigrationException.class,
          () -> engine.verify(new ModuleMigrationPlan(List.of(changed)), Map.of("default", source))).code());
    }
  }

  @Test
  void structuralDriftAndUnreviewedBaselinesFailClosed() throws Exception {
    try (var database = new Database()) {
      var schema = schema(V1);
      database.execute(V1.sql());
      engine.migrate(ModuleSchemaAdoption.receiptMigrations("default"), Map.of("default", database.source));
      var approval = approval(database, schema);
      database.execute("alter table adoption_fixture alter column label type varchar(81)");
      assertEquals("ADOPTION_SCHEMA_MISMATCH", assertThrows(ModuleMigrationException.class,
          () -> new ModuleSchemaAdoption().adopt(schema, database.source, approval)).code());
      assertEquals("0", database.scalar("select count(*) from sp_schema_adoptions_v1"));
      Flyway.configure().dataSource(database.source).locations(new String[0]).table(schema.historyTable())
          .baselineVersion("1").load().baseline();
      assertEquals("ADOPTION_RECEIPT_MISMATCH", assertThrows(ModuleMigrationException.class,
          () -> engine.verify(new ModuleMigrationPlan(List.of(schema)), Map.of("default", database.source))).code());
      assertEquals("ADOPTION_SCHEMA_MISMATCH", assertThrows(ModuleMigrationException.class,
          () -> new ModuleSchemaAdoption().adopt(schema, database.source, approval)).code());
    }
  }

  @Test
  void missingCompletionAcknowledgementReconcilesOnlyTheSameReviewedOperation() throws Exception {
    try (var database = new Database()) {
      var schema = schema(V1);
      database.execute(V1.sql());
      engine.migrate(ModuleSchemaAdoption.receiptMigrations("default"), Map.of("default", database.source));
      var approval = approval(database, schema);
      new ModuleSchemaAdoption().adopt(schema, database.source, approval);
      database.execute("update sp_schema_adoptions_v1 set state='PREPARED'");
      assertEquals("ADOPTION_RECEIPT_MISMATCH", assertThrows(ModuleMigrationException.class,
          () -> engine.verify(new ModuleMigrationPlan(List.of(schema)), Map.of("default", database.source))).code());
      var other = new ModuleSchemaAdoption.Approval(UUID.randomUUID(), approval.scriptFingerprint(),
          approval.schemaFingerprint(), approval.evidenceFingerprint());
      assertEquals("ADOPTION_RECEIPT_CONFLICT", assertThrows(ModuleMigrationException.class,
          () -> new ModuleSchemaAdoption().adopt(schema, database.source, other)).code());
      new ModuleSchemaAdoption().adopt(schema, database.source, approval);
      engine.verify(new ModuleMigrationPlan(List.of(schema)), Map.of("default", database.source));
      assertEquals("APPLIED", database.scalar("select state from sp_schema_adoptions_v1"));
      assertEquals("1", database.scalar("select count(*) from \"" + schema.historyTable() + "\""));
    }
  }

  @Test
  void completedReceiptCannotRecreateOrConcealMissingHistory() throws Exception {
    try (var database = new Database()) {
      var schema = schema(V1);
      database.execute(V1.sql());
      engine.migrate(ModuleSchemaAdoption.receiptMigrations("default"), Map.of("default", database.source));
      var approval = approval(database, schema);
      var adopter = new ModuleSchemaAdoption();
      adopter.adopt(schema, database.source, approval);
      database.execute("delete from \"" + schema.historyTable() + "\"");
      assertEquals("ADOPTION_HISTORY_CONFLICT", assertThrows(ModuleMigrationException.class,
          () -> adopter.adopt(schema, database.source, approval)).code());
      database.execute("drop table \"" + schema.historyTable() + "\"");
      assertEquals("ADOPTION_HISTORY_CONFLICT", assertThrows(ModuleMigrationException.class,
          () -> adopter.adopt(schema, database.source, approval)).code());
    }
  }

  @Test
  void concurrentIdenticalApprovalsShareOneDurableReceipt() throws Exception {
    try (var database = new Database()) {
      var schema = schema(V1);
      database.execute(V1.sql());
      engine.migrate(ModuleSchemaAdoption.receiptMigrations("default"), Map.of("default", database.source));
      var approval = approval(database, schema);
      try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var first = executor.submit(() -> new ModuleSchemaAdoption().adopt(schema, database.source, approval));
        var second = executor.submit(() -> new ModuleSchemaAdoption().adopt(schema, database.source, approval));
        first.get(15, java.util.concurrent.TimeUnit.SECONDS);
        second.get(15, java.util.concurrent.TimeUnit.SECONDS);
      }
      assertEquals("1", database.scalar("select count(*) from sp_schema_adoptions_v1"));
    }
  }

  private ModuleSchemaAdoption.Approval approval(Database database, ModuleSchema schema) throws SQLException {
    try (var connection = database.source.getConnection()) {
      return new ModuleSchemaAdoption.Approval(UUID.randomUUID(), V1.sha256(),
          PostgresSchemaContract.capture(connection, schema.tables()).fingerprint(), V1.sha256());
    }
  }

  private ModuleSchema schema(Script... scripts) {
    var owner = ModuleMigrationPlan.loadSchema("one", "default", getClass().getClassLoader());
    return new ModuleSchema(owner.module(), owner.store(), owner.historyTable(),
        List.of("adoption_fixture"), Map.of("postgresql", List.of(scripts)));
  }

  private static final class Database implements AutoCloseable {
    private final PGSimpleDataSource admin = new PGSimpleDataSource();
    private final PGSimpleDataSource source = new PGSimpleDataSource();
    private final String namespace = "adoption_" + UUID.randomUUID().toString().replace("-", "");

    Database() throws SQLException {
      String url = System.getenv("SP_ADOPTION_TEST_URL");
      if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/composition_adoption_test")) {
        throw new IllegalArgumentException("Adoption tests require the dedicated local test database");
      }
      admin.setURL(url);
      admin.setUser(System.getenv("SP_ADOPTION_TEST_USER"));
      admin.setPassword(System.getenv("SP_ADOPTION_TEST_PASSWORD"));
      try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
        statement.execute("create schema " + namespace);
      }
      source.setURL(url + "?currentSchema=" + namespace);
      source.setUser(admin.getUser());
      source.setPassword(admin.getPassword());
    }

    void execute(String sql) throws SQLException {
      try (var connection = source.getConnection(); var statement = connection.createStatement()) {
        statement.execute(sql);
      }
    }

    String scalar(String sql) throws SQLException {
      try (var connection = source.getConnection(); var statement = connection.createStatement();
          var result = statement.executeQuery(sql)) {
        result.next();
        return result.getString(1);
      }
    }

    @Override
    public void close() throws SQLException {
      try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
        statement.execute("drop schema " + namespace + " cascade");
      }
    }
  }
}
