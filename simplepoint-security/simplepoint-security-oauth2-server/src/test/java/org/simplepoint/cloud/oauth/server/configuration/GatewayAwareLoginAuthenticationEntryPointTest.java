package org.simplepoint.cloud.oauth.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

class GatewayAwareLoginAuthenticationEntryPointTest {

  private final GatewayAwareLoginAuthenticationEntryPoint entryPoint =
      new GatewayAwareLoginAuthenticationEntryPoint();

  @Test
  void redirectsForwardedGatewayRequestToGatewayLoginWithoutInternalAddress() throws Exception {
    MockHttpServletRequest request = internalRequest();
    request.addHeader("X-Forwarded-Prefix", "/authorization");
    MockHttpServletResponse response = commence(request);

    assertThat(response.getStatus()).isEqualTo(302);
    assertThat(response.getHeader(HttpHeaders.LOCATION))
        .isEqualTo(GatewayAwareLoginAuthenticationEntryPoint.GATEWAY_LOGIN_LOCATION)
        .doesNotContain("10.10.10.10", ":9000");
  }

  @Test
  void gatewayQueryRetainsGatewayLoginSemanticsWhenForwardedPrefixIsUnavailable()
      throws Exception {
    MockHttpServletRequest request = internalRequest();
    request.addParameter("gateway", "true");

    assertThat(commence(request).getHeader(HttpHeaders.LOCATION))
        .isEqualTo(GatewayAwareLoginAuthenticationEntryPoint.GATEWAY_LOGIN_LOCATION);
  }

  @Test
  void directRequestUsesRelativeContextLoginLocation() throws Exception {
    MockHttpServletRequest request = internalRequest();
    request.setContextPath("/oauth");

    assertThat(commence(request).getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/oauth/login");
  }

  private MockHttpServletRequest internalRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET",
        "/account/external-identities"
    );
    request.setScheme("http");
    request.setServerName("10.10.10.10");
    request.setServerPort(9000);
    return request;
  }

  private MockHttpServletResponse commence(final MockHttpServletRequest request)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    entryPoint.commence(
        request,
        response,
        new InsufficientAuthenticationException("Authentication is required")
    );
    return response;
  }
}
