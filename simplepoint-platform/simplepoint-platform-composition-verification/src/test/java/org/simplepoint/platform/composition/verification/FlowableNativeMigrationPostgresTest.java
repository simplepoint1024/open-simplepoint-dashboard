package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationException;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.simplepoint.workflow.migration.FlowableProcessMigrations;

/**
 * Compares vendor installation with the managed baseline and verifies recovery on real PostgreSQL.
 */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class FlowableNativeMigrationPostgresTest {
  private final ModuleMigrationEngine migrations = new ModuleMigrationEngine();

  @Test
  void managedNativeSchemaMatchesVendorAndPreservesAnInFlightProcessAcrossRestart() {
    try (var managed = new MigrationPostgresDatabase();
        var vendor = new MigrationPostgresDatabase()) {
      engine(vendor, true).close();
      var plan = plan();
      assertEquals(
          Map.of("workflow", 3, "flowable-process", 1, "platform-events", 1),
          migrations.migrate(plan, Map.of("default", managed.source())).stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      ModuleMigrationEngine.Outcome::module,
                      ModuleMigrationEngine.Outcome::applied)));
      assertEquals(vendor.physicalContract("act_%"), managed.physicalContract("act_%"));
      assertEquals(vendor.physicalContract("flw_%"), managed.physicalContract("flw_%"));
      final String instance;
      var first = engine(managed, false);
      try {
        // Engine startup adds cfg.* values in either mode; compare after both have started.
        for (String propertyTable : List.of("act_ge_property", "act_id_property")) {
          String sql =
              "select name_,value_,rev_ from "
                  + propertyTable
                  + " where name_ <> 'next.dbid' order by name_";
          assertEquals(vendor.jdbc().queryForList(sql), managed.jdbc().queryForList(sql));
        }
        first
            .getRepositoryService()
            .createDeployment()
            .addString(
                "restart.bpmn20.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             targetNamespace="native-migration">
                  <process id="native_restart" isExecutable="true">
                    <startEvent id="start"/><userTask id="review"/><endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                    <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
                  </process>
                </definitions>
                """)
            .deploy();
        instance = first.getRuntimeService().startProcessInstanceByKey("native_restart").getId();
      } finally {
        first.close();
      }
      assertTrue(
          migrations.migrate(plan, Map.of("default", managed.source())).stream()
              .allMatch(result -> result.applied() == 0));
      migrations.verify(plan, Map.of("default", managed.source()));
      var restarted = engine(managed, false);
      try {
        var task =
            restarted.getTaskService().createTaskQuery().processInstanceId(instance).singleResult();
        assertNotNull(task);
        restarted.getTaskService().complete(task.getId());
        assertNotNull(
            restarted
                .getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(instance)
                .singleResult()
                .getEndTime());
      } finally {
        restarted.close();
      }
    }
  }

  @Test
  void untrackedNativeTablesRejectTheEntirePlanBeforePlatformTablesAreCreated() {
    try (var database = new MigrationPostgresDatabase()) {
      engine(database, true).close();
      assertEquals(
          "LEGACY_SCHEMA_ADOPTION_REQUIRED",
          assertThrows(
                  ModuleMigrationException.class,
                  () -> migrations.migrate(plan(), Map.of("default", database.source())))
              .code());
      assertEquals(
          0,
          database
              .jdbc()
              .queryForObject(
                  """
                  select count(*) from pg_tables where schemaname=current_schema()
                    and (tablename like 'sp_migration_%' or tablename like 'simpoint_workflow_%')
                  """,
                  Integer.class));
    }
  }

  @Test
  void nativeBaselineDdlRollsBackAsOneVersionAndCanResumeAfterExternalFailure() {
    try (var database = new MigrationPostgresDatabase()) {
      var plan = plan();
      var nativeSchema = nativeSchema(plan);
      var script = nativeSchema.scripts().get("postgresql").getFirst();
      var failing =
          new ModuleMigrationPlan.ModuleSchema(
              nativeSchema.module(),
              nativeSchema.store(),
              nativeSchema.historyTable(),
              nativeSchema.tables(),
              Map.of(
                  "postgresql",
                  List.of(
                      new ModuleMigrationPlan.Script(
                          script.name(),
                          script.sql() + "\ninsert into migration_external values (1);\n"))));
      var extended =
          new ModuleMigrationPlan(
              plan.schemas().stream()
                  .map(schema -> schema == nativeSchema ? failing : schema)
                  .toList());
      assertEquals(
          "MODULE_MIGRATION_FAILED",
          assertThrows(
                  ModuleMigrationException.class,
                  () -> migrations.migrate(extended, Map.of("default", database.source())))
              .code());
      assertEquals(
          0,
          database
              .jdbc()
              .queryForObject(
                  """
                  select count(*) from pg_tables where schemaname=current_schema()
                    and (tablename like 'act_%' or tablename like 'flw_%')
                  """,
                  Integer.class));
      database.jdbc().execute("create table migration_external (id integer primary key)");
      assertEquals(
          1,
          migrations.migrate(extended, Map.of("default", database.source())).stream()
              .mapToInt(ModuleMigrationEngine.Outcome::applied)
              .sum());
      migrations.verify(extended, Map.of("default", database.source()));
      engine(database, false).close();
      assertEquals(
          1,
          database.jdbc().queryForObject("select count(*) from migration_external", Integer.class));
    }
  }

  private ModuleMigrationPlan plan() {
    var modules = ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("workflow"));
    return ModuleMigrationPlan.discover(
        modules,
        new ModuleStorageBindings(
            Map.of(
                "workflow",
                new ModuleStorageBindings.Binding(
                    "default", "source", "emf", "em", "tx", "jdbc"))));
  }

  private static ModuleMigrationPlan.ModuleSchema nativeSchema(ModuleMigrationPlan plan) {
    return plan.schemas().stream()
        .filter(schema -> schema.module().equals(FlowableProcessMigrations.OWNER))
        .findFirst()
        .orElseThrow();
  }

  private static ProcessEngine engine(MigrationPostgresDatabase database, boolean install) {
    return ProcessEngineConfiguration.createStandaloneProcessEngineConfiguration()
        .setDataSource(database.source())
        .setDatabaseSchema(database.schema())
        .setDatabaseSchemaUpdate(Boolean.toString(install))
        .setAsyncExecutorActivate(false)
        .buildProcessEngine();
  }
}
