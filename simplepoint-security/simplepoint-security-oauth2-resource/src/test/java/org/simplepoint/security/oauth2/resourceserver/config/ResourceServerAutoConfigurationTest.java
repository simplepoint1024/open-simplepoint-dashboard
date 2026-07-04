package org.simplepoint.security.oauth2.resourceserver.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ResourceServerAutoConfigurationTest {

  @Test
  void serviceRouterInternalMatcherAcceptsConfiguredPostPathAndToken() {
    final ResourceServerAutoConfiguration.ServiceRouterInternalRequestMatcher matcher =
        new ResourceServerAutoConfiguration.ServiceRouterInternalRequestMatcher(
            "/_simplepoint/service-router/invoke",
            "X-SimplePoint-Service-Router-Token",
            "secret-token"
        );
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/_simplepoint/service-router/invoke");
    request.addHeader("X-SimplePoint-Service-Router-Token", "secret-token");

    assertThat(matcher.matches(request)).isTrue();
  }

  @Test
  void serviceRouterInternalMatcherRejectsWrongMethodOrToken() {
    ResourceServerAutoConfiguration.ServiceRouterInternalRequestMatcher matcher =
        new ResourceServerAutoConfiguration.ServiceRouterInternalRequestMatcher(
            "/_simplepoint/service-router/invoke",
            "X-SimplePoint-Service-Router-Token",
            "secret-token"
        );
    MockHttpServletRequest wrongMethod = new MockHttpServletRequest("GET", "/_simplepoint/service-router/invoke");
    wrongMethod.addHeader("X-SimplePoint-Service-Router-Token", "secret-token");
    MockHttpServletRequest wrongToken = new MockHttpServletRequest("POST", "/_simplepoint/service-router/invoke");
    wrongToken.addHeader("X-SimplePoint-Service-Router-Token", "other-token");

    assertThat(matcher.matches(wrongMethod)).isFalse();
    assertThat(matcher.matches(wrongToken)).isFalse();
  }

  @Test
  void serviceRouterInternalMatcherMatchesContextPathRequestUri() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/_simplepoint/service-router/invoke");
    request.setContextPath("/api");
    request.setServletPath("/_simplepoint/service-router/invoke");
    request.addHeader("X-SimplePoint-Service-Router-Token", "secret-token");
    ResourceServerAutoConfiguration.ServiceRouterInternalRequestMatcher matcher =
        new ResourceServerAutoConfiguration.ServiceRouterInternalRequestMatcher(
            "/_simplepoint/service-router/invoke",
            "X-SimplePoint-Service-Router-Token",
            "secret-token"
        );

    assertThat(matcher.matches(request)).isTrue();
  }
}
