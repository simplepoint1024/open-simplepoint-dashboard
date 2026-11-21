package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordDeleteTransaction;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.service.RecordTransactionService;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.stereotype.Service;

/** Atomic one-model mutation groups executed through Calcite on one physical connection. */
@Service
public class RecordTransactionServiceImpl implements RecordTransactionService {

  private static final String VALIDATION_RECORD_ID = "00000000-0000-5000-8000-000000000000";

  private final RuntimeRequestContext requestContext;
  private final RuntimeRecordModelResolver modelResolver;
  private final RuntimeRecordAccessPolicy accessPolicy;
  private final RecordSqlCompiler compiler;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordAuditSupport audit;
  private final RecordAdmissionController admission;
  private final RecordExecutionObserver observer;
  private final RecordRequestFingerprint fingerprints;
  private final RecordCalculationWriteSupport calculations;

  /** Creates the transaction service. */
  public RecordTransactionServiceImpl(
      final RuntimeRequestContext requestContext,
      final RuntimeRecordModelResolver modelResolver,
      final RuntimeRecordAccessPolicy accessPolicy,
      final RecordSqlCompiler compiler,
      final RuntimeCalciteRecordBackend backend,
      final RecordAuditSupport audit,
      final RecordAdmissionController admission,
      final RecordExecutionObserver observer,
      final RecordRequestFingerprint fingerprints,
      final RecordCalculationWriteSupport calculations) {
    this.requestContext = requestContext;
    this.modelResolver = modelResolver;
    this.accessPolicy = accessPolicy;
    this.compiler = compiler;
    this.backend = backend;
    this.audit = audit;
    this.admission = admission;
    this.observer = observer;
    this.fingerprints = fingerprints;
    this.calculations = calculations;
  }

  @Override
  public RecordTransactionResult findResult(final RecordTransactionCommand command) {
    if (command.idempotencyKey() == null) {
      throw new IllegalArgumentException("An idempotency key is required to look up an operation");
    }
    PreparedRecordTransaction prepared = prepare(command);
    // Apply current schema, field and resource policy before revealing any receipt metadata.
    command.mutations().forEach(mutation -> validateIntent(prepared, mutation));
    long startedAt = System.nanoTime();
    try (RecordAdmissionController.Permit ignored =
        admission.enter(prepared.access().actor().tenantId())) {
      List<RecordCommandResult> results =
          backend.findResult(
              prepared.model(),
              RecordOperationIdentity.of(
                  prepared.access().actor(), command, fingerprints.of(command)),
              command.timeoutMs());
      if (results != null && results.size() != command.mutations().size()) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Record operation receipt size is invalid");
      }
      audit.success(
          DnaRuntimeAuditEventType.RECORD_QUERIED,
          prepared.model(),
          prepared.access(),
          results == null ? 0 : 1,
          elapsed(startedAt));
      observer.record(
          "receipt", "succeeded", prepared.model(), prepared.access(), elapsed(startedAt));
      if (results == null) {
        return null;
      }
      List<RecordMutationResult> mutations = new ArrayList<>(results.size());
      for (int index = 0; index < results.size(); index++) {
        mutations.add(
            new RecordMutationResult(
                command.mutations().get(index).reference(), results.get(index)));
      }
      return new RecordTransactionResult(mutations, elapsed(startedAt));
    } catch (DnaRuntimeException failure) {
      audit.failure(
          DnaRuntimeAuditEventType.RECORD_QUERIED,
          prepared.model(),
          prepared.access(),
          elapsed(startedAt),
          failure.errorCode());
      observer.record("receipt", "failed", prepared.model(), prepared.access(), elapsed(startedAt));
      throw failure;
    }
  }

  @Override
  public RecordTransactionResult execute(final RecordTransactionCommand command) {
    long startedAt = System.nanoTime();
    return executeAuthorized(prepare(command), startedAt, null, null, null);
  }

  @Override
  public RecordTransactionResult deleteOwned(OwnedRecordDeleteTransaction request) {
    long startedAt = System.nanoTime();
    var prepared = prepare(request.parent());
    var details = new java.util.LinkedHashMap<>(prepared.calculationChildren());
    for (var reference : request.details()) {
      var child = modelResolver.resolve(prepared.access().actor(), reference.physicalModelId(),
          reference.schemaReference());
      OwnedRecordBinding.parentCode(prepared.model(), child);
      details.put(child.physicalModelId(),
          new RecordCalculationWriteSupport.Scope(child,
              accessPolicy.owned(prepared.access().actor(), child, prepared.access())));
    }
    var owned = new PreparedRecordTransaction(prepared.command(), prepared.model(), prepared.access(),
        prepared.calculation(), details);
    var operation = RecordOperationIdentity.of(prepared.access().actor(), request.parent(),
        fingerprints.of(request));
    return executeAuthorized(owned, startedAt, null, null, operation);
  }

  /** Resolves the model and current resource policy before touching idempotency state. */
  PreparedRecordTransaction prepare(final RecordTransactionCommand command) {
    RuntimeActor actor = requestContext.requireActor();
    RuntimeRecordModel model =
        modelResolver.resolve(actor, command.physicalModelId(), command.schemaReference());
    RecordAccessContext access = accessPolicy.authorize(actor, model);
    var installed =
        backend.calculation(
            model, actor.tenantId(), command.writePolicyVersion(), command.timeoutMs());
    var calculation = calculations.bind(actor, model, installed);
    var children = new java.util.LinkedHashMap<String, RecordCalculationWriteSupport.Scope>();
    if (calculation != null) {
      calculation
          .details()
          .values()
          .forEach(
              child ->
                  children.put(
                      child.physicalModelId(),
                      new RecordCalculationWriteSupport.Scope(
                          child, accessPolicy.owned(actor, child, access))));
    }
    return new PreparedRecordTransaction(command, model, access, calculation, children);
  }

  /**
   * Validates client create fields before reserving a key; computed write scope is checked in the
   * target transaction.
   */
  void validatePreparedCreate(final PreparedRecordTransaction prepared) {
    RecordCreateMutation create = requireSingleCreate(prepared);
    validateIntent(prepared, create);
  }

  private void validateIntent(
      final PreparedRecordTransaction prepared, final RecordMutation mutation) {
    if (prepared.calculation() == null) {
      compiler.mutation(
          prepared.model(),
          prepared.access(),
          mutation,
          Instant.EPOCH,
          mutation instanceof RecordCreateMutation ? VALIDATION_RECORD_ID : null);
    } else {
      compiler.validateIntent(
          prepared.model(),
          prepared.access(),
          mutation,
          prepared.calculation().controlledParentFields());
    }
  }

  /** Uses a separate operation identity while preserving the legacy deterministic record ID. */
  RecordOperationIdentity createOperation(
      PreparedRecordTransaction prepared,
      RecordCreateCommand command,
      String fingerprint) {
    if (prepared.model().activeSchema().references().isEmpty()) {
      return null;
    }
    var actor = prepared.access().actor();
    return new RecordOperationIdentity(
        RecordRequestFingerprint.deterministicCreateOperationId(
            actor.tenantId(), command.physicalModelId(), command.idempotencyKey()),
        actor.tenantId(), actor.actorId(), command.physicalModelId(), command.idempotencyKey(),
        fingerprint);
  }

  /** Reads the same target receipt used by the attempted single create, including rejection. */
  RecordCommandResult findPreparedCreateResult(
      PreparedRecordTransaction prepared, RecordOperationIdentity operation) {
    try (RecordAdmissionController.Permit ignored =
        admission.enter(prepared.access().actor().tenantId())) {
      var results = backend.findResult(prepared.model(), operation, prepared.command().timeoutMs());
      if (results == null) {
        return null;
      }
      if (results.size() != 1) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Record create operation receipt is invalid");
      }
      return results.getFirst();
    }
  }

  /** Executes one already authorized create with a durable server-derived identity. */
  RecordCommandResult executePreparedCreate(
      final PreparedRecordTransaction prepared, final String recordId, final Instant operationAt,
      final RecordOperationIdentity operation) {
    requireSingleCreate(prepared);
    return executeAuthorized(prepared, System.nanoTime(), recordId, operationAt, operation)
        .results()
        .getFirst()
        .result();
  }

  /** Finds a committed create by its server-derived identity, including soft-deleted rows. */
  boolean hasCommittedCreate(final PreparedRecordTransaction prepared, final String recordId) {
    try (RecordAdmissionController.Permit ignored =
        admission.enter(prepared.access().actor().tenantId())) {
      CalciteQueryResult result =
          backend.query(
              prepared.model(),
              prepared.access().actor().tenantId(),
              compiler.creation(prepared.model(), prepared.access(), recordId),
              prepared.command().timeoutMs());
      return !result.rows().isEmpty();
    }
  }

  private RecordTransactionResult executeAuthorized(
      final PreparedRecordTransaction prepared,
      final long startedAt,
      final String createRecordId,
      final Instant operationAt,
      final RecordOperationIdentity createOperation) {
    RuntimeRecordModel model = prepared.model();
    RecordAccessContext access = prepared.access();
    try (RecordAdmissionController.Permit ignored = admission.enter(access.actor().tenantId())) {
      return executeAdmitted(
          prepared, startedAt, createRecordId, operationAt, createOperation);
    } catch (DnaRuntimeException exception) {
      if (exception.errorCode() == DnaRuntimeErrorCode.RECORD_RATE_LIMITED) {
        audit.failure(
            DnaRuntimeAuditEventType.RECORD_TRANSACTION_FAILED,
            model,
            access,
            elapsed(startedAt),
            exception.errorCode());
        observer.record("transaction", "failed", model, access, elapsed(startedAt));
      }
      throw exception;
    }
  }

  private RecordTransactionResult executeAdmitted(
      final PreparedRecordTransaction prepared,
      final long startedAt,
      final String createRecordId,
      final Instant operationAt,
      final RecordOperationIdentity createOperation) {
    var command = prepared.command();
    var model = prepared.model();
    var access = prepared.access();
    try {
      List<RecordSqlStatement> statements = new ArrayList<>(command.mutations().size());
      var writeAdmission =
          new RecordWriteAdmission(
              access.actor().tenantId(), command.writePolicyVersion(), access.resourceGeneration());
      var operation =
          createOperation != null || command.idempotencyKey() == null
              ? createOperation
              : RecordOperationIdentity.of(access.actor(), command, fingerprints.of(command));
      RecordBatchExecutionResult execution;
      if (prepared.calculation() == null && prepared.calculationChildren().isEmpty()) {
        Instant mutationAt =
            operationAt == null
                ? Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                : operationAt;
        for (int index = 0; index < command.mutations().size(); index++) {
          statements.add(
              compiler.mutation(
                  model,
                  access,
                  command.mutations().get(index),
                  mutationAt,
                  index == 0 ? createRecordId : null));
        }
        execution =
            backend.execute(model, writeAdmission, statements, command.timeoutMs(), operation);
      } else {
        command.mutations().forEach(mutation -> validateIntent(prepared, mutation));
        var steps = new ArrayList<RecordWritePreparation.Work>();
        var intents = new ArrayList<RecordSequenceLockPlan.Intent>();
        for (int index = 0; index < command.mutations().size(); index++) {
          var mutation = command.mutations().get(index);
          String generated =
              index == 0 && createRecordId != null
                  ? createRecordId
                  : java.util.UUID.randomUUID().toString();
          String id =
              mutation instanceof RecordUpdateMutation update
                  ? update.recordId()
                  : mutation instanceof RecordDeleteMutation delete ? delete.recordId() : generated;
          intents.add(new RecordSequenceLockPlan.Intent(mutation, id));
          steps.add(
              (reader, at, installed) -> {
                verifyCalculation(prepared, installed);
                var write =
                    calculations.prepareSingle(
                        prepared.calculation(),
                        new RecordCalculationWriteSupport.Scope(model, access),
                        prepared.calculationChildren(),
                        mutation,
                        id,
                        reader,
                        operationAt == null ? at : operationAt);
                return new RecordWritePreparation(
                    write.statements(), null, 1, write.references(), null, write.copies());
              });
        }
        execution =
            backend.executePreparedSequence(
                model,
                prepared.calculationChildren().values().stream()
                    .map(RecordCalculationWriteSupport.Scope::model)
                    .toList(),
                writeAdmission,
                command.timeoutMs(),
                operation,
                steps,
                (reader, installed) -> {
                  verifyCalculation(prepared, installed);
                  return calculations.discoverSequence(
                      new RecordCalculationWriteSupport.Scope(model, access),
                      prepared.calculationChildren(),
                      List.copyOf(intents),
                      reader);
                });
      }
      List<RecordMutationResult> results = new ArrayList<>(command.mutations().size());
      for (int index = 0; index < command.mutations().size(); index++) {
        var receipt = execution.results().get(index);
        results.add(new RecordMutationResult(command.mutations().get(index).reference(), receipt));
        if (!execution.replayed()) {
          audit.success(
              auditType(receipt.operation()),
              model,
              access,
              receipt.affectedRows(),
              execution.executionTimeMs());
        }
      }
      audit.success(
          DnaRuntimeAuditEventType.RECORD_TRANSACTION_SUCCEEDED,
          model,
          access,
          execution.affectedRows().stream().mapToLong(Integer::longValue).sum(),
          execution.executionTimeMs());
      observer.record("transaction", "succeeded", model, access, execution.executionTimeMs());
      return new RecordTransactionResult(results, execution.executionTimeMs());
    } catch (RecordMutationMissException exception) {
      DnaRuntimeException diagnosed =
          diagnose(
              model,
              access,
              command.mutations().get(exception.mutationIndex()),
              command.timeoutMs());
      audit.failure(
          DnaRuntimeAuditEventType.RECORD_TRANSACTION_FAILED,
          model,
          access,
          elapsed(startedAt),
          diagnosed.errorCode());
      observer.record("transaction", "failed", model, access, elapsed(startedAt));
      throw diagnosed;
    } catch (DnaRuntimeException exception) {
      audit.failure(
          DnaRuntimeAuditEventType.RECORD_TRANSACTION_FAILED,
          model,
          access,
          elapsed(startedAt),
          exception.errorCode());
      observer.record("transaction", "failed", model, access, elapsed(startedAt));
      throw exception;
    } catch (RuntimeException exception) {
      audit.failure(
          DnaRuntimeAuditEventType.RECORD_TRANSACTION_FAILED,
          model,
          access,
          elapsed(startedAt),
          DnaRuntimeErrorCode.RECORD_TRANSACTION_FAILED);
      observer.record("transaction", "failed", model, access, elapsed(startedAt));
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_TRANSACTION_FAILED, "Record transaction failed", exception);
    }
  }

  private static void verifyCalculation(PreparedRecordTransaction prepared,
      RecordCalculationStore.Installed installed) {
    if (prepared.calculation() != null) {
      prepared.calculation().verify(installed);
    } else if (installed != null && !installed.policy().definition().isEmpty()) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID,
          "Calculation metadata changed during record transaction preparation");
    }
  }

  private DnaRuntimeException diagnose(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordMutation mutation,
      final int timeoutMs) {
    String recordId;
    long expectedVersion;
    if (mutation instanceof RecordUpdateMutation update) {
      recordId = update.recordId();
      expectedVersion = update.expectedVersion();
    } else if (mutation instanceof RecordDeleteMutation delete) {
      recordId = delete.recordId();
      expectedVersion = delete.expectedVersion();
    } else {
      return new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED,
          "Record creation did not affect exactly one row");
    }
    CalciteQueryResult result =
        backend.query(
            model, access.actor().tenantId(), compiler.version(model, access, recordId), timeoutMs);
    if (result.rows().isEmpty()) {
      return new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_NOT_FOUND, "Record was not found in the active data scope");
    }
    long actualVersion = ((Number) result.rows().getFirst().get(1)).longValue();
    if (actualVersion != expectedVersion) {
      return new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT,
          "Record version does not match expectedVersion");
    }
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED,
        "Record mutation did not affect exactly one row");
  }

  private static DnaRuntimeAuditEventType auditType(final RecordMutationType type) {
    return switch (type) {
      case CREATE -> DnaRuntimeAuditEventType.RECORD_CREATED;
      case UPDATE -> DnaRuntimeAuditEventType.RECORD_UPDATED;
      case DELETE -> DnaRuntimeAuditEventType.RECORD_DELETED;
    };
  }

  private static long elapsed(final long startedAt) {
    return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
  }

  private static RecordCreateMutation requireSingleCreate(
      final PreparedRecordTransaction prepared) {
    if (prepared.command().mutations().size() != 1
        || !(prepared.command().mutations().getFirst() instanceof RecordCreateMutation create)) {
      throw new IllegalArgumentException("Prepared create requires exactly one create mutation");
    }
    return create;
  }
}
