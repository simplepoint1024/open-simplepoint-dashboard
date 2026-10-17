package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowInvocationContext;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.service.WorkflowCenterService;
import org.simplepoint.plugin.workflow.configuration.WorkflowBusinessRemoteProperties;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class WorkflowBusinessRuntimeServiceTest {
  private final WorkflowCenterService workflow = mock(WorkflowCenterService.class);
  private final WorkflowInvocationContext context = new WorkflowInvocationContext("tenant-a", "member-a", false,
      Set.of("workflow.instances.start"), Set.of("approver"));

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.set(null);
  }

  @Test
  void rejectsUnconfiguredServicesBeforeReadingForgedAdministratorContext() {
    authenticate("other-service");
    var service = service();
    assertThatThrownBy(() -> service.startForm(new WorkflowInvocationContext("tenant-b", "admin", true,
        Set.of(), Set.of()), "definition", 1)).isInstanceOf(WorkflowException.class);
    verifyNoInteractions(workflow);
  }

  @Test
  void carriesTenantActorRolesAndPermissionsWithoutEscalationThenRestoresPriorContext() {
    authenticate("simplepoint-app");
    var previous = new AuthorizationContext();
    previous.setUserId("service");
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, previous);
    ResourcePolicyContext.set("previous-resource");
    when(workflow.startForm("definition", 1)).thenAnswer(ignored -> {
      var effective = AuthorizationContextHolder.getContext();
      assertThat(effective.getScopeType()).isEqualTo(AuthorizationScopeType.TENANT);
      assertThat(effective.getAttribute("X-Tenant-Id")).isEqualTo("tenant-a");
      assertThat(effective.getUserId()).isEqualTo("member-a");
      assertThat(effective.getIsAdministrator()).isFalse();
      assertThat(effective.getResources()).containsExactly("workflow.instances.start");
      assertThat(effective.getRoles()).containsExactly("approver");
      assertThat(ResourcePolicyContext.get()).isNull();
      throw new WorkflowException(WorkflowException.Code.CONFLICT, "expected");
    });
    assertThatThrownBy(() -> service().startForm(context, "definition", 1)).isInstanceOf(WorkflowException.class);
    assertThat(AuthorizationContextHolder.getContext()).isSameAs(previous);
    assertThat(ResourcePolicyContext.get()).isEqualTo("previous-resource");
  }

  @Test
  void requiresStableIdentityBeforeStartingAnAcceptedBusinessOperation() {
    authenticate("simplepoint-app");
    assertThatThrownBy(() -> service().start(context, "definition",
        new StartProcessCommand(null, "reference", "title", Map.of(), List.of(), "key")))
        .isInstanceOf(WorkflowException.class);
    verify(workflow, never()).start(any(), any());
    assertThat(AuthorizationContextHolder.getContext()).isNull();
  }

  @Test
  void trustedSubmissionScopeExistsOnlyDuringVerifiedBusinessStartAndIsClearedOnFailure() {
    authenticate("simplepoint-app");
    var command = new StartProcessCommand(1, "app-form:accepted", "title", Map.of(), List.of(), "stable");
    when(workflow.start("definition", command)).thenAnswer(ignored -> {
      assertThat(WorkflowBusinessSubmissionScope.active()).isTrue();
      throw new WorkflowException(WorkflowException.Code.CONFLICT, "expected");
    });
    assertThat(WorkflowBusinessSubmissionScope.active()).isFalse();
    assertThatThrownBy(() -> service().start(context, "definition", command)).isInstanceOf(WorkflowException.class);
    assertThat(WorkflowBusinessSubmissionScope.active()).isFalse();
    assertThat(AuthorizationContextHolder.getContext()).isNull();
  }

  private WorkflowBusinessRuntimeServiceImpl service() {
    var properties = new WorkflowBusinessRemoteProperties();
    properties.setAllowedCallers(Set.of("simplepoint-app"));
    return new WorkflowBusinessRuntimeServiceImpl(workflow, properties);
  }

  private void authenticate(String client) {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(new org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal(
            client, Map.of("client_id", client, "grant_type", "client_credentials"), List.of()), "unused",
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_service-router.invoke"))));
  }
}
