package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flowable.engine.ProcessEngineConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationException;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs the packaged workflow migration and native engine in a disposable PostgreSQL schema. */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class WorkflowModuleMigrationPostgresTest {
  private final ModuleMigrationEngine migrations = new ModuleMigrationEngine();
  private String schema;
  private MigrationPostgresDatabase database;
  private DriverManagerDataSource source;
  private ModuleMigrationPlan plan;

  @BeforeEach
  void setup() {
    database = new MigrationPostgresDatabase();
    schema = database.schema();
    source = database.source();
    var modules = ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("workflow"));
    var binding = new ModuleStorageBindings.Binding("default", "source", "emf", "em", "tx", "jdbc");
    plan =
        ModuleMigrationPlan.discover(
            modules, new ModuleStorageBindings(Map.of("workflow", binding)));
  }

  @AfterEach
  void cleanup() {
    if (database != null) {
      database.close();
    }
  }

  @Test
  void realModuleBaselineSupportsNativeWorkflowExecutionAndRestart() {
    var installed = migrations.migrate(plan, Map.of("default", source));
    assertEquals(
        3,
        installed.stream()
            .filter(result -> result.module().equals("workflow"))
            .findFirst()
            .orElseThrow()
            .applied());
    migrations.verify(plan, Map.of("default", source));
    var engine =
        ProcessEngineConfiguration.createStandaloneProcessEngineConfiguration()
            .setDataSource(source)
            .setDatabaseSchema(schema)
            .setDatabaseSchemaUpdate("false")
            .setAsyncExecutorActivate(false)
            .buildProcessEngine();
    try {
      try (var context =
          new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
        context.registerBean(
            org.simplepoint.platform.composition.ModulePlan.class,
            () -> ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("workflow")));
        context.registerBean("dataSource", javax.sql.DataSource.class, () -> source);
        context.register(Persistence.class);
        context.refresh();
        var binding = context.getBean(ModuleStorageBindings.class).require("workflow");
        var entityManager =
            context.getBean(binding.entityManager(), jakarta.persistence.EntityManager.class);
        assertEquals(
            0L,
            entityManager
                .createQuery("select count(d) from WorkflowDefinitionEntity d", Long.class)
                .getSingleResult());
      }
      engine
          .getRepositoryService()
          .createDeployment()
          .addString(
              "migration.bpmn20.xml",
              """
              <?xml version="1.0" encoding="UTF-8"?>
              <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="migration">
                <process id="migration_approval" isExecutable="true">
                  <startEvent id="start"/><userTask id="approve" name="审核"/><endEvent id="end"/>
                  <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                  <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                </process>
              </definitions>
              """)
          .deploy();
      var instance = engine.getRuntimeService().startProcessInstanceByKey("migration_approval");
      var task =
          engine
              .getTaskService()
              .createTaskQuery()
              .processInstanceId(instance.getId())
              .singleResult();
      assertNotNull(task);
      engine.getTaskService().complete(task.getId());
      assertNotNull(
          engine
              .getHistoryService()
              .createHistoricProcessInstanceQuery()
              .processInstanceId(instance.getId())
              .singleResult()
              .getEndTime());
      assertEquals(0, migrations.migrate(plan, Map.of("default", source)).getFirst().applied());
    } finally {
      engine.close();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
  void trackedVersionsUpgradeWithoutChangingExistingWorkflowData(int version) {
    var previous =
        new ModuleMigrationPlan(
            plan.schemas().stream()
                .filter(owner -> !owner.module().equals("platform-events"))
                .map(
                    owner -> {
                      if (!owner.module().equals("workflow")) {
                        return owner;
                      }
                      return new ModuleMigrationPlan.ModuleSchema(
                          owner.module(),
                          owner.store(),
                          owner.historyTable(),
                          owner.tables().stream()
                              .filter(
                                  table ->
                                      version == 1
                                          ? !table.startsWith("simpoint_workflow_member_label")
                                          : !table.equals("simpoint_workflow_member_label_refresh"))
                              .toList(),
                          Map.of(
                              "postgresql", owner.scripts().get("postgresql").subList(0, version)));
                    })
                .toList());
    migrations.migrate(previous, Map.of("default", source));
    var jdbc = new JdbcTemplate(source);
    jdbc.update(
        """
        insert into simpoint_workflow_definitions
          (id,tenant_id,definition_key,name,status,draft_xml,created_by,created_at,updated_at)
        values ('existing','tenant','approval','原有流程','DRAFT','<draft/>','owner',now(),now())
        """);
    if (version == 2) {
      jdbc.update(
          """
          insert into simpoint_workflow_member_label_state
          (tenant_id,active_generation,source_epoch,source_revision,tenant_active,observed_epoch,observed_revision)
          values ('tenant','generation','00000000-0000-0000-0000-000000000001',7,true,'00000000-0000-0000-0000-000000000001',7)
          """);
      jdbc.update(
          "insert into simpoint_workflow_member_labels(tenant_id,generation,user_id,name) values"
              + " ('tenant','generation','owner','原有名称')");
    }
    var before =
        jdbc.queryForMap("select * from simpoint_workflow_definitions where id='existing'");
    assertEquals(
        3 - version,
        migrations.migrate(plan, Map.of("default", source)).stream()
            .filter(result -> result.module().equals("workflow"))
            .findFirst()
            .orElseThrow()
            .applied());
    assertEquals(
        before,
        jdbc.queryForMap("select * from simpoint_workflow_definitions where id='existing'"));
    assertEquals(
        version == 2 ? 1L : 0L,
        jdbc.queryForObject(
            "select count(*) from simpoint_workflow_member_label_state", Long.class));
    migrations.verify(plan, Map.of("default", source));
    if (version == 2) {
      assertEquals(
          "原有名称",
          jdbc.queryForObject("select name from simpoint_workflow_member_labels", String.class));
      assertEquals(
          "tenant",
          jdbc.queryForObject(
              "select tenant_id from simpoint_workflow_member_label_refresh", String.class));
    }
  }

  @Test
  void failedTransactionalDdlRollsBackAndTheSameScriptCanResumeAfterItsExternalCauseIsFixed() {
    migrations.migrate(plan, Map.of("default", source));
    var extended =
        append(
            "V4__transaction_probe.sql",
            "CREATE TABLE migration_probe (id BIGINT PRIMARY KEY); INSERT INTO migration_external"
                + " VALUES (1);");
    assertEquals(
        "MODULE_MIGRATION_FAILED",
        assertThrows(
                ModuleMigrationException.class,
                () -> migrations.migrate(extended, Map.of("default", source)))
            .code());
    var jdbc = new JdbcTemplate(source);
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_schema=? and"
                + " table_name='migration_probe'",
            Integer.class,
            schema));
    jdbc.execute("CREATE TABLE migration_external (id BIGINT PRIMARY KEY)");
    assertEquals(1, migrations.migrate(extended, Map.of("default", source)).getFirst().applied());
    assertEquals(1, jdbc.queryForObject("select count(*) from migration_external", Integer.class));
    migrations.verify(extended, Map.of("default", source));
  }

  @Test
  void concurrentInstallersApplyOneLogicalMigrationOnce() throws Exception {
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first = workers.submit(() -> migrations.migrate(plan, Map.of("default", source)));
      var second = workers.submit(() -> migrations.migrate(plan, Map.of("default", source)));
      int applied =
          java.util.stream.Stream.concat(
                  first.get(40, TimeUnit.SECONDS).stream(),
                  second.get(40, TimeUnit.SECONDS).stream())
              .mapToInt(ModuleMigrationEngine.Outcome::applied)
              .sum();
      assertEquals(5, applied);
      migrations.verify(plan, Map.of("default", source));
    }
  }

  @Test
  void relocatedLegacySchemaRemainsRepeatableButCannotBeSilentlyBaselined() {
    var legacy =
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource(
                "META-INF/simplepoint/modules/workflow/db/legacy.sql"));
    legacy.execute(source);
    legacy.execute(source);
    assertEquals(
        "LEGACY_SCHEMA_ADOPTION_REQUIRED",
        assertThrows(
                ModuleMigrationException.class,
                () -> migrations.migrate(plan, Map.of("default", source)))
            .code());
    assertEquals(
        10,
        new JdbcTemplate(source)
            .queryForObject(
                "select count(*) from information_schema.tables where table_schema=? and table_name"
                    + " like 'simpoint_workflow_%'",
                Integer.class, schema));
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @org.simplepoint.data.jpa.composition.EnableModulePersistence
  static class Persistence {}

  @Test
  void missingCurrentSchemaCannotFallBackToAnotherDatabaseNamespace() {
    var unconfigured =
        new DriverManagerDataSource(
            System.getenv("SP_MIGRATIONS_POSTGRES_URL")
                + "?currentSchema=missing_"
                + UUID.randomUUID().toString().replace("-", ""),
            System.getenv("SP_MIGRATIONS_POSTGRES_USER"),
            System.getenv("SP_MIGRATIONS_POSTGRES_PASSWORD"));
    assertEquals(
        "MISSING_MIGRATION_SCHEMA",
        assertThrows(
                ModuleMigrationException.class,
                () -> migrations.migrate(plan, Map.of("default", unconfigured)))
            .code());
  }

  private ModuleMigrationPlan append(String name, String sql) {
    var original =
        plan.schemas().stream()
            .filter(owner -> owner.module().equals("workflow"))
            .findFirst()
            .orElseThrow();
    var scripts = new ArrayList<>(original.scripts().get("postgresql"));
    scripts.add(new ModuleMigrationPlan.Script(name, sql));
    return new ModuleMigrationPlan(
        List.of(
            new ModuleMigrationPlan.ModuleSchema(
                original.module(),
                original.store(),
                original.historyTable(),
                original.tables(),
                Map.of("postgresql", scripts))));
  }
}
