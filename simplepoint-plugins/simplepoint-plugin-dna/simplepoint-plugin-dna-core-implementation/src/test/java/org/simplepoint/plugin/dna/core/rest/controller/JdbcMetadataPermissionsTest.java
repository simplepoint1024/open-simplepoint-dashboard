package org.simplepoint.plugin.dna.core.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.core.api.service.JdbcMetadataManagementService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class JdbcMetadataPermissionsTest {
  private final JdbcMetadataManagementController controller =
      new JdbcMetadataManagementController(mock(JdbcMetadataManagementService.class));

  @Test
  void metadataViewDoesNotGrantPreviewOrDdl() {
    assertThat(permissions("dna.metadata.view")).containsEntry("view", true)
        .allSatisfy((key, value) -> assertThat(value).isEqualTo(key.equals("view")));
  }

  @Test
  void everyActionUsesOnlyItsOwnAuthority() {
    Map.of("preview", "preview", "createNamespace", "create-namespace", "createTable", "create-table",
        "createView", "create-view", "alterColumn", "alter-column", "dropColumn", "drop-column",
        "addConstraint", "add-constraint", "dropConstraint", "drop-constraint", "drop", "drop")
        .forEach((action, suffix) -> assertThat(permissions("dna.metadata." + suffix))
            .allSatisfy((key, value) -> assertThat(value).isEqualTo(key.equals(action))));
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
