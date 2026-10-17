package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingWork;
import org.simplepoint.plugin.app.form.service.FormWritePolicyPublicationStateService.PreparedPolicy;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyCancellationView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationView;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;

class FormWritePolicyPublicationCoordinatorTest {

  private final FormWritePolicyPublicationStateService state = mock(FormWritePolicyPublicationStateService.class);
  private final DnaFormRuntimeService dna = mock(DnaFormRuntimeService.class);
  private final DnaInvocationContextFactory contexts = mock(DnaInvocationContextFactory.class);
  private final FormWritePolicyPublicationCoordinator coordinator = new FormWritePolicyPublicationCoordinator(state, dna, contexts);
  private final BindingWork work = new BindingWork("binding", "attempt", "tenant", "app", "page", "form", 1,
      "model", "page:page", "Page", null, 0, 1);
  private final FormWritePolicyImpact impact = new FormWritePolicyImpact(0, 1, Set.of(), Map.of());
  private final RecordWritePolicyPublicationCommand command = new RecordWritePolicyPublicationCommand(
      "model", "app", "form", new RecordSchemaReference(1, "a".repeat(64)), 0, 1,
      "app-policy:binding", "b".repeat(64), Map.of(), 30_000);

  @Test
  void persistsBeforeTheRemoteCallAndRecoversTheExactIntentAfterAcknowledgementLoss() {
    var context = mock(DnaInvocationContext.class);
    when(contexts.current()).thenReturn(context);
    var prepared = new PreparedPolicy("intent", FormWritePolicyPublicationStatus.PREPARED, command, null, null);
    var confirmed = new PreparedPolicy("intent", FormWritePolicyPublicationStatus.TARGET_READY, command, "c".repeat(64), null);
    var target = new RecordWritePolicyPublicationView(true, 1, "c".repeat(64), Map.of());
    when(state.prepare(eq(work), any())).thenReturn(prepared);
    when(dna.publishRecordWritePolicy(context, command))
        .thenThrow(new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED, "Lost commit response"))
        .thenReturn(target);
    when(state.recordResult(work, target)).thenReturn(confirmed);
    assertThatThrownBy(() -> coordinator.prepareTarget(work, impact)).isInstanceOf(ApplicationFormException.class);
    assertThat(coordinator.prepareTarget(work, impact)).isEqualTo(confirmed);
    var order = inOrder(state, dna);
    order.verify(state).prepare(work, impact);
    order.verify(dna).publishRecordWritePolicy(context, command);
    order.verify(state).prepare(work, impact);
    order.verify(dna).publishRecordWritePolicy(context, command);
    order.verify(state).recordResult(work, target);
    verify(dna, times(2)).publishRecordWritePolicy(context, command);
  }

  @Test
  void resumesCancellationWithoutAccidentallyPublishingAfterResponseLoss() {
    var context = mock(DnaInvocationContext.class);
    when(contexts.current()).thenReturn(context);
    var pending = new PreparedPolicy("intent", FormWritePolicyPublicationStatus.CANCELLING, command, null, null);
    var resolution = new RecordWritePolicyCancellationView(true, 1, "a".repeat(64), "b".repeat(64));
    var confirmed = new PreparedPolicy("intent", FormWritePolicyPublicationStatus.CANCELLED, command, "b".repeat(64), null, resolution);
    when(state.requestCancellation(work)).thenReturn(pending);
    when(state.prepare(work, impact)).thenReturn(pending);
    when(dna.cancelRecordWritePolicy(context, command))
        .thenThrow(new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED, "Lost cancellation response")).thenReturn(resolution);
    when(state.recordCancellation(work, resolution)).thenReturn(confirmed);
    assertThatThrownBy(() -> coordinator.cancelTarget(work)).isInstanceOf(ApplicationFormException.class);
    assertThat(coordinator.prepareTarget(work, impact)).isEqualTo(confirmed);
    var order = inOrder(state, dna);
    order.verify(state).requestCancellation(work);
    order.verify(dna).cancelRecordWritePolicy(context, command);
    order.verify(state).prepare(work, impact);
    order.verify(state).requestCancellation(work);
    order.verify(dna).cancelRecordWritePolicy(context, command);
    order.verify(state).recordCancellation(work, resolution);
    verify(dna, org.mockito.Mockito.never()).publishRecordWritePolicy(any(), any());
  }

  @Test
  void confirmedTargetWaitsForAppActivationWithoutRepeatingRemoteWork() {
    var confirmed = new PreparedPolicy("intent", FormWritePolicyPublicationStatus.TARGET_READY, command, "c".repeat(64), null);
    when(state.prepare(work, impact)).thenReturn(confirmed);
    assertThat(coordinator.prepareTarget(work, impact)).isEqualTo(confirmed);
    verifyNoInteractions(dna, contexts);
  }
}
