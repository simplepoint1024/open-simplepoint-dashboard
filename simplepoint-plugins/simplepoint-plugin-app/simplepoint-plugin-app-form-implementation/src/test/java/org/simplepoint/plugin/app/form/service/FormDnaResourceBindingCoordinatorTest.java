package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingWork;
import org.simplepoint.plugin.app.form.service.FormPageResourceCoordinator.PreparedPageResource;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelView;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;

class FormDnaResourceBindingCoordinatorTest {

  @Test
  void mapsObsoleteDnaProjectionToTerminalPublicationFailure() {
    DnaFormRuntimeService dna = mock(DnaFormRuntimeService.class);
    DnaInvocationContextFactory contexts = mock(DnaInvocationContextFactory.class);
    when(contexts.current()).thenReturn(mock(DnaInvocationContext.class));
    when(dna.getModel(any(), any())).thenReturn(new PhysicalModelView(
        "model-a", "app-a", "form-a", "Orders", PhysicalModelStatus.ACTIVE, 2, 2, 7, null,
        Instant.EPOCH, Instant.EPOCH));
    when(dna.projectModelResource(any(), any())).thenThrow(
        new DnaRuntimeException(DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED, "superseded"));

    assertThatThrownBy(() -> new FormDnaResourceBindingCoordinator(dna, contexts).bind(work(), resource()))
        .isInstanceOfSatisfying(ApplicationFormException.class, failure -> assertThat(failure.errorCode())
            .isEqualTo(ApplicationFormErrorCode.PAGE_BINDING_SUPERSEDED));
  }

  @Test
  void bindsThePreparedPageToTheVerifiedActiveModel() {
    DnaFormRuntimeService dna = mock(DnaFormRuntimeService.class);
    DnaInvocationContextFactory contexts = mock(DnaInvocationContextFactory.class);
    DnaInvocationContext context = mock(DnaInvocationContext.class);
    when(contexts.current()).thenReturn(context);
    when(dna.getModel(context, "model-a")).thenReturn(new PhysicalModelView(
        "model-a",
        "app-a",
        "form-a",
        "Orders",
        PhysicalModelStatus.ACTIVE,
        2,
        2,
        7,
        null,
        Instant.EPOCH,
        Instant.EPOCH
    ));
    when(dna.projectModelResource(
        org.mockito.ArgumentMatchers.eq(context),
        org.mockito.ArgumentMatchers.any()
    )).thenReturn(new PhysicalModelResourceBindingView(
        "binding-a",
        "model-a",
        "app.runtime.page.a",
        Instant.EPOCH
    ));
    var coordinator = new FormDnaResourceBindingCoordinator(dna, contexts);

    var result = coordinator.bind(work(), resource());

    assertThat(result.resourceCode()).isEqualTo("app.runtime.page.a");
    ArgumentCaptor<PhysicalModelResourceProjectionCommand> command =
        ArgumentCaptor.forClass(PhysicalModelResourceProjectionCommand.class);
    verify(dna).projectModelResource(org.mockito.ArgumentMatchers.eq(context), command.capture());
    assertThat(command.getValue())
        .extracting(
            PhysicalModelResourceProjectionCommand::physicalModelId,
            PhysicalModelResourceProjectionCommand::resourceCode,
            PhysicalModelResourceProjectionCommand::ownerKey,
            PhysicalModelResourceProjectionCommand::generation
        )
        .containsExactly("model-a", "app.runtime.page.a", "app:page:page-a", 1L);
  }

  private static BindingWork work() {
    return new BindingWork(
        "binding-a",
        "token-a",
        "tenant-a",
        "app-a",
        "page-a",
        "form-a",
        2,
        "model-a",
        "page:page-a",
        "Orders",
        null,
        0, 1
    );
  }

  private static PreparedPageResource resource() {
    return new PreparedPageResource(
        "tenant-a",
        "page:page-a",
        "resource-a",
        "app.runtime.page.a",
        "/app/runtime/app-a/page-a",
        false
    );
  }
}
