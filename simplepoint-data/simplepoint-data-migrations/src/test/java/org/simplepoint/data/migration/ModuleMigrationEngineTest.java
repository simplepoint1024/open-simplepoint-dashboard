package org.simplepoint.data.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.migrationfixture.one.OneModule;
import org.simplepoint.migrationfixture.two.TwoModule;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleStorageBindings;

class ModuleMigrationEngineTest {
  private final ModuleMigrationEngine engine = new ModuleMigrationEngine();

  @Test
  void sharedSchemaPreservesIndependentModuleHistoriesAndRestartsWithoutReplay() throws Exception {
    var source = source();
    var plan = plan(false);
    assertEquals(
        List.of("one", "two"),
        plan.schemas().stream().map(ModuleMigrationPlan.ModuleSchema::module).toList());
    assertEquals(
        2,
        engine.migrate(plan, Map.of("default", source)).stream()
            .mapToInt(ModuleMigrationEngine.Outcome::applied)
            .sum());
    engine.verify(plan, Map.of("default", source));
    assertEquals(
        0,
        engine.migrate(plan, Map.of("default", source)).stream()
            .mapToInt(ModuleMigrationEngine.Outcome::applied)
            .sum());
    assertTrue(table(source, "FIXTURE_ONE"));
    assertTrue(table(source, "FIXTURE_TWO"));
    assertFalse(
        plan.schemas().getFirst().historyTable().equals(plan.schemas().getLast().historyTable()));
  }

  @Test
  void storageGroupsNeverExecuteEachOthersScripts() throws Exception {
    var first = source();
    var second = source();
    engine.migrate(plan(true), Map.of("default", first, "other", second));
    assertTrue(table(first, "FIXTURE_ONE"));
    assertFalse(table(first, "FIXTURE_TWO"));
    assertTrue(table(second, "FIXTURE_TWO"));
    assertFalse(table(second, "FIXTURE_ONE"));
  }

  @Test
  void legacyTablesStopAllMigrationWorkUntilExplicitlyAdopted() throws Exception {
    var source = source();
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("create table fixture_two (id bigint primary key, label varchar(40))");
      statement.execute("insert into fixture_two values (7, 'retained')");
    }
    assertEquals(
        "LEGACY_SCHEMA_ADOPTION_REQUIRED",
        assertThrows(
                ModuleMigrationException.class,
                () -> engine.migrate(plan(false), Map.of("default", source)))
            .code());
    assertFalse(table(source, "FIXTURE_ONE"));
    assertFalse(table(source, plan(false).schemas().getFirst().historyTable()));
    try (var connection = source.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("select label from fixture_two where id=7")) {
      assertTrue(rows.next());
      assertEquals("retained", rows.getString(1));
    }
  }

  @Test
  void appliedScriptChangesAndFutureSchemaVersionsFailValidation() {
    var source = source();
    var plan = plan(false);
    engine.migrate(plan, Map.of("default", source));
    var first = plan.schemas().getFirst();
    var changed =
        new ModuleMigrationPlan.ModuleSchema(
            first.module(),
            first.store(),
            first.historyTable(),
            first.tables(),
            Map.of(
                "h2",
                List.of(
                    new ModuleMigrationPlan.Script(
                        "V1__initial.sql",
                        "CREATE TABLE fixture_one (id BIGINT PRIMARY KEY, label VARCHAR(80));"))));
    assertEquals(
        "MIGRATION_VALIDATION_FAILED",
        assertThrows(
                ModuleMigrationException.class,
                () ->
                    engine.verify(
                        new ModuleMigrationPlan(List.of(changed)), Map.of("default", source)))
            .code());
    var future =
        new ModuleMigrationPlan.ModuleSchema(
            first.module(),
            first.store(),
            first.historyTable(),
            first.tables(),
            Map.of(
                "h2",
                List.of(
                    first.scripts().get("h2").getFirst(),
                    new ModuleMigrationPlan.Script(
                        "V2__add_code.sql",
                        "ALTER TABLE fixture_one ADD COLUMN code VARCHAR(20);"))));
    engine.migrate(new ModuleMigrationPlan(List.of(future)), Map.of("default", source));
    assertEquals(
        "MIGRATION_VALIDATION_FAILED",
        assertThrows(
                ModuleMigrationException.class,
                () -> engine.verify(plan, Map.of("default", source)))
            .code());
  }

  @Test
  void laterChecksumFailurePreventsEarlierPendingModulesFromChangingTheDatabase() throws Exception {
    var source = source();
    var original = plan(false);
    engine.migrate(original, Map.of("default", source));
    var one = original.schemas().getFirst();
    var two = original.schemas().getLast();
    var pending =
        new ModuleMigrationPlan.ModuleSchema(
            one.module(),
            one.store(),
            one.historyTable(),
            one.tables(),
            Map.of(
                "h2",
                List.of(
                    one.scripts().get("h2").getFirst(),
                    new ModuleMigrationPlan.Script(
                        "V2__probe.sql",
                        "CREATE TABLE migration_probe (id BIGINT PRIMARY KEY);"))));
    var invalid =
        new ModuleMigrationPlan.ModuleSchema(
            two.module(),
            two.store(),
            two.historyTable(),
            two.tables(),
            Map.of(
                "h2",
                List.of(
                    new ModuleMigrationPlan.Script(
                        "V1__initial.sql",
                        "CREATE TABLE fixture_two (id BIGINT PRIMARY KEY, label VARCHAR(80));"))));
    assertEquals(
        "MIGRATION_VALIDATION_FAILED",
        assertThrows(
                ModuleMigrationException.class,
                () ->
                    engine.migrate(
                        new ModuleMigrationPlan(List.of(pending, invalid)),
                        Map.of("default", source)))
            .code());
    assertFalse(table(source, "MIGRATION_PROBE"));
  }

  @Test
  void separateGroupsCannotAliasTheSameDatasource() {
    var source = source();
    assertEquals(
        "ALIASED_MIGRATION_STORAGE",
        assertThrows(
                ModuleMigrationException.class,
                () -> engine.migrate(plan(true), Map.of("default", source, "other", source)))
            .code());
  }

  @Test
  void existingViewWithAnOwnedTableNameCannotBeOverwrittenOrAutomaticallyAdopted()
      throws Exception {
    var source = source();
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("CREATE VIEW fixture_two AS SELECT 1 AS id, 'legacy' AS label");
    }
    assertEquals(
        "LEGACY_SCHEMA_ADOPTION_REQUIRED",
        assertThrows(
                ModuleMigrationException.class,
                () -> engine.migrate(plan(false), Map.of("default", source)))
            .code());
    assertFalse(table(source, "FIXTURE_ONE"));
  }

  @Test
  void explicitlySchemaFreeModulesNeedNoDummyMigrationOrDatabaseConnection() {
    var modules =
        new ModuleCatalog(List.of(org.simplepoint.migrationfixture.stateless.StatelessModule.class))
            .plan(List.of("stateless"));
    var plan =
        ModuleMigrationPlan.discover(
            modules, new ModuleStorageBindings(Map.of("stateless", binding("default"))));
    assertTrue(plan.undeclaredModules().isEmpty());
    assertTrue(engine.migrate(plan, Map.of()).isEmpty());
    engine.verify(plan, Map.of());
  }

  private static ModuleMigrationPlan plan(boolean separate) {
    var modules = new ModuleCatalog(List.of(OneModule.class, TwoModule.class)).plan(List.of("two"));
    return ModuleMigrationPlan.discover(
        modules,
        new ModuleStorageBindings(
            Map.of("one", binding("default"), "two", binding(separate ? "other" : "default"))));
  }

  private static ModuleStorageBindings.Binding binding(String store) {
    return new ModuleStorageBindings.Binding(
        store, store + "Source", store + "Factory", store + "Em", store + "Tx", store + "Jdbc");
  }

  private static JdbcDataSource source() {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:migrations-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    source.setUser("sa");
    return source;
  }

  private static boolean table(JdbcDataSource source, String name) throws SQLException {
    try (var connection = source.getConnection();
        var tables =
            connection
                .getMetaData()
                .getTables(
                    connection.getCatalog(),
                    connection.getSchema(),
                    name,
                    new String[] {"TABLE"})) {
      return tables.next();
    }
  }
}
