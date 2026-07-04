package org.simplepoint.cloud.oauth.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthorizationServerConfigurationTest {

  @Test
  void serviceRouterPathMatcherMatchesOnlyConfiguredPostPath() {
    AuthorizationServerConfiguration.ServiceRouterPathRequestMatcher matcher =
        new AuthorizationServerConfiguration.ServiceRouterPathRequestMatcher(
            "/_simplepoint/service-router/invoke"
        );
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/_simplepoint/service-router/invoke");
    MockHttpServletRequest wrongMethod = new MockHttpServletRequest("GET", "/_simplepoint/service-router/invoke");
    MockHttpServletRequest wrongPath = new MockHttpServletRequest("POST", "/_simplepoint/other");

    assertThat(matcher.matches(request)).isTrue();
    assertThat(matcher.matches(wrongMethod)).isFalse();
    assertThat(matcher.matches(wrongPath)).isFalse();
  }
}
