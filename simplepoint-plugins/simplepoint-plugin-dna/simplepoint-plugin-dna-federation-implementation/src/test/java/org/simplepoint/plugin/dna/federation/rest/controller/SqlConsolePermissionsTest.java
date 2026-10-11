package org.simplepoint.plugin.dna.federation.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.federation.api.service.FederationSqlConsoleService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class SqlConsolePermissionsTest {
  private final FederationSqlConsoleController controller = new FederationSqlConsoleController(
      mock(FederationSqlConsoleService.class), new ObjectMapper());

  @Test
  void viewingTheConsoleDoesNotPermitExecutionOrExplanation() {
    assertThat(permissions("dna.federation.sql-console.view"))
        .containsEntry("canExecute", false).containsEntry("canExplain", false);
  }

  @Test
  void executionAndExplanationRemainIndependent() {
    assertThat(permissions("dna.federation.sql-console.execute"))
        .containsEntry("canExecute", true).containsEntry("canExplain", false);
    assertThat(permissions("dna.federation.sql-console.explain"))
        .containsEntry("canExecute", false).containsEntry("canExplain", true);
  }

  @Test
  void administratorsHaveBothActionsAndAnonymousHasNeither() {
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
