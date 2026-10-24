package org.simplepoint.plugin.app.form.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingClaim;

class FormPublicationRecoveryTest {
  private final FormPublicationRecoveryState state = mock(FormPublicationRecoveryState.class);
  private final FormPublicationRecoveryAuthority authority = mock(FormPublicationRecoveryAuthority.class);
  private final ApplicationFormPageBindingStateService bindings = mock(ApplicationFormPageBindingStateService.class);
  private final ApplicationFormPublicationServiceImpl publications = mock(ApplicationFormPublicationServiceImpl.class);
  private final FormWritePolicyPublicationStateService policies = mock(FormWritePolicyPublicationStateService.class);
  private final FormPublicationRecovery worker = new FormPublicationRecovery(state, authority, bindings, publications, policies);
  private final FormPublicationRecoveryState.Candidate candidate =
      new FormPublicationRecoveryState.Candidate("policy-a", 1, "tenant-a", "app-a", "form-a", "binding-a", "actor-a", "role-a", 0);

  @Test
  void authorityPrecedesClaimAndClaimPrecedesOneBoundedDomainStep() {
    var scope = mock(FormPublicationRecoveryAuthority.Scope.class);
    var claim = new BindingClaim(true, null, null);
    when(state.due(5)).thenReturn(List.of(candidate));
    when(authority.open("tenant-a", "actor-a", "role-a")).thenReturn(scope);
    when(bindings.claimPolicyRecovery("app-a", "form-a", "binding-a", candidate)).thenReturn(claim);
    worker.recover();
    var order = inOrder(authority, bindings, publications, scope);
    order.verify(authority).open("tenant-a", "actor-a", "role-a");
    order.verify(bindings).claimPolicyRecovery("app-a", "form-a", "binding-a", candidate);
    order.verify(publications).recoverPolicy("app-a", "form-a", claim, false);
    order.verify(scope).close();
    verify(state, never()).defer(any(), anyBoolean());
  }

  @Test
  void aStaleClaimDoesNoRemoteWorkAndClosesTheAuthorizationScope() {
    var scope = mock(FormPublicationRecoveryAuthority.Scope.class);
    when(state.due(5)).thenReturn(List.of(candidate));
    when(authority.open(any(), any(), any())).thenReturn(scope);
    when(bindings.claimPolicyRecovery(any(), any(), any(), any())).thenReturn(new BindingClaim(false, null, null));
    worker.recover();
    verifyNoInteractions(publications);
    verify(scope).close();
  }

  @Test
  void revokedAuthoritySuspendsWithoutClaimAndTransientFailuresUseBackoff() {
    when(state.due(5)).thenReturn(List.of(candidate));
    when(authority.open(any(), any(), any())).thenThrow(new ApplicationFormException(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "revoked"));
    worker.recover();
    verify(state).defer(candidate, true);
    verifyNoInteractions(bindings, publications);
    doThrow(new IllegalStateException("temporary RPC failure")).when(authority).open(any(), any(), any());
    worker.recover();
    verify(state).defer(candidate, false);
  }
}
