package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
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
      final RecordRequestFingerprint fingerprints
  ) {
    this.requestContext = requestContext;
    this.modelResolver = modelResolver;
    this.accessPolicy = accessPolicy;
    this.compiler = compiler;
    this.backend = backend;
    this.audit = audit;
    this.admission = admission;
    this.observer = observer;
    this.fingerprints = fingerprints;
  }

  @Override
  public RecordTransactionResult findResult(final RecordTransactionCommand command) {
    if (command.idempotencyKey() == null) {
      throw new IllegalArgumentException("An idempotency key is required to look up an operation");
    }
    PreparedRecordTransaction prepared = prepare(command);
    // Apply current schema, field and resource policy before revealing any receipt metadata.
    command.mutations().forEach(mutation -> compiler.mutation(
        prepared.model(), prepared.access(), mutation, Instant.EPOCH));
    long startedAt = System.nanoTime();
    try (RecordAdmissionController.Permit ignored = admission.enter(prepared.access().actor().tenantId())) {
      List<RecordCommandResult> results = backend.findResult(prepared.model(),
          RecordOperationIdentity.of(prepared.access().actor(), command, fingerprints.of(command)), command.timeoutMs());
      if (results != null && results.size() != command.mutations().size()) {
        throw new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Record operation receipt size is invalid");
      }
      audit.success(DnaRuntimeAuditEventType.RECORD_QUERIED, prepared.model(), prepared.access(),
          results == null ? 0 : 1, elapsed(startedAt));
      observer.record("receipt", "succeeded", prepared.model(), prepared.access(), elapsed(startedAt));
      if (results == null) {
        return null;
      }
      List<RecordMutationResult> mutations = new ArrayList<>(results.size());
      for (int index = 0; index < results.size(); index++) {
        mutations.add(new RecordMutationResult(command.mutations().get(index).reference(), results.get(index)));
      }
      return new RecordTransactionResult(mutations, elapsed(startedAt));
    } catch (DnaRuntimeException failure) {
      audit.failure(DnaRuntimeAuditEventType.RECORD_QUERIED, prepared.model(), prepared.access(),
          elapsed(startedAt), failure.errorCode());
      observer.record("receipt", "failed", prepared.model(), prepared.access(), elapsed(startedAt));
      throw failure;
    }
  }

  @Override
  public RecordTransactionResult execute(final RecordTransactionCommand command) {
    long startedAt = System.nanoTime();
    return executeAuthorized(prepare(command), startedAt, null, null);
  }

  /** Resolves the model and current resource policy before touching idempotency state. */
  PreparedRecordTransaction prepare(final RecordTransactionCommand command) {
    RuntimeActor actor = requestContext.requireActor();
    RuntimeRecordModel model = modelResolver.resolve(
        actor,
        command.physicalModelId(),
        command.schemaReference()
    );
    RecordAccessContext access = accessPolicy.authorize(actor, model);
    return new PreparedRecordTransaction(command, model, access);
  }

  /** Validates create values and write scope before a durable idempotency key is reserved. */
  void validatePreparedCreate(final PreparedRecordTransaction prepared) {
    RecordCreateMutation create = requireSingleCreate(prepared);
    compiler.mutation(
        prepared.model(),
        prepared.access(),
        create,
        Instant.EPOCH,
        VALIDATION_RECORD_ID
    );
  }

  /** Executes one already authorized create with a durable server-derived identity. */
  RecordCommandResult executePreparedCreate(
      final PreparedRecordTransaction prepared,
      final String recordId,
      final Instant operationAt
  ) {
    requireSingleCreate(prepared);
    return executeAuthorized(prepared, System.nanoTime(), recordId, operationAt)
        .results().getFirst().result();
  }

  /** Finds a committed create by its server-derived identity, including soft-deleted rows. */
  boolean hasCommittedCreate(
      final PreparedRecordTransaction prepared,
      final String recordId
  ) {
    try (RecordAdmissionController.Permit ignored = admission.enter(
        prepared.access().actor().tenantId()
    )) {
      CalciteQueryResult result = backend.query(
          prepared.model(),
          compiler.creation(prepared.model(), prepared.access(), recordId),
          prepared.command().timeoutMs()
      );
      return !result.rows().isEmpty();
    }
  }

  private RecordTransactionResult executeAuthorized(
      final PreparedRecordTransaction prepared,
      final long startedAt,
      final String createRecordId,
      final Instant operationAt
  ) {
    RuntimeRecordModel model = prepared.model();
    RecordAccessContext access = prepared.access();
    try (RecordAdmissionController.Permit ignored = admission.enter(access.actor().tenantId())) {
      return executeAdmitted(
          prepared.command(),
          startedAt,
          model,
          access,
          createRecordId,
          operationAt
      );
    } catch (DnaRuntimeException exception) {
      if (exception.errorCode() == DnaRuntimeErrorCode.RECORD_RATE_LIMITED) {
        audit.failure(
            DnaRuntimeAuditEventType.RECORD_TRANSACTION_FAILED,
            model,
            access,
            elapsed(startedAt),
            exception.errorCode()
        );
        observer.record("transaction", "failed", model, access, elapsed(startedAt));
      }
      throw exception;
    }
  }

  private RecordTransactionResult executeAdmitted(
      final RecordTransactionCommand command,
      final long startedAt,
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final String createRecordId,
      final Instant operationAt
  ) {
    try {
      List<RecordSqlStatement> statements = new ArrayList<>(command.mutations().size());
      for (int index = 0; index < command.mutations().size(); index++) {
        RecordMutation mutation = command.mutations().get(index);
        Instant mutationAt = operationAt == null ? Instant.now() : operationAt;
        String preferredId = index == 0 ? createRecordId : null;
        statements.add(compiler.mutation(model, access, mutation, mutationAt, preferredId));
      }
      var writeAdmission = new RecordWriteAdmission(access.actor().tenantId(), command.writePolicyVersion(), access.resourceGeneration());
      RecordBatchExecutionResult execution = command.idempotencyKey() == null
          ? backend.execute(model, writeAdmission, statements, command.timeoutMs())
          : backend.execute(model, writeAdmission, statements, command.timeoutMs(),
              RecordOperationIdentity.of(access.actor(), command, fingerprints.of(command)));
      List<RecordMutationResult> results = new ArrayList<>(statements.size());
      for (int index = 0; index < statements.size(); index++) {
        RecordSqlStatement statement = statements.get(index);
        results.add(new RecordMutationResult(
            command.mutations().get(index).reference(),
            execution.replayedResults() != null ? execution.replayedResults().get(index) : new RecordCommandResult(
                statement.mutationType(),
                statement.recordId(),
                statement.resultVersion(),
                execution.affectedRows().get(index),
                statement.updatedAt()
            )
        ));
        if (execution.replayedResults() == null) {
          audit.success(
              auditType(statement.mutationType()),
              model,
              access,
              execution.affectedRows().get(index),
              execution.executionTimeMs()
          );
        }
      }
      audit.success(
          DnaRuntimeAuditEventType.RECORD_TRANSACTION_SUCCEEDED,
          model,
          access,
          execution.affectedRows().stream().mapToLong(Integer::longValue).sum(),
          execution.executionTimeMs()
      );
      observer.record("transaction", "succeeded", model, access, execution.executionTimeMs());
      return new RecordTransactionResult(results, execution.executionTimeMs());
    } catch (RecordMutationMissException exception) {
      DnaRuntimeException diagnosed = diagnose(
          model,
          access,
          command.mutations().get(exception.mutationIndex()),
          command.timeoutMs()
      );
      audit.failure(
          DnaRuntimeAuditEventType.RECORD_TRANSACTION_FAILED,
          model,
          access,
          elapsed(startedAt),
          diagnosed.errorCode()
      );
      observer.record("transaction", "failed", model, access, elapsed(startedAt));
      throw diagnosed;
    } catch (DnaRuntimeException exception) {
      audit.failure(
          DnaRuntimeAuditEventType.RECORD_TRANSACTION_FAILED,
          model,
          access,
          elapsed(startedAt),
          exception.errorCode()
      );
      observer.record("transaction", "failed", model, access, elapsed(startedAt));
      throw exception;
    } catch (RuntimeException exception) {
      audit.failure(
          DnaRuntimeAuditEventType.RECORD_TRANSACTION_FAILED,
          model,
          access,
          elapsed(startedAt),
          DnaRuntimeErrorCode.RECORD_TRANSACTION_FAILED
      );
      observer.record("transaction", "failed", model, access, elapsed(startedAt));
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_TRANSACTION_FAILED,
          "Record transaction failed",
          exception
      );
    }
  }

  private DnaRuntimeException diagnose(
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final RecordMutation mutation,
      final int timeoutMs
  ) {
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
          "Record creation did not affect exactly one row"
      );
    }
    CalciteQueryResult result = backend.query(
        model,
        compiler.version(model, access, recordId),
        timeoutMs
    );
    if (result.rows().isEmpty()) {
      return new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_NOT_FOUND,
          "Record was not found in the active data scope"
      );
    }
    long actualVersion = ((Number) result.rows().getFirst().get(1)).longValue();
    if (actualVersion != expectedVersion) {
      return new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT,
          "Record version does not match expectedVersion"
      );
    }
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED,
        "Record mutation did not affect exactly one row"
    );
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
      final PreparedRecordTransaction prepared
  ) {
    if (prepared.command().mutations().size() != 1
        || !(prepared.command().mutations().getFirst() instanceof RecordCreateMutation create)) {
      throw new IllegalArgumentException("Prepared create requires exactly one create mutation");
    }
    return create;
  }
}
