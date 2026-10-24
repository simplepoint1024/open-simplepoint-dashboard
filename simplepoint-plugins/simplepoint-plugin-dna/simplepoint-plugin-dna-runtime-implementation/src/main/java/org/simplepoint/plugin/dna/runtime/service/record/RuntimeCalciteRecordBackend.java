package org.simplepoint.plugin.dna.runtime.service.record;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.apache.calcite.adapter.enumerable.EnumerableRules;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.runtime.Hook;
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

  private final ManagedRuntimeDataSourceProvider dataSourceProvider;
  private final CalciteQueryEngine queryEngine;
  private final RecordOperationJournal journal = new RecordOperationJournal();
  private final RecordSnapshotStore snapshots = new RecordSnapshotStore();
  private final RecordCalculationStore calculations = new RecordCalculationStore();
  private final RecordMaintenanceAdmission maintenance = new RecordMaintenanceAdmission();
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

  /** Tenant-scoped internal read; the root lock and maintenance check precede every value read. */
  CalciteQueryResult query(final RuntimeRecordModel model, final String tenantId,
      final RecordSelectPlan plan, final int timeoutMs) {
    return maintenanceRead(model, tenantId, timeoutMs, reader -> reader.apply(plan));
  }

  /** Management callers hold the owning control model lock through their eventual commit. */
  void requireMaintenanceAvailable(final RuntimeRecordModel model, final String tenantId) {
    maintenanceRead(model, tenantId, 30_000, reader -> null);
  }

  private <T> T maintenanceRead(final RuntimeRecordModel model, final String tenantId, final int timeoutMs,
      final java.util.function.Function<java.util.function.Function<RecordSelectPlan, CalciteQueryResult>, T> work) {
    var deadline = new RecordExecutionDeadline(timeoutMs);
    try (ManagedRuntimeDataSource handle = dataSourceProvider.open(model.target())) {
      return RecordTargetTransaction.execute(handle, physical -> {
        var key = RecordMaintenanceAdmission.key(model, tenantId);
        var fence = new RecordWriteFence(handle.runtimeDialect().recordWriteFenceSql(
            handle.coreDialect(), handle.supportContext(), model.target()));
        var admitted = fence.admitRead(physical, key, deadline.remainingMillis());
        var catalog = catalog(new TransactionConnectionDataSource(physical), handle, model);
        java.util.function.Function<RecordSelectPlan, CalciteQueryResult> reader =
            plan -> readInTransaction(plan, catalog, handle, deadline);
        maintenance.requireAvailable(key, admitted.revision(), reader);
        T result = work.apply(reader);
        deadline.remainingMillis();
        return result;
      });
    } catch (DnaRuntimeException failure) {
      throw failure;
    } catch (SQLException | RuntimeException failure) {
      throw executionFailed("Calcite maintenance-admitted read failed", failure);
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
  CalciteQueryResult aggregateQuery(final RuntimeRecordModel model, final String tenantId,
      final RecordSelectPlan plan, final int timeoutMs) {
    return withPhysicalAggregation(() -> query(model, tenantId, plan, timeoutMs));
  }

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

  /** One worker batch, after control model locks; no ordinary maintenance bypass is exported. */
  <T> T publication(final RuntimeRecordModel model, final List<RuntimeRecordModel> children,
      final RecordWriteFenceKey key, final int timeoutMs,
      final java.util.function.Function<RecordPublicationSession, T> work) {
    if (!model.physicalModelId().equals(key.modelId())) {
      throw new IllegalArgumentException("Publication lock does not belong to the resolved root model");
    }
    var deadline = new RecordExecutionDeadline(timeoutMs);
    try (ManagedRuntimeDataSource handle = dataSourceProvider.open(model.target())) {
      return RecordTargetTransaction.execute(handle, physical -> {
        var fence = new RecordWriteFence(handle.runtimeDialect().recordWriteFenceSql(
            handle.coreDialect(), handle.supportContext(), model.target()));
        var admitted = fence.lockPublication(physical, key, deadline);
        var catalog = catalog(new TransactionConnectionDataSource(physical), handle, model, children);
        var session = new RecordPublicationSession(physical, fence, admitted, deadline,
            plan -> readInTransaction(plan, catalog, handle, deadline),
            plan -> withPhysicalAggregation(() -> readInTransaction(plan, catalog, handle, deadline)),
            statement -> updateInTransaction(statement, catalog, handle, deadline).affectedRows());
        T result = work.apply(session);
        deadline.remainingMillis();
        return result;
      });
    } catch (DnaRuntimeException failure) {
      throw failure;
    } catch (SQLException | RuntimeException failure) {
      throw executionFailed("Calcite publication batch failed; resolve its durable checkpoint before retrying", failure);
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
            var key = RecordMaintenanceAdmission.key(model, actor.tenantId());
            var current = fence.lockPublication(physical, key, deadline);
            var admissionCatalog = catalog(new TransactionConnectionDataSource(physical), handle, model);
            maintenance.requireAvailable(key, current.revision(), plan -> readInTransaction(plan, admissionCatalog, handle, deadline));
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
    return resolveCancellation(model, actor, command, fingerprint, cancellationFingerprint, deadline, false).orElseThrow();
  }

  /** An unstarted calculation has no staged/applied data; consume its cancellation under the target fence. */
  java.util.Optional<RecordWritePolicyCancellationView> cancelUnstartedCalculationPolicy(
      final RuntimeRecordModel model, final RuntimeActor actor, final RecordWritePolicyPublicationCommand command,
      final String fingerprint, final String cancellationFingerprint, final RecordExecutionDeadline deadline) {
    return resolveCancellation(model, actor, command, fingerprint, cancellationFingerprint, deadline, true);
  }

  private java.util.Optional<RecordWritePolicyCancellationView> resolveCancellation(
      final RuntimeRecordModel model, final RuntimeActor actor, final RecordWritePolicyPublicationCommand command,
      final String fingerprint, final String cancellationFingerprint, final RecordExecutionDeadline deadline,
      final boolean onlyUnstartedCalculation) {
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
            var key = RecordMaintenanceAdmission.key(model, actor.tenantId());
            var current = fence.lockPublication(physical, key, deadline);
            var admissionCatalog = catalog(new TransactionConnectionDataSource(physical), handle, model);
            if (onlyUnstartedCalculation) {
              var journal = new RecordPublicationStore();
              var existing = journal.read(key, command.targetVersion(),
                  readInTransaction(journal.lookup(key, command.targetVersion()), admissionCatalog, handle, deadline));
              if (existing != null) {
                // A durable batch always follows the original restoration protocol and its metadata checks.
                return java.util.Optional.empty();
              }
            }
            maintenance.requireAvailable(key, current.revision(), plan -> readInTransaction(plan, admissionCatalog, handle, deadline));
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
            return java.util.Optional.of(new RecordWritePolicyCancellationView(
                resolution.cancelled(),
                resolution.snapshot().revision(),
                fingerprint,
                resolution.snapshot().fingerprint()));
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
    if (!model.physicalModelId().equals(operation.modelId())) {
      throw new IllegalArgumentException("Receipt model does not match the resolved model");
    }
    return maintenanceRead(model, operation.tenantId(), timeoutMs,
        reader -> journal.read(operation, reader.apply(journal.lookup(operation))));
  }

  /** Preloads immutable metadata only; execution rereads it after policy admission in the target transaction. */
  RecordCalculationStore.Installed calculation(final RuntimeRecordModel model, final String tenantId,
      final long policyVersion, final int timeoutMs) {
    var key = new RecordWriteFenceKey(tenantId, model.physicalModelId());
    return calculations.read(key, policyVersion, query(model, tenantId, calculations.lookup(key, policyVersion), timeoutMs));
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

  /** Keeps resource state and the installed policy fixed through authorization, SQL and projection. */
  <T> T read(final RuntimeRecordModel model, final List<RuntimeRecordModel> children,
      final RecordAccessContext access, final int timeoutMs,
      final java.util.function.Function<RecordReadSession, T> work) {
    Supplier<T> operation = () -> readFenced(model, children, access, timeoutMs, work);
    return resourceFence == null ? operation.get()
        : resourceFence.execute(access.actor().tenantId(), model.physicalModelId(), access.resourceGeneration(), operation);
  }

  private <T> T readFenced(final RuntimeRecordModel model, final List<RuntimeRecordModel> children,
      final RecordAccessContext access, final int timeoutMs,
      final java.util.function.Function<RecordReadSession, T> work) {
    var deadline = new RecordExecutionDeadline(timeoutMs);
    try (ManagedRuntimeDataSource handle = dataSourceProvider.open(model.target())) {
      return RecordTargetTransaction.execute(handle, physical -> {
        var key = new RecordWriteFenceKey(access.actor().tenantId(), model.physicalModelId());
        var fence = new RecordWriteFence(handle.runtimeDialect().recordWriteFenceSql(
            handle.coreDialect(), handle.supportContext(), model.target()));
        var admitted = fence.admitRead(physical, key, deadline.remainingMillis());
        var catalog = catalog(new TransactionConnectionDataSource(physical), handle, model, children);
        java.util.function.Function<RecordSelectPlan, CalciteQueryResult> reader =
            plan -> readInTransaction(plan, catalog, handle, deadline);
        maintenance.requireAvailable(key, admitted.revision(), reader);
        var installed = calculations.read(key, admitted.revision(), reader.apply(calculations.lookup(key, admitted.revision())));
        T result = work.apply(new RecordReadSession(key, admitted.revision(), installed, reader));
        deadline.remainingMillis();
        return result;
      });
    } catch (DnaRuntimeException failure) {
      throw failure;
    } catch (org.springframework.security.access.AccessDeniedException failure) {
      throw failure;
    } catch (SQLException | RuntimeException failure) {
      throw executionFailed("Calcite authorized record read failed", failure);
    }
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
    return executeWork(model, children, admission, timeoutMs, operation,
        children.isEmpty() ? statements.size() : 1, (reader, at, calculation) -> {
          if (calculation != null && !calculation.policy().definition().isEmpty()) {
            throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID,
                "Calculated records require the aggregate calculation write boundary");
          }
          return new RecordWritePreparation(statements, snapshot, children.isEmpty() ? statements.size() : 1);
        });
  }

  /** Prepares aggregate reads and computed writes inside the same physical transaction. */
  RecordBatchExecutionResult executePreparedOwned(
      final RuntimeRecordModel model, final List<RuntimeRecordModel> children,
      final RecordWriteAdmission admission, final int timeoutMs, final RecordOperationIdentity operation,
      final RecordWritePreparation.Work work) {
    return executeWork(model, children, admission, timeoutMs, operation, 1, work);
  }

  private RecordBatchExecutionResult executeWork(
      final RuntimeRecordModel model, final List<RuntimeRecordModel> children,
      final RecordWriteAdmission admission, final int timeoutMs, final RecordOperationIdentity operation,
      final int receiptCount, final RecordWritePreparation.Work work) {
    return executeSequence(model, children, admission, timeoutMs, operation, receiptCount, List.of(work));
  }

  /** Each operation prepares after preceding operations have executed on the borrowed transaction. */
  RecordBatchExecutionResult executePreparedSequence(final RuntimeRecordModel model, final List<RuntimeRecordModel> children,
      final RecordWriteAdmission admission, final int timeoutMs, final RecordOperationIdentity operation,
      final List<RecordWritePreparation.Work> work) {
    if (work.isEmpty() || work.size() > 100) {
      throw new IllegalArgumentException("Record sequence requires 1 to 100 operations");
    }
    return executeSequence(model, children, admission, timeoutMs, operation, work.size(), List.copyOf(work));
  }

  private RecordBatchExecutionResult executeSequence(final RuntimeRecordModel model, final List<RuntimeRecordModel> children,
      final RecordWriteAdmission admission, final int timeoutMs, final RecordOperationIdentity operation,
      final int receiptCount, final List<RecordWritePreparation.Work> work) {
    if (resourceFence == null) {
      return executeOwnedFenced(model, children, admission, timeoutMs, operation, receiptCount, work);
    }
    return resourceFence.execute(admission.tenantId(), model.physicalModelId(), admission.resourceGeneration(),
        () -> executeOwnedFenced(model, children, admission, timeoutMs, operation, receiptCount, work));
  }

  private RecordBatchExecutionResult executeOwnedFenced(
      final RuntimeRecordModel model, final List<RuntimeRecordModel> children,
      final RecordWriteAdmission admission, final int timeoutMs, final RecordOperationIdentity operation,
      final int receiptCount, final List<RecordWritePreparation.Work> work) {
    long startedAt = System.nanoTime();
    var deadline = new RecordExecutionDeadline(timeoutMs);
    Objects.requireNonNull(admission, "admission");
    Objects.requireNonNull(work, "work");
    if (receiptCount < 1 || receiptCount > 100) {
      throw new IllegalArgumentException("Invalid record receipt count");
    }
    try (ManagedRuntimeDataSource handle = dataSourceProvider.open(model.target())) {
      return RecordTargetTransaction.execute(
          handle,
          physical -> {
            DataSource transactionDataSource = new TransactionConnectionDataSource(physical);
            CalciteSchemaConfigurer catalog =
                catalog(transactionDataSource, handle, model, children);
            var key = RecordMaintenanceAdmission.key(model, admission.tenantId());
            var fence = new RecordWriteFence(handle.runtimeDialect().recordWriteFenceSql(
                handle.coreDialect(), handle.supportContext(), model.target()));
            // Replays preserve their original result across policy versions, but never bypass maintenance.
            var admitted = fence.admitRead(physical, key, deadline.remainingMillis());
            maintenance.requireAvailable(key, admitted.revision(), plan -> readInTransaction(plan, catalog, handle, deadline));
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
                              deadline.remainingMillis(),
                              RecordSqlParameters.bind(
                                  lookup.parameters(), handle.runtimeDialect())),
                          catalog));
              if (existing != null) {
                return replay(existing, receiptCount, elapsed(startedAt));
              }
            }
            fence.admitWrite(physical, key, admission.version(), deadline.remainingMillis());
            var calculation = calculations.read(key, admission.version(),
                readInTransaction(calculations.lookup(key, admission.version()), catalog, handle, deadline));
            var at = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            List<RecordSqlStatement> receipts = new ArrayList<>(receiptCount);
            List<Integer> affected = new ArrayList<>();
            int pushedStatements = 0;
            for (var step : work) {
              var prepared = step.prepare(plan -> readInTransaction(plan, catalog, handle, deadline), at, calculation);
              var statements = prepared.statements();
              if (affected.size() + statements.size() > 401) {
                throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID,
                    "Record transaction exceeds the aggregate physical mutation budget");
              }
              receipts.addAll(statements.subList(0, prepared.receiptCount()));
              for (int index = 0; index < statements.size(); index++) {
                RecordSqlStatement statement = statements.get(index);
                var result = updateInTransaction(statement, catalog, handle, deadline);
                if (result.affectedRows() != 1) {
                  // Prepared aggregate callers diagnose child/parent races inside their domain boundary.
                  throw new RecordMutationMissException(
                      work.size() == 1 && prepared.receiptCount() == statements.size() ? index : receipts.size() - 1);
                }
                affected.add(result.affectedRows());
                pushedStatements += result.pushedSqls().size();
              }
              var snapshot = prepared.snapshot();
              if (snapshot != null) {
                if (work.size() != 1 || operation == null) {
                  throw new IllegalArgumentException("A snapshot requires one keyed aggregate operation");
                }
                var captured = snapshot.capture(plan -> readInTransaction(plan, catalog, handle, deadline));
                var storedSnapshot = RecordStoredSnapshot.capture(captured, calculation);
                if (updateInTransaction(snapshots.insert(operation, storedSnapshot, statements.getFirst().updatedAt()),
                    catalog, handle, deadline).affectedRows() != 1) {
                  throw executionFailed("Snapshot insert did not affect one row", null);
                }
              }
            }
            if (receipts.size() != receiptCount) {
              throw new IllegalArgumentException("Prepared operations do not match receipt count");
            }
            if (operation != null) {
              // A sequence's complete receipts become known after its last prepared operation.
              // The unique journal insert still commits atomically with all business effects;
              // a concurrent replay loser rolls back everything before reading the winner.
              if (updateInTransaction(journal.insert(operation, receipts), catalog, handle, deadline).affectedRows() != 1) {
                throw executionFailed("Record receipt insert did not affect exactly one row", null);
              }
            }
            deadline.remainingMillis();
            return new RecordBatchExecutionResult(affected, elapsed(startedAt), pushedStatements);
          });
    } catch (RuntimeException | SQLException exception) {
      if (operation != null) {
        try {
          // Read on a new connection after rollback/close. Covers a concurrent winner
          // as well as a commit that succeeded before its acknowledgement was lost.
          List<RecordCommandResult> committed =
              findResult(model, operation, timeoutMs);
          if (committed != null) {
            return replay(committed, receiptCount, elapsed(startedAt));
          }
        } catch (DnaRuntimeException recoveryFailure) {
          if (recoveryFailure.errorCode() == DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED
              || recoveryFailure.errorCode() == DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT
              || recoveryFailure.errorCode() == DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS) {
            throw recoveryFailure;
          }
          exception.addSuppressed(recoveryFailure);
        }
      }
      if (exception instanceof RecordMutationMissException miss) {
        throw miss;
      }
      if (operation != null && RecordConstraintFailures.integrityViolation(exception)) {
        return rejectConstraint(model, admission, timeoutMs, operation, receiptCount, startedAt);
      }
      if (exception instanceof DnaRuntimeException failure) {
        throw failure;
      }
      throw executionFailed("Calcite record transaction failed", exception);
    }
  }

  /** Drain earlier writers, then commit either their successful receipt or a permanent rejection. */
  private RecordBatchExecutionResult rejectConstraint(final RuntimeRecordModel model,
      final RecordWriteAdmission admission, final int timeoutMs, final RecordOperationIdentity operation,
      final int receiptCount, final long startedAt) {
    var deadline = new RecordExecutionDeadline(timeoutMs);
    try (ManagedRuntimeDataSource handle = dataSourceProvider.open(model.target())) {
      List<RecordCommandResult> committed = RecordTargetTransaction.execute(handle, physical -> {
        var catalog = catalog(new TransactionConnectionDataSource(physical), handle, model);
        var key = RecordMaintenanceAdmission.key(model, admission.tenantId());
        var fence = new RecordWriteFence(handle.runtimeDialect().recordWriteFenceSql(
            handle.coreDialect(), handle.supportContext(), model.target()));
        var admitted = fence.lockPublication(physical, key, deadline);
        maintenance.requireAvailable(key, admitted.revision(), plan -> readInTransaction(plan, catalog, handle, deadline));
        var existing = journal.read(operation, readInTransaction(journal.lookup(operation), catalog, handle, deadline));
        if (existing != null) {
          return existing;
        }
        // An exclusive model fence blocks every ordinary writer until this receipt commits.
        // A late retry must read it before policy validation or any business mutation.
        var rejection = journal.reject(operation, java.time.Instant.now());
        if (updateInTransaction(rejection, catalog, handle, deadline).affectedRows() != 1) {
          throw executionFailed("Record rejection receipt was not persisted", null);
        }
        return null;
      });
      if (committed != null) {
        return replay(committed, receiptCount, elapsed(startedAt));
      }
    } catch (SQLException failure) {
      // An uncertain rejection commit must remain retryable until its durable receipt is read.
      throw executionFailed("Record rejection outcome is uncertain", failure);
    }
    throw RecordOperationJournal.rejected();
  }

  private static RecordBatchExecutionResult replay(
      List<RecordCommandResult> results, int receiptCount, long elapsed) {
    if (results.size() != receiptCount) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Record operation receipt size is invalid");
    }
    return new RecordBatchExecutionResult(
        results.stream().map(RecordCommandResult::affectedRows).toList(), elapsed, 0, results);
  }

  private CalciteUpdateResult updateInTransaction(final RecordSqlStatement statement,
      final CalciteSchemaConfigurer catalog, final ManagedRuntimeDataSource handle, final RecordExecutionDeadline deadline) {
    return queryEngine.executeUpdate(new CalciteUpdateRequest(statement.sql(), null, deadline.remainingMillis(),
        RecordSqlParameters.bind(statement.parameters(), handle.runtimeDialect())), catalog);
  }

  private CalciteQueryResult readInTransaction(final RecordSelectPlan plan,
      final CalciteSchemaConfigurer catalog, final ManagedRuntimeDataSource handle,
      final RecordExecutionDeadline deadline) {
    Supplier<CalciteQueryResult> read = () -> queryEngine.execute(new CalciteQueryRequest(
        plan.sql(), null, plan.maxRows(), deadline.remainingMillis(),
        RecordSqlParameters.bind(plan.parameters(), handle.runtimeDialect())), catalog);
    return plan.physicalValueProjection() ? withPhysicalProjection(read) : read.get();
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
    tables.add(org.simplepoint.plugin.dna.runtime.service.schema.RecordCalculationSchema.TABLE.toLowerCase(java.util.Locale.ROOT));
    tables.add(org.simplepoint.plugin.dna.runtime.service.schema.RecordPublicationSchema.TABLE.toLowerCase(java.util.Locale.ROOT));
    tables.add(org.simplepoint.plugin.dna.runtime.service.schema.RecordPublicationStageSchema.TABLE.toLowerCase(java.util.Locale.ROOT));
    tables.add(org.simplepoint.plugin.dna.runtime.service.schema.RecordPublicationStageSchema.CHUNKS.toLowerCase(java.util.Locale.ROOT));
    tables.add(
        org.simplepoint.plugin.dna.runtime.service.schema.RecordSnapshotSchema.TABLE.toLowerCase(
            java.util.Locale.ROOT));
    children.forEach(
        child ->
            tables.add(
                child.target().metadataTarget().tableName().toLowerCase(java.util.Locale.ROOT)));
    return new RuntimeRecordCatalog(dataSource, handle.runtimeDialect(), handle.metadataCatalog(), handle.metadataSchema(), tables);
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
