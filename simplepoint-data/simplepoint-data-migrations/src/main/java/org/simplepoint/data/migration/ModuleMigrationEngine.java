package org.simplepoint.data.migration;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.simplepoint.data.migration.ModuleMigrationPlan.ModuleSchema;

/**
 * Executes selected module migrations with Flyway locks/checksums and database-specific DDL
 * atomicity.
 */
public final class ModuleMigrationEngine {
  /** Checks that every declared migration is applied and unchanged, without running SQL scripts. */
  public void verify(ModuleMigrationPlan plan, Map<String, DataSource> sources) {
    requireComplete(plan);
    sources = checkedSources(plan, sources);
    for (var schema : plan.schemas()) {
      var source = source(schema, sources);
      var state = inspect(schema, source);
      if (!state.history()) {
        throw failure("MISSING_MIGRATION_HISTORY", schema, null);
      }
      verifyAdoptedBaseline(schema, source);
      try {
        flyway(schema, state, source, false).validate();
      } catch (FlywayException invalid) {
        throw failure("MIGRATION_VALIDATION_FAILED", schema, invalid);
      }
    }
  }

  /**
   * Applies dependency-ordered scripts using an installation/migration identity. Existing owned
   * tables without history require a separately verified legacy adoption; they are never skipped.
   */
  public List<Outcome> migrate(ModuleMigrationPlan plan, Map<String, DataSource> sources) {
    requireComplete(plan);
    sources = checkedSources(plan, sources);
    var prepared = new ArrayList<Prepared>();
    // Preflight every store/module before the first migration changes any database.
    for (var schema : plan.schemas()) {
      var source = source(schema, sources);
      var state = inspect(schema, source);
      if (!state.history() && state.ownedTables()) {
        throw failure("LEGACY_SCHEMA_ADOPTION_REQUIRED", schema, null);
      }
      if (state.history()) {
        verifyAdoptedBaseline(schema, source);
        try {
          flyway(schema, state, source, true).validate();
        } catch (FlywayException invalid) {
          throw failure("MIGRATION_VALIDATION_FAILED", schema, invalid);
        }
      }
      var migration = flyway(schema, state, source, false);
      try {
        // Resolve and validate version identities even on a new database before any writes.
        migration.info();
      } catch (FlywayException invalid) {
        throw failure("MIGRATION_VALIDATION_FAILED", schema, invalid);
      }
      prepared.add(new Prepared(schema, migration));
    }
    var outcomes = new ArrayList<Outcome>();
    for (var work : prepared) {
      try {
        var result = work.flyway().migrate();
        outcomes.add(
            new Outcome(work.schema().module(), work.schema().store(), result.migrationsExecuted));
      } catch (FlywayException failed) {
        throw failure("MODULE_MIGRATION_FAILED", work.schema(), failed);
      }
    }
    return List.copyOf(outcomes);
  }

  private static void requireComplete(ModuleMigrationPlan plan) {
    if (!plan.undeclaredModules().isEmpty()) {
      throw new ModuleMigrationException(
          "INCOMPLETE_MIGRATION_PLAN", String.join(",", plan.undeclaredModules()), null);
    }
  }

  private static void verifyAdoptedBaseline(ModuleSchema schema, DataSource source) {
    try (var connection = source.getConnection()) {
      ModuleSchemaAdoption.verifyBaseline(connection, schema);
    } catch (SQLException failure) {
      throw failure("ADOPTION_RECEIPT_UNAVAILABLE", schema, failure);
    }
  }

  private static Map<String, DataSource> checkedSources(
      ModuleMigrationPlan plan, Map<String, DataSource> sources) {
    var fixed = Map.copyOf(sources);
    var owners = new java.util.IdentityHashMap<DataSource, String>();
    for (var schema : plan.schemas()) {
      var source = source(schema, fixed);
      String previous = owners.putIfAbsent(source, schema.store());
      if (previous != null && !previous.equals(schema.store())) {
        throw failure("ALIASED_MIGRATION_STORAGE", schema, null);
      }
    }
    return fixed;
  }

  private static DataSource source(ModuleSchema schema, Map<String, DataSource> sources) {
    var source = sources.get(schema.store());
    if (source == null) {
      throw failure("MISSING_MIGRATION_DATASOURCE", schema, null);
    }
    return source;
  }

  private static State inspect(ModuleSchema schema, DataSource source) {
    try (var connection = source.getConnection()) {
      String product = connection.getMetaData().getDatabaseProductName();
      String dialect =
          switch (product) {
            case "PostgreSQL" -> "postgresql";
            case "H2" -> "h2";
            default -> throw failure("UNSUPPORTED_MIGRATION_DATABASE", schema, null);
          };
      if (!schema.scripts().containsKey(dialect)) {
        throw failure("MISSING_MIGRATION_DIALECT", schema, null);
      }
      String namespace = connection.getSchema();
      if (namespace == null || namespace.isBlank()) {
        throw failure("MISSING_MIGRATION_SCHEMA", schema, null);
      }
      var names = new HashSet<String>();
      try (var tables =
          connection.getMetaData().getTables(connection.getCatalog(), namespace, "%", null)) {
        while (tables.next()) {
          names.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
      }
      return new State(
          dialect,
          names.contains(schema.historyTable()),
          schema.tables().stream().anyMatch(names::contains));
    } catch (SQLException failed) {
      throw failure("MIGRATION_DATABASE_UNAVAILABLE", schema, failed);
    }
  }

  private static Flyway flyway(
      ModuleSchema schema, State state, DataSource source, boolean pendingAllowed) {
    return Flyway.configure(ModuleMigrationEngine.class.getClassLoader())
        .dataSource(source)
        .table(schema.historyTable())
        .locations(new String[0])
        .resourceProvider(
            new OwnedMigrationResources(schema.module(), schema.scripts().get(state.dialect())))
        .javaMigrationClassProvider(Set::of)
        .placeholderReplacement(false)
        .cleanDisabled(true)
        .createSchemas(false)
        // Zero never skips a declared version (all versions start at 1). This permits a new
        // module in a shared schema after the owned-table preflight, under Flyway's own lock.
        .baselineVersion("0")
        .baselineDescription("Module " + schema.module() + " empty baseline")
        .baselineOnMigrate(true)
        .ignoreMigrationPatterns(pendingAllowed ? new String[] {"*:pending"} : new String[0])
        .validateMigrationNaming(true)
        .validateOnMigrate(true)
        .outOfOrder(false)
        .lockRetryCount(30)
        .connectRetries(0)
        .load();
  }

  private static ModuleMigrationException failure(
      String code, ModuleSchema schema, Throwable cause) {
    return new ModuleMigrationException(code, schema.module(), cause);
  }

  private record State(String dialect, boolean history, boolean ownedTables) {}

  private record Prepared(ModuleSchema schema, Flyway flyway) {}

  /** Reports actual committed migration work without exposing SQL or connection details. */
  public record Outcome(String module, String store, int applied) {}
}
