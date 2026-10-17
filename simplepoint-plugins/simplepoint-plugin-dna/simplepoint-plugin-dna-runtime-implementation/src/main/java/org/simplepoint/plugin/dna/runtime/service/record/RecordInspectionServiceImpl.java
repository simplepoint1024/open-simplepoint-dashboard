package org.simplepoint.plugin.dna.runtime.service.record;

import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessView;
import org.simplepoint.plugin.dna.runtime.api.service.RecordInspectionService;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.springframework.stereotype.Service;

/** Tenant/owner-scoped management inspection; deliberately does not use a page reader's data scope. */
@Service
public class RecordInspectionServiceImpl implements RecordInspectionService {

  private static final int TIMEOUT_MS = 30_000;

  private final RuntimeRequestContext requestContext;
  private final RuntimeRecordModelResolver modelResolver;
  private final RecordSqlCompiler compiler;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordAdmissionController admission;
  private final RecordAuditSupport audit;
  private final RecordExecutionObserver observer;

  /** Uses the same execution, admission, audit and metrics infrastructure as Record reads. */
  public RecordInspectionServiceImpl(final RuntimeRequestContext requestContext,
      final RuntimeRecordModelResolver modelResolver, final RecordSqlCompiler compiler,
      final RuntimeCalciteRecordBackend backend, final RecordAdmissionController admission,
      final RecordAuditSupport audit, final RecordExecutionObserver observer) {
    this.requestContext = requestContext;
    this.modelResolver = modelResolver;
    this.compiler = compiler;
    this.backend = backend;
    this.admission = admission;
    this.audit = audit;
    this.observer = observer;
  }

  @Override
  public RecordCompletenessView inspectCompleteness(final RecordCompletenessQuery query) {
    RecordManagementAccess.requireAuthority();
    var actor = requestContext.requireActor();
    RuntimeRecordModel model = modelResolver.resolve(actor, query.physicalModelId(), query.schemaReference());
    RecordAccessContext access = RecordManagementAccess.forOwner(actor, model, query.appId(), query.formId());
    long startedAt = System.nanoTime();
    try (RecordAdmissionController.Permit ignored = admission.enter(actor.tenantId())) {
      RecordSelectPlan plan = compiler.completeness(model, access, query);
      RecordCompletenessView result = RecordCompletenessResult.read(query, backend.aggregateQuery(model, plan, TIMEOUT_MS));
      audit.success(DnaRuntimeAuditEventType.RECORD_QUERIED, model, access, result.recordCount(), elapsed(startedAt));
      observer.record("completeness", "succeeded", model, access, elapsed(startedAt));
      return result;
    } catch (RuntimeException exception) {
      DnaRuntimeErrorCode code = exception instanceof DnaRuntimeException failure
          ? failure.errorCode() : DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED;
      audit.failure(DnaRuntimeAuditEventType.RECORD_QUERIED, model, access, elapsed(startedAt), code);
      observer.record("completeness", "failed", model, access, elapsed(startedAt));
      throw exception;
    }
  }

  private static long elapsed(final long startedAt) {
    return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
  }
}
