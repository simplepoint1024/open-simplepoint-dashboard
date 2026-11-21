package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyCancellationView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPhase;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyProgress;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationView;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;

class RecordCalculationPublicationServiceTest {
  private final RecordPublicationPatchFixture fixture =
      new RecordPublicationPatchFixture("db", "forms");
  private final RuntimeRequestContext context = mock(RuntimeRequestContext.class);
  private final RuntimeRecordModelResolver models = mock(RuntimeRecordModelResolver.class);
  private final RuntimeCalciteRecordBackend backend = mock(RuntimeCalciteRecordBackend.class);
  private final RecordCalculationPublicationAdapter calculations = mock(RecordCalculationPublicationAdapter.class);
  private final RecordAuditSupport audit = mock(RecordAuditSupport.class);
  private final RecordExecutionObserver observer = mock(RecordExecutionObserver.class);
  private final RecordAdmissionController admission = mock(RecordAdmissionController.class);
  private final RecordAdmissionController.Permit permit = mock(RecordAdmissionController.Permit.class);
  private final RuntimeRequestContext.RuntimeActor actor =
      new RuntimeRequestContext.RuntimeActor("tenant-a", "publisher");
  private final RecordWritePolicyServiceImpl service = new RecordWritePolicyServiceImpl(context, models, mock(RecordSqlCompiler.class), backend,
      mock(RecordRequestFingerprint.class), admission, audit, observer, calculations);

  @AfterEach
  void clear() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void keepsManagementAuthorizationBeforeAnyMetadataOrTargetWork() {
    assertCode(() -> service.inspectReferenceCollection(null), DnaRuntimeErrorCode.RECORD_ACCESS_DENIED);
    assertCode(() -> service.publish(command()), DnaRuntimeErrorCode.RECORD_ACCESS_DENIED);
    assertCode(() -> service.cancel(command()), DnaRuntimeErrorCode.RECORD_ACCESS_DENIED);
    verifyNoInteractions(context, models, backend, calculations, admission);
  }

  @Test
  void routesCalculatedPublicationWithAdmissionAndDoesNotAuditProgressAsSuccessOrFailure() {
    authorize();
    var progress = new RecordWritePolicyProgress(1, RecordWritePolicyPhase.APPLYING, 3, 1, 1, null);
    var pending = new RecordWritePolicyPublicationView(false, 0, fixture.intent.fingerprint(), Map.of(), Map.of(), progress);
    when(calculations.publish(actor, command())).thenReturn(pending);
    assertThat(service.publish(command())).isEqualTo(pending);
    verifyNoInteractions(backend, audit);
    verify(observer)
        .record(eq("policy-publication"), eq("pending"), eq(fixture.parent), any(), anyLong());
    verify(permit).close();
    var complete = new RecordWritePolicyPublicationView(true, 1, fixture.intent.fingerprint(), Map.of());
    when(calculations.publish(actor, command())).thenReturn(complete);
    assertThat(service.publish(command())).isEqualTo(complete);
    verify(audit).success(eq(DnaRuntimeAuditEventType.RECORD_WRITE_POLICY_PUBLISHED), eq(fixture.parent), any(), eq(0L), anyLong());
  }

  @Test
  void auditsDurableFinalRejectionAsBlockedDespiteHavingProgress() {
    authorize();
    var progress = new RecordWritePolicyProgress(1, RecordWritePolicyPhase.APPLYING, 4, 1, 1,
        DnaRuntimeErrorCode.RECORD_WRITE_POLICY_PRECHECK_FAILED);
    var blocked =
        new RecordWritePolicyPublicationView(
            false, 0, fixture.intent.fingerprint(), Map.of(), Map.of("invariant", 1L), progress);
    when(calculations.publish(actor, command())).thenReturn(blocked);
    assertThat(service.publish(command())).isEqualTo(blocked);
    verify(audit).failure(eq(DnaRuntimeAuditEventType.RECORD_WRITE_POLICY_PUBLISHED), eq(fixture.parent), any(), anyLong(),
        eq(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_PRECHECK_FAILED));
    verify(observer)
        .record(eq("policy-publication"), eq("blocked"), eq(fixture.parent), any(), anyLong());
  }

  @Test
  void cancellationProgressCannotBecomeAnAlreadyPublishedAuditEvent() {
    authorize();
    var progress = new RecordWritePolicyProgress(1, RecordWritePolicyPhase.RESTORING, 4, 0, 1, null);
    var restoring = new RecordWritePolicyCancellationView(false, 0, fixture.intent.fingerprint(), null, progress);
    when(calculations.cancel(actor, command())).thenReturn(restoring);
    assertThat(service.cancel(command())).isEqualTo(restoring);
    verifyNoInteractions(audit);
    verify(backend).cancelUnstartedCalculationPolicy(eq(fixture.parent), eq(actor), eq(command()), any(), any(), any());
    verify(observer)
        .record(eq("policy-cancellation"), eq("restoring"), eq(fixture.parent), any(), anyLong());
    var cancelled = new RecordWritePolicyCancellationView(true, 1, fixture.intent.fingerprint(), "c".repeat(64));
    when(calculations.cancel(actor, command())).thenReturn(cancelled);
    assertThat(service.cancel(command())).isEqualTo(cancelled);
    verify(audit).success(eq(DnaRuntimeAuditEventType.RECORD_WRITE_POLICY_CANCELLED), eq(fixture.parent), any(), eq(0L), anyLong());
  }

  @Test
  void rejectsTheWrongOwnerBeforeEnteringTheCoordinatorAndReleasesAdmissionOnFailure() {
    authorize();
    var base = command();
    var wrong =
        new RecordWritePolicyPublicationCommand(
            base.physicalModelId(),
            "other-app",
            base.formId(),
            base.schemaReference(),
            0,
            1,
            base.publicationKey(),
            base.policyChecksum(),
            Map.of(),
            1000,
            Map.of(),
            base.calculation());
    assertThatThrownBy(() -> service.publish(wrong)).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> service.cancel(wrong)).isInstanceOf(DnaRuntimeException.class);
    verifyNoInteractions(calculations, admission);
    when(calculations.publish(actor, base))
        .thenThrow(
            new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED, "test failure"));
    assertCode(() -> service.publish(base), DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED);
    verify(permit).close();
    verify(audit).failure(eq(DnaRuntimeAuditEventType.RECORD_WRITE_POLICY_PUBLISHED), any(), any(), anyLong(),
        eq(DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED));
  }

  @Test
  void unstartedCancellationDoesNotEnterBusinessProjectionOrBatchRestoration() {
    authorize();
    var cancelled = new RecordWritePolicyCancellationView(true, 1, fixture.intent.fingerprint(), "c".repeat(64));
    when(backend.cancelUnstartedCalculationPolicy(eq(fixture.parent), eq(actor), eq(command()), any(), any(), any()))
        .thenReturn(java.util.Optional.of(cancelled));
    assertThat(service.cancel(command())).isEqualTo(cancelled);
    verifyNoInteractions(calculations);
    verify(models, never()).resolve(any(), any(), any());
    verify(permit).close();
    verify(audit).success(eq(DnaRuntimeAuditEventType.RECORD_WRITE_POLICY_CANCELLED), eq(fixture.parent), any(), eq(0L), anyLong());
  }

  private void authorize() {
    var authorization = new AuthorizationContext();
    authorization.setIsAdministrator(true);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
    when(context.requireActor()).thenReturn(actor);
    when(models.resolve(eq(actor), eq(command().physicalModelId()), any())).thenReturn(fixture.parent);
    when(models.resolveForCancellation(eq(actor), eq(command().physicalModelId()), any())).thenReturn(fixture.parent);
    when(admission.enter(actor.tenantId())).thenReturn(permit);
  }

  private RecordWritePolicyPublicationCommand command() {
    return fixture.intent.command();
  }

  private static void assertCode(Runnable operation, DnaRuntimeErrorCode code) {
    assertThatThrownBy(operation::run).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(code));
  }
}
