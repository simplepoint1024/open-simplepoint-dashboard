package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.simplepoint.data.jpa.composition.ModulePersistencePlan;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.ConnectionCallback;

/** Compares the new baseline against the existing host's mapping and post-Hibernate DDL. */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class AiLegacySchemaContractPostgresTest {
  @Test
  void baselinePreservesDeployedColumnsConstraintsAndIndexes() throws Exception {
    var modules =
        ModuleCatalog.discover(getClass().getClassLoader())
            .plan(List.of("ai-catalog", "ai-knowledge"));
    try (var current = new MigrationPostgresDatabase();
        var legacy = new MigrationPostgresDatabase();
        var context = new AnnotationConfigApplicationContext()) {
      var persistence =
          ModulePersistencePlan.discover(
              modules, context.getEnvironment(), new DefaultResourceLoader());
      var plan = ModuleMigrationPlan.discover(modules, persistence.bindings());
      new ModuleMigrationEngine().migrate(plan, Map.of("default", current.source()));
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "legacy-schema-fixture",
                  Map.of(
                      "spring.jpa.hibernate.ddl-auto",
                      "create",
                      "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error",
                      "true")));
      context.registerBean(ModulePlan.class, () -> modules);
      context.registerBean("dataSource", DataSource.class, legacy::source);
      context.register(AiModuleMigrationPostgresTest.Persistence.class);
      context.refresh();
      String script = Files.readString(Path.of(System.getProperty("simplepoint.ai.legacy-sql")));
      String boundary = "-- Hibernate creates the entity tables";
      assertTrue(script.contains(boundary), "Review this fixture when the legacy script changes");
      // Only the schema contract is compared. Historical grant rewrites, document requeue and
      // storage backfills need a separate adoption protocol and must never run in a fresh baseline.
      String ddl =
          script.substring(script.indexOf(boundary)).replaceAll("(?m)^\\s*--[^\\r\\n]*", "");
      legacy
          .jdbc()
          .execute(
              (ConnectionCallback<Void>)
                  connection -> {
                    try (var statement = connection.createStatement()) {
                      statement.execute("set search_path = " + legacy.schema() + ", public");
                      for (String fragment : ddl.split(";")) {
                        String sql = fragment.strip();
                        if (sql.isEmpty() || sql.matches("(?s)^(UPDATE|INSERT|DELETE)\\b.*")) {
                          continue;
                        }
                        // This legacy asset has no procedural blocks or semicolons inside string
                        // literals.
                        // Fail rather than silently omit an unfamiliar schema operation.
                        assertTrue(
                            sql.matches(
                                "(?s)^(ALTER TABLE|CREATE (UNIQUE )?INDEX|CREATE TABLE|DROP"
                                    + " INDEX)\\b.*"),
                            sql);
                        statement.execute(sql);
                      }
                    }
                    return null;
                  });
      assertEquals(
          legacy.physicalContract("simpoint_ai_%"), current.physicalContract("simpoint_ai_%"));
    }
  }
}
