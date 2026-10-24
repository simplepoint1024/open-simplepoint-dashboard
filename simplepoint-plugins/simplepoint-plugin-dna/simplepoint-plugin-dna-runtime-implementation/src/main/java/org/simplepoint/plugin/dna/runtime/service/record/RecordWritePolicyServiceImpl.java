package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyCancellationView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationView;
import org.simplepoint.plugin.dna.runtime.api.service.RecordWritePolicyService;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.springframework.stereotype.Service;

/** Compiles authorized historical checks and delegates one bounded physical publication transaction. */
@Service
public class RecordWritePolicyServiceImpl implements RecordWritePolicyService {

  private final RecordCalculationPublicationAdapter calculations;
  private final RuntimeRequestContext context;
  private final RuntimeRecordModelResolver models;
  private final RecordSqlCompiler compiler;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordRequestFingerprint fingerprints;
  private final RecordAdmissionController admission;
  private final RecordAuditSupport audit;
  private final RecordExecutionObserver observer;

  /** Reuses Record authorization, SQL compilation, admission, auditing and execution infrastructure. */
  public RecordWritePolicyServiceImpl(final RuntimeRequestContext context, final RuntimeRecordModelResolver models,
      final RecordSqlCompiler compiler, final RuntimeCalciteRecordBackend backend, final RecordRequestFingerprint fingerprints,
      final RecordAdmissionController admission, final RecordAuditSupport audit, final RecordExecutionObserver observer,
      final RecordCalculationPublicationAdapter calculations) {
    this.calculations = calculations;
    this.context = context;
    this.models = models;
    this.compiler = compiler;
    this.backend = backend;
    this.fingerprints = fingerprints;
    this.admission = admission;
    this.audit = audit;
    this.observer = observer;
  }

  @Override
  public RecordWritePolicyPublicationView publish(final RecordWritePolicyPublicationCommand command) {
    RecordManagementAccess.requireAuthority();
    var actor = context.requireActor();
    var deadline = new RecordExecutionDeadline(command.timeoutMs());
    long startedAt = System.nanoTime();
    RuntimeRecordModel model = models.resolve(actor, command.physicalModelId(), command.schemaReference());
    var access = RecordManagementAccess.forOwner(actor, model, command.appId(), command.formId());
    try (RecordAdmissionController.Permit ignored = admission.enter(actor.tenantId())) {
      var result = command.calculation() == null ? synchronousPublication(command, actor, model, deadline) : calculations.publish(actor, command);
      if (result.published()) {
        audit.success(DnaRuntimeAuditEventType.RECORD_WRITE_POLICY_PUBLISHED, model, access, 0, elapsed(startedAt));
      } else if (result.progress() == null || !result.violations().isEmpty() || !result.valueViolations().isEmpty()) {
        audit.failure(DnaRuntimeAuditEventType.RECORD_WRITE_POLICY_PUBLISHED, model, access,
            elapsed(startedAt), DnaRuntimeErrorCode.RECORD_WRITE_POLICY_PRECHECK_FAILED);
      }
      observer.record("policy-publication", result.published() ? "succeeded"
          : result.violations().isEmpty() && result.valueViolations().isEmpty() ? "pending" : "blocked", model, access, elapsed(startedAt));
      return result;
    } catch (RuntimeException exception) {
      var code = exception instanceof DnaRuntimeException failure ? failure.errorCode() : DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED;
      audit.failure(DnaRuntimeAuditEventType.RECORD_WRITE_POLICY_PUBLISHED, model, access, elapsed(startedAt), code);
      observer.record("policy-publication", "failed", model, access, elapsed(startedAt));
      throw exception;
    }
  }

  @Override
  public RecordWritePolicyCancellationView cancel(final RecordWritePolicyPublicationCommand command) {
    RecordManagementAccess.requireAuthority();
    var actor = context.requireActor();
    var deadline = new RecordExecutionDeadline(command.timeoutMs());
    long startedAt = System.nanoTime();
    RuntimeRecordModel model = models.resolveForCancellation(actor, command.physicalModelId(), command.schemaReference());
    var access = RecordManagementAccess.forOwner(actor, model, command.appId(), command.formId());
    try (RecordAdmissionController.Permit ignored = admission.enter(actor.tenantId())) {
      var result = command.calculation() == null
          ? backend.cancelWritePolicy(model, actor, command, fingerprints.of(command), fingerprints.cancellationOf(command), deadline)
          : backend.cancelUnstartedCalculationPolicy(model, actor, command, fingerprints.of(command),
              fingerprints.cancellationOf(command), deadline).orElseGet(() -> calculations.cancel(actor, command));
      if (result.progress() == null) {
        audit.success(result.cancelled() ? DnaRuntimeAuditEventType.RECORD_WRITE_POLICY_CANCELLED
            : DnaRuntimeAuditEventType.RECORD_WRITE_POLICY_PUBLISHED, model, access, 0, elapsed(startedAt));
      }
      observer.record("policy-cancellation", result.progress() != null ? "restoring" : result.cancelled() ? "cancelled" : "already-published",
          model, access, elapsed(startedAt));
      return result;
    } catch (RuntimeException exception) {
      var code = exception instanceof DnaRuntimeException failure ? failure.errorCode() : DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED;
      audit.failure(DnaRuntimeAuditEventType.RECORD_WRITE_POLICY_CANCELLED, model, access, elapsed(startedAt), code);
      observer.record("policy-cancellation", "failed", model, access, elapsed(startedAt));
      throw exception;
    }
  }

  private RecordWritePolicyPublicationView synchronousPublication(final RecordWritePolicyPublicationCommand command,
      final org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor actor,
      final RuntimeRecordModel model, final RecordExecutionDeadline deadline) {
    Map<String, RecordWritePolicyInspection> checks = new LinkedHashMap<>();
    for (var entry : command.checks().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      deadline.remainingMillis();
      var query = entry.getValue();
      RuntimeRecordModel projection = models.resolve(actor, command.physicalModelId(), query.schemaReference());
      var inspectionAccess = RecordManagementAccess.forOwner(actor, projection, command.appId(), command.formId());
      requireSameTarget(model, projection);
      checks.put(entry.getKey(), new RecordWritePolicyInspection(query, compiler.completeness(projection, inspectionAccess, query)));
    }
    Map<String, RecordSelectPlan> valueChecks = new LinkedHashMap<>();
    for (var entry : command.valueChecks().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      deadline.remainingMillis();
      var query = entry.getValue();
      RuntimeRecordModel projection = models.resolve(actor, command.physicalModelId(), query.schemaReference());
      var inspectionAccess = RecordManagementAccess.forOwner(actor, projection, command.appId(), command.formId());
      requireSameTarget(model, projection);
      valueChecks.put(entry.getKey(), compiler.violations(projection, inspectionAccess, query));
    }
    return backend.publishWritePolicy(model, actor, command, fingerprints.of(command), checks, valueChecks, deadline);
  }

  private static void requireSameTarget(final RuntimeRecordModel model, final RuntimeRecordModel projection) {
    if (!model.target().equals(projection.target()) || model.activeVersion() != projection.activeVersion()
        || !model.activeSchema().equals(projection.activeSchema())) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
          "The active physical schema changed while preparing publication checks");
    }
  }

  private static long elapsed(final long startedAt) {
    return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
  }

}
