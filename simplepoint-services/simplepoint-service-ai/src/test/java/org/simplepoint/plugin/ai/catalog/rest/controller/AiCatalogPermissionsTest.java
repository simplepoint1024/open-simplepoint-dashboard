package org.simplepoint.plugin.ai.catalog.rest.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.catalog.api.service.AiCatalogService;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AiCatalogPermissionsTest {
  final AiCatalogService service = mock(AiCatalogService.class);
  final AiScopeAccessPolicy policy = mock(AiScopeAccessPolicy.class);
  final AiCatalogController controller = new AiCatalogController(service, policy);

  @SuppressWarnings("unchecked")
  Map<String, Boolean> permissions(String... authorities) {
    var authentication = new UsernamePasswordAuthenticationToken("actor", null,
        Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    return (Map<String, Boolean>) controller.workbenchPermissions(authentication).getBody();
  }

  @Test
  void viewImportAndSyncAuthoritiesRemainIndependent() {
    when(policy.currentManagementScope()).thenReturn(new AiScopeAccessPolicy.ScopeAssignment(AiResourceScope.SYSTEM, null));
    assertThat(permissions("ai.workbench.catalog.view")).allSatisfy((key, value) -> assertThat(value).isFalse());
    assertThat(permissions("ai.workbench.catalog.import")).containsEntry("importRemote", true)
        .containsEntry("importRuntime", true).containsEntry("sync", false);
    assertThat(permissions("ai.workbench.catalog.sync")).containsEntry("sync", true).containsEntry("importRemote", false);
    assertThat(permissions("ROLE_Administrator")).allSatisfy((key, value) -> assertThat(value).isTrue());
    assertThat((Map<?, ?>) controller.workbenchPermissions(null).getBody()).allSatisfy((key, value) -> assertThat(value).isEqualTo(false));
  }

  @Test
  void tenantAndUnsupportedScopesCannotAcquirePlatformSynchronization() {
    when(policy.currentManagementScope()).thenReturn(new AiScopeAccessPolicy.ScopeAssignment(AiResourceScope.TENANT, "t1"));
    assertThat(permissions("ROLE_Administrator")).containsEntry("sync", false).containsEntry("importRemote", true);
    when(policy.currentManagementScope()).thenThrow(new AccessDeniedException("Unsupported scope"));
    assertThat(permissions("ROLE_Administrator")).allSatisfy((key, value) -> assertThat(value).isFalse());
  }

  @Test
  void synchronizationAuthorizationFailuresCannotBeDisguisedAsSuccessfulHttpResults() {
    when(service.syncOfficialRegistry()).thenThrow(new AccessDeniedException("Platform only"));
    assertThatThrownBy(controller::sync).isInstanceOf(AccessDeniedException.class);
  }
}
