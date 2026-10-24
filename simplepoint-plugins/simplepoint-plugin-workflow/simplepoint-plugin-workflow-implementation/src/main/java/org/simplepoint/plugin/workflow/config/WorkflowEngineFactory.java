package org.simplepoint.plugin.workflow.config;

import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.flowable.common.engine.impl.history.HistoryLevel;
import org.flowable.engine.ProcessEngine;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.workflow.migration.FlowableProcessMigrations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/** Shared engine construction; legacy initialization is an explicit compatibility option. */
public final class WorkflowEngineFactory {
  private WorkflowEngineFactory() {}

  /** Builds the platform engine, preserving binding and policy after trusted customization. */
  public static SpringProcessEngineConfiguration create(
      DataSource source,
      PlatformTransactionManager manager,
      Environment environment,
      ObjectProvider<WorkflowEngineCustomizer> customizers,
      boolean allowLegacyInitialization)
      throws SQLException {
    verifyEngineVersion();
    String schemaUpdate = environment.getProperty("flowable.database-schema-update", "false");
    if (!("false".equals(schemaUpdate)
            || (allowLegacyInitialization && "true".equals(schemaUpdate)))
        || environment.getProperty("flowable.check-process-definitions", Boolean.class, false)) {
      throw failure("WORKFLOW_RUNTIME_INSTALLATION_FORBIDDEN");
    }
    final String schema;
    try (var connection = source.getConnection()) {
      schema = connection.getSchema();
    }
    if (schema == null
        || schema.isBlank()
        || !schema.equals(environment.getProperty("flowable.database-schema", schema))) {
      throw failure("WORKFLOW_SCHEMA_BINDING_MISMATCH");
    }
    var configuration = new SpringProcessEngineConfiguration();
    configuration.setEngineName("simplepoint-workflow");
    configuration.setDataSource(source);
    final var boundSource = configuration.getDataSource();
    configuration.setTransactionManager(manager);
    configuration.setDatabaseSchema(schema);
    configuration.setDatabaseSchemaUpdate(schemaUpdate);
    configuration.setHistoryLevel(
        HistoryLevel.getHistoryLevelForKey(
            environment.getProperty("flowable.history-level", "audit")));
    configuration.setAsyncExecutorActivate(
        environment.getProperty("flowable.async-executor-activate", Boolean.class, true));
    configuration.setBeans(Map.of());
    customizers.orderedStream().forEach(customizer -> customizer.configure(configuration));
    if (configuration.getDataSource() != boundSource
        || configuration.getTransactionManager() != manager
        || !schema.equals(configuration.getDatabaseSchema())
        || !schemaUpdate.equals(configuration.getDatabaseSchemaUpdate())) {
      throw failure("WORKFLOW_STORAGE_OVERRIDE");
    }
    if (configuration.getHistoryLevel() == null
        || !configuration.getHistoryLevel().isAtLeast(HistoryLevel.AUDIT)
        || configuration.getBeans() == null
        || !configuration.getBeans().isEmpty()) {
      throw failure("WORKFLOW_ENGINE_POLICY_REQUIRED");
    }
    WorkflowEngineConfiguration.isolateExpressions(configuration);
    return configuration;
  }

  private static ModuleCompositionException failure(String code) {
    return new ModuleCompositionException(code, "workflow");
  }

  private static void verifyEngineVersion() {
    try {
      // Reflect the public field: a direct constant reference would inline the build-time version.
      if (!FlowableProcessMigrations.ENGINE_VERSION.equals(
          ProcessEngine.class.getField("VERSION").get(null))) {
        throw failure("WORKFLOW_ENGINE_MIGRATION_VERSION_MISMATCH");
      }
    } catch (ReflectiveOperationException unavailable) {
      throw failure("WORKFLOW_ENGINE_MIGRATION_VERSION_MISMATCH");
    }
  }
}
