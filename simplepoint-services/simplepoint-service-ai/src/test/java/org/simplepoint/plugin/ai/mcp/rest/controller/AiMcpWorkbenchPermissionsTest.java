package org.simplepoint.plugin.ai.mcp.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpServerDefinitionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** UI capabilities must reflect the same distinct authorities required by the operation endpoints. */
class AiMcpWorkbenchPermissionsTest {

  private final AiMcpServerDefinitionController controller =
      new AiMcpServerDefinitionController(mock(AiMcpServerDefinitionService.class));

  @Test
  void readOnlyRuntimeViewNeverGrantsManagementOrDiscovery() {
    assertThat(permissions("ai.workbench.runtime.view"))
        .containsEntry("viewRuntime", true).containsEntry("managePools", false)
        .containsEntry("discoverServer", false);
  }

  @Test
  void deploymentAndDiscoveryPermissionsAreIndependent() {
    assertThat(permissions("ai.workbench.runtime.pools.manage"))
        .containsEntry("managePools", true).containsEntry("discoverServer", false);
    assertThat(permissions("ai.workbench.mcp-servers.discover"))
        .containsEntry("discoverServer", true).containsEntry("managePools", false);
  }

  @Test
  void administratorsHaveBothCapabilitiesAndAnonymousNeverDoes() {
    assertThat(permissions("ROLE_Administrator"))
        .containsEntry("managePools", true).containsEntry("discoverServer", true);
    assertThat((Map<?, ?>) controller.workbenchPermissions(null).getBody())
        .allSatisfy((key, value) -> assertThat(value).isEqualTo(false));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Boolean> permissions(final String... authorities) {
    var authentication = new UsernamePasswordAuthenticationToken("actor", null,
        Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    return (Map<String, Boolean>) controller.workbenchPermissions(authentication).getBody();
  }
}
