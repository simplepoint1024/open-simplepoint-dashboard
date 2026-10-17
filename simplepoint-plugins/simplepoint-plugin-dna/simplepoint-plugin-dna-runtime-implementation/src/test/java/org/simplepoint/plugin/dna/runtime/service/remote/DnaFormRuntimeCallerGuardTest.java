package org.simplepoint.plugin.dna.runtime.service.remote;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.configuration.DnaFormRuntimeRemoteProperties;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class DnaFormRuntimeCallerGuardTest {

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void acceptsOnlyConfiguredServicePrincipals() {
    DnaFormRuntimeRemoteProperties properties = new DnaFormRuntimeRemoteProperties();
    properties.setAllowedCallers(Set.of("simplepoint-service-app"));
    DnaFormRuntimeCallerGuard guard = new DnaFormRuntimeCallerGuard(properties);
    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken(
            principal("simplepoint-service-app"),
            null,
            "SCOPE_service-router.invoke"
        )
    );

    assertThatCode(guard::requireTrustedCaller).doesNotThrowAnyException();

    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken(
            principal("simplepoint-service-workflow"),
            null,
            "SCOPE_service-router.invoke"
        )
    );
    assertThatThrownBy(guard::requireTrustedCaller)
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void failsClosedWhenTheAllowlistIsEmpty() {
    DnaFormRuntimeCallerGuard guard = new DnaFormRuntimeCallerGuard(
        new DnaFormRuntimeRemoteProperties()
    );
    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken(
            principal("simplepoint-service-app"),
            null,
            "SCOPE_service-router.invoke"
        )
    );

    assertThatThrownBy(guard::requireTrustedCaller)
        .isInstanceOf(AccessDeniedException.class);
  }
  private Object principal(String client) {
    return new org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal(
        client, java.util.Map.of("client_id", client, "grant_type", "client_credentials"), java.util.List.of());
  }
}
