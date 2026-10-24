package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flowable.engine.ProcessEngine;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.simplepoint.plugin.workflow.config.ModularWorkflowEngineConfiguration;
import org.simplepoint.plugin.workflow.entity.WorkflowDefinitionEntity;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual engine/store binding and JPA atomicity after the complete versioned installation. */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class WorkflowEngineStoragePostgresTest {
  private static final String BPMN =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="composition">
        <process id="storage_approval" isExecutable="true">
          <startEvent id="start"/><userTask id="approve" name="审核"/><endEvent id="end"/>
          <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
          <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
        </process>
      </definitions>
      """;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nativeEngineUsesOwnedStoreAndSharesJpaTransactionDespiteForeignDefaults(boolean primary) {
    try (var owned = new MigrationPostgresDatabase();
        var foreign = new MigrationPostgresDatabase()) {
      final SpringProcessEngineConfiguration configuration;
      try (var context = context(owned, foreign, primary)) {
        context.refresh();
        var binding = context.getBean(ModuleStorageBindings.class).require("workflow");
        var manager =
            context.getBean(binding.transactionManager(), PlatformTransactionManager.class);
        configuration = context.getBean(SpringProcessEngineConfiguration.class);
        assertSame(
            owned.source(),
            ((TransactionAwareDataSourceProxy) configuration.getDataSource())
                .getTargetDataSource());
        assertSame(manager, configuration.getTransactionManager());
        assertEquals(owned.schema(), configuration.getDatabaseSchema());
        assertEquals("false", configuration.getDatabaseSchemaUpdate());
        assertTrue(configuration.getAsyncExecutor().isActive());
        var engine = context.getBean(ProcessEngine.class);
        var em = context.getBean(binding.entityManager(), EntityManager.class);
        var transaction = new TransactionTemplate(manager);
        transaction.executeWithoutResult(
            status -> {
              em.persist(definition("rolled-back"));
              em.flush();
              engine
                  .getRepositoryService()
                  .createDeployment()
                  .addString("fixture.bpmn20.xml", BPMN)
                  .deploy();
              status.setRollbackOnly();
            });
        assertEquals(
            0L,
            owned
                .jdbc()
                .queryForObject("select count(*) from simpoint_workflow_definitions", Long.class));
        assertEquals(0, engine.getRepositoryService().createDeploymentQuery().count());
        transaction.executeWithoutResult(
            status -> {
              em.persist(definition("committed"));
              em.flush();
              engine
                  .getRepositoryService()
                  .createDeployment()
                  .addString("fixture.bpmn20.xml", BPMN)
                  .deploy();
            });
        assertEquals(
            1L,
            owned
                .jdbc()
                .queryForObject("select count(*) from simpoint_workflow_definitions", Long.class));
        var instance = engine.getRuntimeService().startProcessInstanceByKey("storage_approval");
        var task =
            engine
                .getTaskService()
                .createTaskQuery()
                .processInstanceId(instance.getId())
                .singleResult();
        assertNotNull(task);
        transaction.executeWithoutResult(
            status -> {
              engine.getTaskService().complete(task.getId());
              status.setRollbackOnly();
            });
        assertEquals(1, engine.getTaskService().createTaskQuery().taskId(task.getId()).count());
        engine.getTaskService().complete(task.getId());
        assertNotNull(
            engine
                .getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(instance.getId())
                .singleResult()
                .getEndTime());
        assertEquals(
            0L,
            foreign
                .jdbc()
                .queryForObject(
                    "select count(*) from pg_tables where schemaname=current_schema()",
                    Long.class));
      }
      assertFalse(
          configuration.getAsyncExecutor().isActive(), "Engine workers must stop with the host");
    }
  }

  @Test
  void absentNativeSchemaFailsStartupWithoutInstallingTables() {
    try (var owned = new MigrationPostgresDatabase();
        var foreign = new MigrationPostgresDatabase();
        var context = context(owned, foreign, true)) {
      // Deliberately damage an installed native schema: runtime must never repair it.
      owned.jdbc().execute("drop table act_ge_property");
      var before =
          owned
              .jdbc()
              .queryForList(
                  "select tablename from pg_tables where schemaname=current_schema() order by"
                      + " tablename",
                  String.class);
      assertThrows(RuntimeException.class, context::refresh);
      assertEquals(
          before,
          owned
              .jdbc()
              .queryForList(
                  "select tablename from pg_tables where schemaname=current_schema() order by"
                      + " tablename",
                  String.class));
    }
  }

  private AnnotationConfigApplicationContext context(
      MigrationPostgresDatabase owned, MigrationPostgresDatabase foreign, boolean primary) {
    var context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "stores",
                Map.of(
                    "simplepoint.persistence.modules.workflow", "owned",
                    "simplepoint.persistence.sources.owned", "workflowSource")));
    var modules = ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("workflow"));
    var persistence =
        ModulePersistencePlan.discover(
            modules, context.getEnvironment(), new DefaultResourceLoader());
    new ModuleMigrationEngine()
        .migrate(
            ModuleMigrationPlan.discover(modules, persistence.bindings()),
            Map.of("owned", owned.source()));
    context.registerBean(ModulePlan.class, () -> modules);
    context.registerBean("workflowSource", DataSource.class, owned::source);
    context.registerBean(
        "dataSource",
        DataSource.class,
        foreign::source,
        definition -> definition.setPrimary(primary));
    context.registerBean(
        "foreignTx",
        PlatformTransactionManager.class,
        () -> new DataSourceTransactionManager(foreign.source()),
        definition -> definition.setPrimary(primary));
    context.register(Persistence.class, ModularWorkflowEngineConfiguration.class);
    return context;
  }

  private static WorkflowDefinitionEntity definition(String id) {
    var definition = new WorkflowDefinitionEntity();
    definition.setId(id);
    definition.setTenantId("tenant");
    definition.setKey(id);
    definition.setName("组合事务验证");
    definition.setStatus("DRAFT");
    definition.setDraftXml(BPMN);
    definition.setCreatedBy("fixture");
    definition.setCreatedAt(Instant.now());
    definition.setUpdatedAt(Instant.now());
    return definition;
  }

  @Configuration(proxyBeanMethods = false)
  @EnableModulePersistence
  static class Persistence {}
}
