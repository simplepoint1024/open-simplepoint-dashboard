package org.simplepoint.plugin.dna.core.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class JdbcDialectPermissionsTest {
  private final JdbcDialectManagementController controller =
      new JdbcDialectManagementController(mock(JdbcDialectManagementService.class));

  @Test
  void viewingDialectsDoesNotGrantAnyWriteAction() {
    assertThat(permissions("dna.dialects.view"))
        .containsEntry("canCreateUrl", false).containsEntry("canUpload", false).containsEntry("canDelete", false);
  }

  @Test
  void independentOperationPermissionsRemainIndependent() {
    assertThat(permissions("dna.dialects.upload"))
        .containsEntry("canCreateUrl", false).containsEntry("canUpload", true).containsEntry("canDelete", false);
    assertThat(permissions("dna.dialects.create", "dna.dialects.delete"))
        .containsEntry("canCreateUrl", true).containsEntry("canUpload", false).containsEntry("canDelete", true);
  }

  @Test
  void administratorsHaveAllCapabilitiesAndAnonymousHasNone() {
    assertThat(permissions("ROLE_Administrator")).allSatisfy((key, value) -> assertThat(value).isTrue());
    assertThat((Map<?, ?>) controller.workbenchPermissions(null).getBody())
        .allSatisfy((key, value) -> assertThat(value).isEqualTo(false));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Boolean> permissions(final String... authorities) {
    return (Map<String, Boolean>) controller.workbenchPermissions(new UsernamePasswordAuthenticationToken("actor", null,
        Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList())).getBody();
  }
}
