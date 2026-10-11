package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
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

  private final RuntimeRequestContext requestContext;
  private final RuntimeRecordModelResolver modelResolver;
  private final RuntimeRecordAccessPolicy accessPolicy;
  private final RecordSqlCompiler compiler;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordAuditSupport audit;
  private final RecordAdmissionController admission;
  private final RecordExecutionObserver observer;

  /** Creates the transaction service. */
  public RecordTransactionServiceImpl(
      final RuntimeRequestContext requestContext,
      final RuntimeRecordModelResolver modelResolver,
      final RuntimeRecordAccessPolicy accessPolicy,
      final RecordSqlCompiler compiler,
      final RuntimeCalciteRecordBackend backend,
      final RecordAuditSupport audit,
      final RecordAdmissionController admission,
      final RecordExecutionObserver observer
  ) {
    this.requestContext = requestContext;
    this.modelResolver = modelResolver;
    this.accessPolicy = accessPolicy;
    this.compiler = compiler;
    this.backend = backend;
    this.audit = audit;
    this.admission = admission;
    this.observer = observer;
  }

  @Override
  public RecordTransactionResult execute(final RecordTransactionCommand command) {
    long startedAt = System.nanoTime();
    RuntimeActor actor = requestContext.requireActor();
    RuntimeRecordModel model = modelResolver.resolve(actor, command.physicalModelId());
    RecordAccessContext access = accessPolicy.authorize(actor, model);
    try (RecordAdmissionController.Permit ignored = admission.enter(actor.tenantId())) {
      return executeAdmitted(command, startedAt, model, access);
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
      final RecordAccessContext access
  ) {
    try {
      List<RecordSqlStatement> statements = new ArrayList<>(command.mutations().size());
      for (RecordMutation mutation : command.mutations()) {
        statements.add(compiler.mutation(model, access, mutation, Instant.now()));
      }
      RecordBatchExecutionResult execution = backend.execute(
          model,
          statements,
          command.timeoutMs()
      );
      List<RecordMutationResult> results = new ArrayList<>(statements.size());
      for (int index = 0; index < statements.size(); index++) {
        RecordSqlStatement statement = statements.get(index);
        results.add(new RecordMutationResult(
            command.mutations().get(index).reference(),
            new RecordCommandResult(
                statement.mutationType(),
                statement.recordId(),
                statement.resultVersion(),
                execution.affectedRows().get(index),
                statement.updatedAt()
            )
        ));
        audit.success(
            auditType(statement.mutationType()),
            model,
            access,
            execution.affectedRows().get(index),
            execution.executionTimeMs()
        );
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
}
