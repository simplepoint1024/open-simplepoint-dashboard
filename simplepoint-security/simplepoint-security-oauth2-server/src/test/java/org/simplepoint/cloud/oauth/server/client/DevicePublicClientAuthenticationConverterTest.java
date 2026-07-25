package org.simplepoint.cloud.oauth.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

class DevicePublicClientAuthenticationConverterTest {

  private DevicePublicClientAuthenticationConverter converter;

  @BeforeEach
  void setUp() {
    converter = new DevicePublicClientAuthenticationConverter(
        AuthorizationServerSettings.builder().build()
    );
  }

  @Test
  void authenticatesPublicClientAtDeviceAuthorizationEndpoint() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/oauth2/device_authorization");
    request.addParameter("client_id", "device-client");

    OAuth2ClientAuthenticationToken result =
        (OAuth2ClientAuthenticationToken) converter.convert(request);

    assertThat(result.getPrincipal()).isEqualTo("device-client");
    assertThat(result.getClientAuthenticationMethod())
        .isEqualTo(ClientAuthenticationMethod.NONE);
  }

  @Test
  void authenticatesDeviceCodeGrantAtTokenEndpoint() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/oauth2/token");
    request.addParameter("client_id", "device-client");
    request.addParameter(
        "grant_type",
        AuthorizationGrantType.DEVICE_CODE.getValue()
    );

    assertThat(converter.convert(request))
        .isInstanceOf(OAuth2ClientAuthenticationToken.class);
  }

  @Test
  void authenticatesRefreshGrantForPublicDeviceClient() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/oauth2/token");
    request.addParameter("client_id", "device-client");
    request.addParameter(
        "grant_type",
        AuthorizationGrantType.REFRESH_TOKEN.getValue()
    );

    assertThat(converter.convert(request))
        .isInstanceOf(OAuth2ClientAuthenticationToken.class);
  }

  @Test
  void ignoresConfidentialRefreshGrantUsingBasicAuthentication() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/oauth2/token");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Basic encoded-credentials");
    request.addParameter(
        "grant_type",
        AuthorizationGrantType.REFRESH_TOKEN.getValue()
    );

    assertThat(converter.convert(request)).isNull();
  }

  @Test
  void ignoresConfidentialRefreshGrantUsingPostAuthentication() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/oauth2/token");
    request.addParameter("client_id", "browser-client");
    request.addParameter("client_secret", "secret");
    request.addParameter(
        "grant_type",
        AuthorizationGrantType.REFRESH_TOKEN.getValue()
    );

    assertThat(converter.convert(request)).isNull();
  }

  @Test
  void ignoresRefreshGrantWithoutPublicClientId() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/oauth2/token");
    request.addParameter(
        "grant_type",
        AuthorizationGrantType.REFRESH_TOKEN.getValue()
    );

    assertThat(converter.convert(request)).isNull();
  }

  @Test
  void ignoresOtherTokenGrantsAndRejectsAmbiguousClientId() {
    MockHttpServletRequest authorizationCodeRequest =
        new MockHttpServletRequest("POST", "/oauth2/token");
    authorizationCodeRequest.addParameter("client_id", "browser-client");
    authorizationCodeRequest.addParameter("grant_type", "authorization_code");
    assertThat(converter.convert(authorizationCodeRequest)).isNull();

    MockHttpServletRequest ambiguous =
        new MockHttpServletRequest("POST", "/oauth2/device_authorization");
    ambiguous.addParameter("client_id", "one", "two");
    assertThatThrownBy(() -> converter.convert(ambiguous))
        .isInstanceOf(OAuth2AuthenticationException.class);
  }
}
