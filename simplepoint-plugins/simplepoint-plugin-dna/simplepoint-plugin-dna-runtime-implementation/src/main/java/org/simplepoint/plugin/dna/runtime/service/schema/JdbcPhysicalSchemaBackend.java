package org.simplepoint.plugin.dna.runtime.service.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalColumnSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalIndexSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalTableSnapshot;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.springframework.stereotype.Component;

/** Managed JDBC bridge for schema planning, execution, and post-DDL verification. */
@Component
public class JdbcPhysicalSchemaBackend {

  private static final int STATEMENT_TIMEOUT_SECONDS = 60;

  private final ManagedRuntimeDataSourceProvider dataSourceProvider;
  private final PhysicalSchemaSnapshotCodec snapshotCodec;

  /** Creates the backend from the platform's managed datasource and dialect services. */
  public JdbcPhysicalSchemaBackend(
      final ManagedRuntimeDataSourceProvider dataSourceProvider,
      final PhysicalSchemaSnapshotCodec snapshotCodec) {
    this.dataSourceProvider = dataSourceProvider;
    this.snapshotCodec = snapshotCodec;
  }

  /** Holds one target session and its exclusive DDL lock for the complete publication attempt. */
  public <T> T withExclusivePublication(
      PhysicalSchemaTarget target, java.util.function.Function<PublicationSession, T> callback) {
    return withExclusiveSchema(target, target, null, callback);
  }

  /** Shared table provisioning requires a verified model in the same locked namespace. */
  <T> T withExclusiveInfrastructure(
      PhysicalSchemaTarget model,
      RuntimeInfrastructureTable table,
      java.util.function.Function<PublicationSession, T> callback) {
    return withExclusiveSchema(table.target(model), model, table, callback);
  }

  private <T> T withExclusiveSchema(
      PhysicalSchemaTarget target,
      PhysicalSchemaTarget model,
      RuntimeInfrastructureTable infrastructure,
      java.util.function.Function<PublicationSession, T> callback) {
    return withContext(
        target,
        context -> {
          var metadata = target.metadataTarget();
          String catalog = context.handle().metadataCatalog();
          String schema = context.handle().metadataSchema();
          String identity =
              java.util.Arrays.asList(catalog, schema, metadata.tableName()).stream()
                  .map(value -> value == null ? "-:" : value.length() + ":" + value)
                  .collect(java.util.stream.Collectors.joining());
          String key = snapshotCodec.statementChecksum("schema-publication-session-v1", identity);
          try (var ownership =
                  PhysicalPublicationOwnership.open(context.connection(), context.handle(), model);
              var ignored =
                  SchemaPublicationSessionLock.acquire(
                      context.connection(), context.runtimeDialect().dialectId(), key)) {
            if (infrastructure != null) {
              PhysicalPublicationOwnership.reserveInfrastructure(ownership, infrastructure);
            }
            return callback.apply(publicationSession(context, target, ownership));
          }
        });
  }

  private PublicationSession publicationSession(
      ExecutionContext context,
      PhysicalSchemaTarget target,
      org.simplepoint.data.ownership.PhysicalOwnershipSession ownership) {
    return new PublicationSession() {
      @Override
      public void prepareInfrastructure() {
        for (var table : RuntimeInfrastructureTable.values()) {
          PhysicalPublicationOwnership.reserveInfrastructure(ownership, table);
          RuntimeInfrastructureSchema.reconcile(
              JdbcPhysicalSchemaBackend.this,
              publicationSession(context, table.target(target), ownership),
              table);
        }
      }

      @Override
      public InitialSchemaPlan plan(
          String comment, PhysicalSchemaDefinition current, PhysicalSchemaDefinition desired) {
        return invoke(
            () -> {
              ownership.verifyExecutionSession();
              return current == null
                  ? context
                      .runtimeDialect()
                      .planInitial(
                          context.connection(),
                          context.coreDialect(),
                          context.supportContext(),
                          context.database(),
                          target,
                          comment,
                          desired)
                  : context
                      .runtimeDialect()
                      .planEvolution(
                          context.connection(),
                          context.coreDialect(),
                          context.supportContext(),
                          context.database(),
                          target,
                          comment,
                          current,
                          desired);
            });
      }

      @Override
      public void verifyReferences(PhysicalSchemaDefinition schema, PhysicalTableSnapshot actual) {
        invoke(
            () -> {
              ownership.verifyExecutionSession();
              context
                  .runtimeDialect()
                  .verifyReferenceData(
                      context.connection(),
                      context.coreDialect(),
                      context.supportContext(),
                      target,
                      schema,
                      actual);
              return null;
            });
      }

      @Override
      public PhysicalTableSnapshot inspect(String dialect) {
        requireDialect(dialect, context.runtimeDialect());
        return invoke(
            () -> {
              ownership.verifyExecutionSession();
              return context
                  .runtimeDialect()
                  .inspect(
                      context.connection(),
                      context.coreDialect(),
                      context.supportContext(),
                      target);
            });
      }

      @Override
      public void execute(String dialect, PlannedSchemaStep step) {
        requireDialect(dialect, context.runtimeDialect());
        invoke(
            () -> {
              ownership.verifyExecutionSession();
              executeStatement(context.connection(), step);
              return null;
            });
      }
    };
  }

  /** Operations sharing one verified ownership identity and one physical DDL session. */
  public interface PublicationSession {
    /** Prepares the complete owned infrastructure on this already locked executing connection. */
    default void prepareInfrastructure() {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_OWNERSHIP_UNAVAILABLE,
          "Publication session does not support owned infrastructure provisioning");
    }

    /** Plans against the locked physical namespace and its current structure. */
    InitialSchemaPlan plan(
        String comment, PhysicalSchemaDefinition current, PhysicalSchemaDefinition desired);

    /** Reads structure on the same executing session. */
    PhysicalTableSnapshot inspect(String expectedDialectId);

    /** Executes one persisted statement after checking that the namespace lock is still held. */
    void execute(String expectedDialectId, PlannedSchemaStep step);

    /** Rechecks reference data before activating the publication. */
    default void verifyReferences(PhysicalSchemaDefinition schema, PhysicalTableSnapshot actual) {
      if (!schema.references().isEmpty()) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.SCHEMA_VERIFICATION_FAILED,
            "Publication session does not support final reference data verification");
      }
    }
  }

  private <T> T invoke(SqlOperation<T> operation) {
    try {
      return operation.execute();
    } catch (SQLException failure) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_PUBLICATION_FAILED,
          "Target database schema operation failed",
          failure);
    }
  }

  private void executeStatement(Connection connection, PlannedSchemaStep step) throws SQLException {
    if (!snapshotCodec
        .statementChecksum(step.operation().name(), step.statementSql())
        .equals(step.statementChecksum())) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED,
          "Migration statement checksum does not match its persisted plan");
    }
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
      statement.execute(step.statementSql());
    }
  }

  /** Inspects and compiles an initial publication plan on one managed connection. */
  public InitialSchemaPlan planInitial(
      final PhysicalSchemaTarget target,
      final String tableComment,
      final PhysicalSchemaDefinition schema) {
    return withContext(
        target,
        context ->
            context
                .runtimeDialect()
                .planInitial(
                    context.connection(),
                    context.coreDialect(),
                    context.supportContext(),
                    context.database(),
                    target,
                    tableComment,
                    schema));
  }

  /** Plans initial publication or an evolution based on the active logical schema. */
  public InitialSchemaPlan plan(
      final PhysicalSchemaTarget target,
      final String tableComment,
      final PhysicalSchemaDefinition currentSchema,
      final PhysicalSchemaDefinition targetSchema) {
    if (currentSchema == null) {
      return planInitial(target, tableComment, targetSchema);
    }
    return withContext(
        target,
        context ->
            context
                .runtimeDialect()
                .planEvolution(
                    context.connection(),
                    context.coreDialect(),
                    context.supportContext(),
                    context.database(),
                    target,
                    tableComment,
                    currentSchema,
                    targetSchema));
  }

  /** Reads the normalized target structure with the expected executable dialect. */
  public PhysicalTableSnapshot inspect(
      final PhysicalSchemaTarget target, final String expectedDialectId) {
    return withContext(
        target,
        context -> {
          requireDialect(expectedDialectId, context.runtimeDialect());
          return context
              .runtimeDialect()
              .inspect(
                  context.connection(), context.coreDialect(), context.supportContext(), target);
        });
  }

  /** Executes exactly one checksummed statement generated by the selected dialect. */
  public void execute(
      final PhysicalSchemaTarget target,
      final String expectedDialectId,
      final PlannedSchemaStep step) {
    String checksum = snapshotCodec.statementChecksum(step.operation().name(), step.statementSql());
    if (!checksum.equals(step.statementChecksum())) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_PUBLICATION_FAILED,
          "Migration statement checksum does not match its persisted plan");
    }
    withContext(
        target,
        context -> {
          requireDialect(expectedDialectId, context.runtimeDialect());
          boolean previousAutoCommit = context.connection().getAutoCommit();
          try {
            context.connection().setAutoCommit(true);
            try (Statement statement = context.connection().createStatement()) {
              statement.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
              try {
                statement.execute(step.statementSql());
              } catch (SQLException exception) {
                throw new DnaRuntimeException(
                    DnaRuntimeErrorCode.SCHEMA_PUBLICATION_FAILED,
                    "Target database rejected a generated schema migration statement",
                    exception);
              }
            }
          } finally {
            context.connection().setAutoCommit(previousAutoCommit);
          }
          return null;
        });
  }

  /** Determines whether a retry can skip a step based on the live structure. */
  public boolean isSatisfied(
      final PlannedSchemaStep step,
      final PhysicalTableSnapshot expected,
      final PhysicalTableSnapshot actual) {
    if (!actual.exists()) {
      return false;
    }
    return switch (step.operation()) {
      case CREATE_TABLE ->
          baseColumns(actual).equals(baseColumns(expected))
              && primary(actual).equals(primary(expected));
      case ADD_COLUMN, ALTER_COLUMN ->
          expected.columns().stream()
              .filter(column -> column.name().equals(step.satisfactionKey()))
              .map(JdbcPhysicalSchemaBackend::withoutComment)
              .filter(
                  column ->
                      actual.columns().stream()
                          .map(JdbcPhysicalSchemaBackend::withoutComment)
                          .anyMatch(column::equals))
              .findFirst()
              .isPresent();
      case RENAME_COLUMN ->
          actual.columns().stream()
              .anyMatch(column -> column.name().equals(step.satisfactionKey()));
      case DROP_COLUMN ->
          actual.columns().stream()
              .noneMatch(column -> column.name().equals(step.satisfactionKey()));
      case CREATE_INDEX ->
          expected.indexes().stream()
              .filter(index -> index.name().equals(step.satisfactionKey()))
              .findFirst()
              .filter(actual.indexes()::contains)
              .isPresent();
      case DROP_INDEX ->
          actual.indexes().stream().noneMatch(index -> index.name().equals(step.satisfactionKey()));
      case ADD_FOREIGN_KEY ->
          expected.foreignKeys().stream()
              .filter(key -> key.name().equals(step.satisfactionKey()))
              .findFirst()
              .filter(actual.foreignKeys()::contains)
              .isPresent();
      case DROP_FOREIGN_KEY ->
          actual.foreignKeys().stream().noneMatch(key -> key.name().equals(step.satisfactionKey()));
      case COMMENT_TABLE -> Objects.equals(actual.tableComment(), expected.tableComment());
      case COMMENT_COLUMN ->
          expected.columns().stream()
              .filter(column -> column.name().equals(step.satisfactionKey()))
              .findFirst()
              .filter(actual.columns()::contains)
              .isPresent();
    };
  }

  private static PhysicalColumnSnapshot withoutComment(final PhysicalColumnSnapshot column) {
    return new PhysicalColumnSnapshot(
        column.name(),
        column.storageType(),
        column.length(),
        column.precision(),
        column.scale(),
        column.nullable(),
        column.defaultValue(),
        null);
  }

  private <T> T withContext(final PhysicalSchemaTarget target, final SqlCallback<T> callback) {
    try (ManagedRuntimeDataSource handle = dataSourceProvider.open(target);
        Connection connection = handle.dataSource().getConnection()) {
      return callback.execute(new ExecutionContext(connection, handle));
    } catch (DnaRuntimeException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.DATA_SOURCE_UNAVAILABLE,
          "Managed datasource schema operation failed",
          exception);
    }
  }

  private static void requireDialect(
      final String expectedDialectId, final RuntimeSchemaDialect actual) {
    if (expectedDialectId == null || !expectedDialectId.equals(actual.dialectId())) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED,
          "The target database dialect changed during publication");
    }
  }

  private static java.util.List<PhysicalColumnSnapshot> baseColumns(
      final PhysicalTableSnapshot snapshot) {
    return snapshot.columns().stream()
        .map(
            column ->
                new PhysicalColumnSnapshot(
                    column.name(),
                    column.storageType(),
                    column.length(),
                    column.precision(),
                    column.scale(),
                    column.nullable(),
                    column.defaultValue(),
                    null))
        .toList();
  }

  private static java.util.List<PhysicalIndexSnapshot> primary(
      final PhysicalTableSnapshot snapshot) {
    return snapshot.indexes().stream().filter(PhysicalIndexSnapshot::primary).toList();
  }

  @FunctionalInterface
  private interface SqlOperation<T> {
    T execute() throws SQLException;
  }

  @FunctionalInterface
  private interface SqlCallback<T> {
    T execute(ExecutionContext context) throws SQLException;
  }

  private record ExecutionContext(Connection connection, ManagedRuntimeDataSource handle) {

    private JdbcDatabaseDialect.SupportContext supportContext() {
      return handle.supportContext();
    }

    private JdbcDatabaseDialect coreDialect() {
      return handle.coreDialect();
    }

    private RuntimeSchemaDialect runtimeDialect() {
      return handle.runtimeDialect();
    }

    private DatabaseIdentity database() {
      return handle.database();
    }
  }
}
