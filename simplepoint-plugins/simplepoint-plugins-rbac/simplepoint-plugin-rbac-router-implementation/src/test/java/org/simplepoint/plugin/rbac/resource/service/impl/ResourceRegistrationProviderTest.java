package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.resource.service.properties.ResourceRegistrationProperties;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceRegistrationCallerPolicy;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.resource.ResourceRegistrationException;
import org.simplepoint.security.service.ResourceService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

class ResourceRegistrationProviderTest {
  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void verifiedClientIdentityIsPassedToTheTransactionalDomainWithoutAnOwnerClaimFromInput() {
    var resources = mock(ResourceService.class);
    var provider =
        new ResourceRegistrationProvider(
            resources, new ResourceRegistrationCallerPolicy(new ResourceRegistrationProperties()));
    authenticate("client_credentials", "deployment-client", "service-router.invoke");
    var declaration = new ResourceDeclaration();
    declaration.setCode("workflow.view");
    provider.sync("workflow", Set.of(declaration));
    verify(resources).syncRegistered("workflow", Set.of(declaration), "deployment-client");
  }

  @Test
  void usersDisplayNamesAndMissingScopeCannotReachDomainWrites() {
    var resources = mock(ResourceService.class);
    var provider =
        new ResourceRegistrationProvider(
            resources, new ResourceRegistrationCallerPolicy(properties()));
    assertDenied(() -> provider.sync("workflow", Set.of()));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken("workflow-client", null, "SCOPE_service-router.invoke"));
    assertDenied(() -> provider.sync("workflow", Set.of()));
    authenticate("authorization_code", "workflow-client", "service-router.invoke");
    assertDenied(() -> provider.sync("workflow", Set.of()));
    authenticate("client_credentials", "workflow-client", "openid");
    assertDenied(() -> provider.sync("workflow", Set.of()));
    verifyNoInteractions(resources);
  }

  @Test
  void exactBoundPolicyWorksAndMissingOrWildcardPoliciesNeverGrantAccess() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(ResourceRegistrationProperties.class)
        .withBean(ResourceRegistrationCallerPolicy.class)
        .withPropertyValues(
            "simplepoint.rbac.resource-registration.owner-clients[workflow]=workflow-client")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              authenticate("client_credentials", "workflow-client", "service-router.invoke");
              context.getBean(ResourceRegistrationCallerPolicy.class).requireCaller("workflow");
              assertDenied(
                  () ->
                      context
                          .getBean(ResourceRegistrationCallerPolicy.class)
                          .requireCaller("common"));
            });
    authenticate("client_credentials", "workflow-client", "service-router.invoke");
    assertDenied(
        () ->
            new ResourceRegistrationCallerPolicy(new ResourceRegistrationProperties())
                .requireCaller("workflow"));
    for (String invalid : List.of("*", " client", "", "\nclient")) {
      var properties = new ResourceRegistrationProperties();
      properties.setOwnerClients(Map.of("workflow", invalid));
      assertThatThrownBy(() -> new ResourceRegistrationCallerPolicy(properties))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static ResourceRegistrationProperties properties() {
    var properties = new ResourceRegistrationProperties();
    properties.setOwnerClients(Map.of("workflow", "workflow-client"));
    return properties;
  }

  private static void authenticate(String grant, String client, String scope) {
    List<org.springframework.security.core.GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("SCOPE_" + scope));
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            "display-name", Map.of("grant_type", grant, "client_id", client), authorities);
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(principal, null, authorities));
  }

  private static void assertDenied(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ResourceRegistrationException.class,
            exception ->
                assertThat(exception.code())
                    .isEqualTo(ResourceRegistrationException.Code.ACCESS_DENIED));
  }
}
