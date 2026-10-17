package org.simplepoint.plugin.dna.runtime.service.record;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.apache.calcite.adapter.enumerable.EnumerableRules;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.adapter.jdbc.JdbcTable;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.schema.SchemaPlus;
import org.simplepoint.data.calcite.core.query.CalciteQueryEngine;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.data.calcite.core.query.CalciteSchemaConfigurer;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.CalciteUpdateResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyCancellationView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationView;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalSchema;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.stereotype.Component;

/** Executes all Record SQL through Calcite and owns physical transaction boundaries. */
@Component
public class RuntimeCalciteRecordBackend {

  private static final String CATALOG = "runtime";

  private final ManagedRuntimeDataSourceProvider dataSourceProvider;
  private final CalciteQueryEngine queryEngine;
  private final RecordOperationJournal journal = new RecordOperationJournal();
  private final RecordSnapshotStore snapshots = new RecordSnapshotStore();
  private RecordResourceFence resourceFence;

  /** Installs the shared runtime resource fence without coupling isolated SQL tests to JPA. */
  @org.springframework.beans.factory.annotation.Autowired
  public void setResourceFence(RecordResourceFence resourceFence) {
    this.resourceFence = resourceFence;
  }

  /** Creates the Calcite backend over platform-managed datasources. */
  public RuntimeCalciteRecordBackend(
      final ManagedRuntimeDataSourceProvider dataSourceProvider,
      final CalciteQueryEngine queryEngine) {
    this.dataSourceProvider = dataSourceProvider;
    this.queryEngine = queryEngine;
  }

  /** Executes one bounded read against a transient model-only Calcite catalog. */
  CalciteQueryResult query(
      final RuntimeRecordModel model, final RecordSelectPlan plan, final int timeoutMs) {
    try (ManagedRuntimeDataSource handle = dataSourceProvider.open(model.target())) {
      Supplier<CalciteQueryResult> execution =
          () ->
              queryEngine.execute(
                  new CalciteQueryRequest(
                      plan.sql(),
                      null,
                      plan.maxRows(),
                      timeoutMs,
                      RecordSqlParameters.bind(plan.parameters(), handle.runtimeDialect())),
                  catalog(
                      new QueryConnectionDataSource(handle.dataSource(), handle.runtimeDialect()),
                      handle,
                      model));
      return plan.physicalValueProjection() ? withPhysicalProjection(execution) : execution.get();
    } catch (DnaRuntimeException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw executionFailed("Calcite record query failed", exception);
    }
  }

  private static CalciteQueryResult withPhysicalProjection(
      final Supplier<CalciteQueryResult> query) {
    try (Hook.Closeable ignored =
        Hook.PLANNER.addThread(
            (RelOptPlanner planner) -> {
              // Local casts can lose temporal precision or reinterpret native JSON before wire
              // conversion.
              // Unsupported target projection must fail planning instead of returning a changed
              // value.
              planner.removeRule(EnumerableRules.ENUMERABLE_PROJECT_RULE);
              planner.removeRule(EnumerableRules.ENUMERABLE_CALC_RULE);
            })) {
      return query.get();
    }
  }

  /**
   * Requires physical aggregation for owner-management scans. A local aggregate could otherwise
   * transfer every matching record even when the result limit is one. The planner restriction is
   * thread-scoped, applies to explain and execution, and is removed on success or failure.
   * Unsupported database aggregates fail planning instead of falling back to a service-side scan.
   */
  CalciteQueryResult aggregateQuery(
      final RuntimeRecordModel model, final RecordSelectPlan plan, final int timeoutMs) {
    return withPhysicalAggregation(() -> query(model, plan, timeoutMs));
  }

  private CalciteQueryResult withPhysicalAggregation(final Supplier<CalciteQueryResult> query) {
    try (Hook.Closeable ignored =
        Hook.PLANNER.addThread(
            (RelOptPlanner planner) -> {
              planner.removeRule(EnumerableRules.ENUMERABLE_AGGREGATE_RULE);
              planner.removeRule(EnumerableRules.ENUMERABLE_SORTED_AGGREGATE_RULE);
              // Constant-false predicates can become an empty Values relation. Fold its aggregates
              // without enabling any local aggregation over a table or transferring business rows.
              planner.addRule(CoreRules.AGGREGATE_VALUES);
            })) {
      return query.get();
    }
  }

  /**
   * Owns the target transaction; inspection cannot open a second connection or commit
   * independently.
   */
  RecordWritePolicyPublicationView publishWritePolicy(
      final RuntimeRecordModel model,
      final RuntimeActor actor,
      final RecordWritePolicyPublicationCommand command,
      final String fingerprint,
      final Map<String, RecordWritePolicyInspection> checks,
      final Map<String, RecordSelectPlan> valueChecks,
      final RecordExecutionDeadline deadline) {
    try (ManagedRuntimeDataSource handle = dataSourceProvider.open(model.target())) {
      return RecordTargetTransaction.execute(
          handle,
          physical -> {
            var fence =
                new RecordWriteFence(
                    handle
                        .runtimeDialect()
                        .recordWriteFenceSql(
                            handle.coreDialect(), handle.supportContext(), model.target()));
            var snapshot =
                fence.publish(
                    physical,
                    new RecordWriteFenceKey(actor.tenantId(), model.physicalModelId()),
                    command.expectedVersion(),
                    command.targetVersion(),
                    fingerprint,
                    actor.actorId(),
                    deadline,
                    borrowed -> {
                      var catalog =
                          catalog(new TransactionConnectionDataSource(borrowed), handle, model);
                      Map<String, RecordCompletenessView> violations = new LinkedHashMap<>();
                      Map<String, Long> valueViolations = new LinkedHashMap<>();
                      for (var entry : checks.entrySet()) {
                        RecordWritePolicyInspection check = entry.getValue();
                        var result =
                            withPhysicalAggregation(
                                () ->
                                    queryEngine.execute(
                                        new CalciteQueryRequest(
                                            check.plan().sql(),
                                            null,
                                            check.plan().maxRows(),
                                            deadline.remainingMillis(),
                                            RecordSqlParameters.bind(
                                                check.plan().parameters(),
                                                handle.runtimeDialect())),
                                        catalog));
                        var counts = RecordCompletenessResult.read(check.query(), result);
                        if (counts.emptyCounts().values().stream().anyMatch(count -> count > 0)) {
                          violations.put(entry.getKey(), counts);
                        }
                      }
                      for (var entry : valueChecks.entrySet()) {
                        RecordSelectPlan plan = entry.getValue();
                        var result =
                            withPhysicalAggregation(
                                () ->
                                    queryEngine.execute(
                                        new CalciteQueryRequest(
                                            plan.sql(),
                                            null,
                                            plan.maxRows(),
                                            deadline.remainingMillis(),
                                            RecordSqlParameters.bind(
                                                plan.parameters(), handle.runtimeDialect())),
                                        catalog));
                        long count = RecordViolationResult.read(result);
                        if (count > 0) {
                          valueViolations.put(entry.getKey(), count);
                        }
                      }
                      deadline.remainingMillis();
                      if (!violations.isEmpty() || !valueViolations.isEmpty()) {
                        throw new InspectionRejected(violations, valueViolations);
                      }
                    });
            deadline.remainingMillis();
            return new RecordWritePolicyPublicationView(
                true, snapshot.revision(), snapshot.fingerprint(), Map.of());
          });
    } catch (InspectionRejected rejected) {
      return new RecordWritePolicyPublicationView(
          false,
          command.expectedVersion(),
          fingerprint,
          rejected.violations,
          rejected.valueViolations);
    } catch (DnaRuntimeException failure) {
      throw failure;
    } catch (SQLException | RuntimeException failure) {
      throw executionFailed(
          "Record write-policy publication failed; retry the same publication intent", failure);
    }
  }

  /** Resolves cancellation using target metadata only; it cannot scan or alter business records. */
  RecordWritePolicyCancellationView cancelWritePolicy(
      final RuntimeRecordModel model,
      final RuntimeActor actor,
      final RecordWritePolicyPublicationCommand command,
      final String fingerprint,
      final String cancellationFingerprint,
      final RecordExecutionDeadline deadline) {
    try (ManagedRuntimeDataSource handle = dataSourceProvider.open(model.target())) {
      return RecordTargetTransaction.execute(
          handle,
          physical -> {
            var fence =
                new RecordWriteFence(
                    handle
                        .runtimeDialect()
                        .recordWriteFenceSql(
                            handle.coreDialect(), handle.supportContext(), model.target()));
            var resolution =
                fence.cancel(
                    physical,
                    new RecordWriteFenceKey(actor.tenantId(), model.physicalModelId()),
                    command.expectedVersion(),
                    command.targetVersion(),
                    fingerprint,
                    cancellationFingerprint,
                    actor.actorId(),
                    deadline);
            deadline.remainingMillis();
            return new RecordWritePolicyCancellationView(
                resolution.cancelled(),
                resolution.snapshot().revision(),
                fingerprint,
                resolution.snapshot().fingerprint());
          });
    } catch (DnaRuntimeException failure) {
      throw failure;
    } catch (SQLException | RuntimeException failure) {
      throw executionFailed(
          "Record policy cancellation outcome is unknown; retry the same intent", failure);
    }
  }

  private static final class InspectionRejected extends RuntimeException {
    private final Map<String, RecordCompletenessView> violations;
    private final Map<String, Long> valueViolations;

    private InspectionRejected(
        final Map<String, RecordCompletenessView> violations,
        final Map<String, Long> valueViolations) {
      this.violations = Map.copyOf(violations);
      this.valueViolations = Map.copyOf(valueViolations);
    }
  }

  /**
   * Initializes only during authorized model preparation; ordinary writes never create missing
   * infrastructure.
   */
  void initializeWriteFence(final RuntimeActor actor, final PhysicalSchemaTarget target) {
    try (ManagedRuntimeDataSource handle = dataSourceProvider.open(target)) {
      RecordTargetTransaction.execute(
          handle,
          physical ->
              new RecordWriteFence(
                      handle
                          .runtimeDialect()
                          .recordWriteFenceSql(
                              handle.coreDialect(), handle.supportContext(), target))
                  .initialize(
                      physical,
                      new RecordWriteFenceKey(actor.tenantId(), target.physicalModelId()),
                      actor.actorId(),
                      30_000));
    } catch (DnaRuntimeException failure) {
      throw failure;
    } catch (SQLException failure) {
      throw executionFailed("Record write-fence initialization failed", failure);
    }
  }

  /** Reads an exact committed receipt without modifying the target. */
  List<RecordCommandResult> findResult(
      final RuntimeRecordModel model,
      final RecordOperationIdentity operation,
      final int timeoutMs) {
    return journal.read(operation, query(model, journal.lookup(operation), timeoutMs));
  }

  /** Executes every statement on one physical connection and commits only after all match. */
  RecordBatchExecutionResult execute(
      final RuntimeRecordModel model,
      final RecordWriteAdmission admission,
      final List<RecordSqlStatement> statements,
      final int timeoutMs) {
    return execute(model, admission, statements, timeoutMs, null);
  }

  /**
   * A keyed operation commits its immutable result in the same target transaction as all mutations.
   */
  RecordBatchExecutionResult execute(
      final RuntimeRecordModel model,
      final RecordWriteAdmission admission,
      final List<RecordSqlStatement> statements,
      final int timeoutMs,
      final RecordOperationIdentity operation) {
    return executeOwned(model, List.of(), admission, statements, timeoutMs, operation);
  }

  org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView snapshot(
      RuntimeRecordModel model, RecordOperationIdentity operation, int timeoutMs) {
    return snapshots.read(operation, query(model, snapshots.lookup(operation), timeoutMs));
  }

  /**
   * Aggregate SQL uses one datasource, one transaction and the existing durable receipt journal.
   */
  RecordBatchExecutionResult executeOwned(
      final RuntimeRecordModel model,
      final List<RuntimeRecordModel> children,
      final RecordWriteAdmission admission,
      final List<RecordSqlStatement> statements,
      final int timeoutMs,
      final RecordOperationIdentity operation) {
    return executeOwned(model, children, admission, statements, timeoutMs, operation, null);
  }

  RecordBatchExecutionResult executeOwned(
      final RuntimeRecordModel model,
      final List<RuntimeRecordModel> children,
      final RecordWriteAdmission admission,
      final List<RecordSqlStatement> statements,
      final int timeoutMs,
      final RecordOperationIdentity operation,
      final RecordSnapshotPlan snapshot) {
    if (resourceFence == null) {
      return executeOwnedFenced(model, children, admission, statements, timeoutMs, operation, snapshot);
    }
    return resourceFence.execute(admission.tenantId(), model.physicalModelId(), admission.resourceGeneration(),
        () -> executeOwnedFenced(model, children, admission, statements, timeoutMs, operation, snapshot));
  }

  private RecordBatchExecutionResult executeOwnedFenced(
      final RuntimeRecordModel model, final List<RuntimeRecordModel> children,
      final RecordWriteAdmission admission, final List<RecordSqlStatement> statements,
      final int timeoutMs, final RecordOperationIdentity operation, final RecordSnapshotPlan snapshot) {
    long startedAt = System.nanoTime();
    // Owned rows share the parent acknowledgement; the journal stays bounded independently of child
    // count.
    List<RecordSqlStatement> receipts =
        children.isEmpty() ? statements : List.of(statements.getFirst());
    Objects.requireNonNull(admission, "admission");
    try (ManagedRuntimeDataSource handle = dataSourceProvider.open(model.target())) {
      return RecordTargetTransaction.execute(
          handle,
          physical -> {
            DataSource transactionDataSource = new TransactionConnectionDataSource(physical);
            CalciteSchemaConfigurer catalog =
                catalog(transactionDataSource, handle, model, children);
            if (operation != null) {
              RecordSelectPlan lookup = journal.lookup(operation);
              List<RecordCommandResult> existing =
                  journal.read(
                      operation,
                      queryEngine.execute(
                          new CalciteQueryRequest(
                              lookup.sql(),
                              null,
                              1,
                              timeoutMs,
                              RecordSqlParameters.bind(
                                  lookup.parameters(), handle.runtimeDialect())),
                          catalog));
              if (existing != null) {
                return replay(existing, receipts, elapsed(startedAt));
              }
            }
            new RecordWriteFence(
                    handle
                        .runtimeDialect()
                        .recordWriteFenceSql(
                            handle.coreDialect(), handle.supportContext(), model.target()))
                .admitWrite(
                    physical,
                    new RecordWriteFenceKey(admission.tenantId(), model.physicalModelId()),
                    admission.version(),
                    timeoutMs);
            if (operation != null) {
              // Insert first: the unique operation identity serializes concurrent replays.
              // If any business mutation fails, this insert rolls back with it.
              RecordSqlStatement receipt = journal.insert(operation, receipts);
              CalciteUpdateResult inserted =
                  queryEngine.executeUpdate(
                      new CalciteUpdateRequest(
                          receipt.sql(),
                          null,
                          timeoutMs,
                          RecordSqlParameters.bind(receipt.parameters(), handle.runtimeDialect())),
                      catalog);
              if (inserted.affectedRows() != 1) {
                throw executionFailed("Record receipt insert did not affect exactly one row", null);
              }
            }
            List<Integer> affected = new ArrayList<>(statements.size());
            int pushedStatements = 0;
            for (int index = 0; index < statements.size(); index++) {
              RecordSqlStatement statement = statements.get(index);
              CalciteUpdateResult result =
                  queryEngine.executeUpdate(
                      new CalciteUpdateRequest(
                          statement.sql(),
                          null,
                          timeoutMs,
                          RecordSqlParameters.bind(
                              statement.parameters(), handle.runtimeDialect())),
                      catalog);
              if (result.affectedRows() != 1) {
                throw new RecordMutationMissException(index);
              }
              affected.add(result.affectedRows());
              pushedStatements += result.pushedSqls().size();
            }
            if (snapshot != null) {
              var captured =
                  snapshot.capture(
                      plan -> {
                        Supplier<CalciteQueryResult> read =
                            () ->
                                queryEngine.execute(
                                    new CalciteQueryRequest(
                                        plan.sql(),
                                        null,
                                        plan.maxRows(),
                                        timeoutMs,
                                        RecordSqlParameters.bind(
                                            plan.parameters(), handle.runtimeDialect())),
                                    catalog);
                        return plan.physicalValueProjection()
                            ? withPhysicalProjection(read)
                            : read.get();
                      });
              var statement =
                  snapshots.insert(operation, captured, statements.getFirst().updatedAt());
              var stored =
                  queryEngine.executeUpdate(
                      new CalciteUpdateRequest(
                          statement.sql(),
                          null,
                          timeoutMs,
                          RecordSqlParameters.bind(
                              statement.parameters(), handle.runtimeDialect())),
                      catalog);
              if (stored.affectedRows() != 1) {
                throw executionFailed("Snapshot insert did not affect one row", null);
              }
            }
            return new RecordBatchExecutionResult(affected, elapsed(startedAt), pushedStatements);
          });
    } catch (RuntimeException | SQLException exception) {
      if (operation != null) {
        try {
          // Read on a new connection after rollback/close. Covers a concurrent winner
          // as well as a commit that succeeded before its acknowledgement was lost.
          List<RecordCommandResult> committed =
              journal.read(operation, query(model, journal.lookup(operation), timeoutMs));
          if (committed != null) {
            return replay(committed, receipts, elapsed(startedAt));
          }
        } catch (DnaRuntimeException recoveryFailure) {
          if (recoveryFailure.errorCode() == DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT) {
            throw recoveryFailure;
          }
          exception.addSuppressed(recoveryFailure);
        }
      }
      if (exception instanceof RecordMutationMissException miss) {
        throw miss;
      }
      if (exception instanceof DnaRuntimeException failure) {
        throw failure;
      }
      throw executionFailed("Calcite record transaction failed", exception);
    }
  }

  private static RecordBatchExecutionResult replay(
      List<RecordCommandResult> results, List<RecordSqlStatement> statements, long elapsed) {
    if (results.size() != statements.size()) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Record operation receipt size is invalid");
    }
    return new RecordBatchExecutionResult(
        results.stream().map(RecordCommandResult::affectedRows).toList(), elapsed, 0, results);
  }

  private static CalciteSchemaConfigurer catalog(
      final DataSource dataSource,
      final ManagedRuntimeDataSource handle,
      final RuntimeRecordModel model) {
    return catalog(dataSource, handle, model, List.of());
  }

  private static CalciteSchemaConfigurer catalog(
      final DataSource dataSource,
      final ManagedRuntimeDataSource handle,
      final RuntimeRecordModel model,
      final List<RuntimeRecordModel> children) {
    var tables = new java.util.HashSet<String>();
    tables.add(model.target().metadataTarget().tableName().toLowerCase(java.util.Locale.ROOT));
    tables.add(RecordJournalSchema.TABLE.toLowerCase(java.util.Locale.ROOT));
    tables.add(
        org.simplepoint.plugin.dna.runtime.service.schema.RecordSnapshotSchema.TABLE.toLowerCase(
            java.util.Locale.ROOT));
    children.forEach(
        child ->
            tables.add(
                child.target().metadataTarget().tableName().toLowerCase(java.util.Locale.ROOT)));
    return new CalciteSchemaConfigurer() {
      @Override
      public void configure(final SchemaPlus root) {
        var jdbc =
            JdbcSchema.create(
                root,
                CATALOG,
                dataSource,
                handle.runtimeDialect().recordSqlDialectFactory(),
                handle.metadataCatalog(),
                handle.metadataSchema());
        var catalog = root.add(CATALOG, jdbc);
        for (String name : jdbc.getTableNames()) {
          if (tables.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            if (jdbc.getTable(name) instanceof JdbcTable table) {
              catalog.add(name, new RuntimeRecordJdbcTable(table));
            }
          }
        }
      }

      @Override
      public Class<? extends RelDataTypeSystem> typeSystem() {
        return RuntimeRecordTypeSystem.class;
      }

      @Override
      public ZoneId timeZone() {
        return ZoneId.of("UTC");
      }
    };
  }

  private static long elapsed(final long startedAt) {
    return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
  }

  private static DnaRuntimeException executionFailed(final String message, final Throwable cause) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED, message, cause);
  }

  /** Applies and restores dialect session state around each Calcite query connection. */
  private static final class QueryConnectionDataSource implements DataSource {

    private final DataSource delegate;
    private final RuntimeSchemaDialect dialect;

    private QueryConnectionDataSource(
        final DataSource delegate, final RuntimeSchemaDialect dialect) {
      this.delegate = delegate;
      this.dialect = dialect;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return configure(delegate.getConnection());
    }

    @Override
    public Connection getConnection(final String username, final String password)
        throws SQLException {
      return configure(delegate.getConnection(username, password));
    }

    private Connection configure(final Connection physical) throws SQLException {
      try {
        dialect.prepareRecordQueryConnection(physical);
        return RuntimeJdbcConnection.query(physical, dialect);
      } catch (SQLException | RuntimeException exception) {
        try {
          physical.close();
        } catch (SQLException closeFailure) {
          exception.addSuppressed(closeFailure);
        }
        throw exception;
      }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(final PrintWriter out) throws SQLException {
      delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(final int seconds) throws SQLException {
      delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(final Class<T> iface) throws SQLException {
      return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface) throws SQLException {
      return delegate.isWrapperFor(iface);
    }
  }
}
