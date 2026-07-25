package org.simplepoint.cloud.oauth.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

@ExtendWith(MockitoExtension.class)
class DevicePublicClientAuthenticationProviderTest {

  @Mock
  private RegisteredClientRepository registeredClientRepository;

  private DevicePublicClientAuthenticationProvider provider;

  @BeforeEach
  void setUp() {
    provider = new DevicePublicClientAuthenticationProvider(
        registeredClientRepository
    );
  }

  @Test
  void authenticatesRegisteredPublicDeviceClient() {
    RegisteredClient client = RegisteredClient.withId("device-client")
        .clientId("device-client")
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.DEVICE_CODE)
        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
        .scope("profile")
        .build();
    when(registeredClientRepository.findByClientId("device-client"))
        .thenReturn(client);

    OAuth2ClientAuthenticationToken result =
        (OAuth2ClientAuthenticationToken) provider.authenticate(request());

    assertThat(result.isAuthenticated()).isTrue();
    assertThat(result.getRegisteredClient()).isSameAs(client);
  }

  @Test
  void rejectsClientThatIsNotRegisteredForDeviceGrant() {
    RegisteredClient client = RegisteredClient.withId("device-client")
        .clientId("device-client")
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://client.example.com/callback")
        .build();
    when(registeredClientRepository.findByClientId("device-client"))
        .thenReturn(client);

    assertThatThrownBy(() -> provider.authenticate(request()))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .satisfies(exception -> assertThat(
            ((OAuth2AuthenticationException) exception).getError().getErrorCode()
        ).isEqualTo("invalid_client"));
  }

  private OAuth2ClientAuthenticationToken request() {
    return new OAuth2ClientAuthenticationToken(
        "device-client",
        ClientAuthenticationMethod.NONE,
        null,
        Map.of(
            DevicePublicClientAuthenticationConverter.DEVICE_PUBLIC_CLIENT,
            Boolean.TRUE,
            DevicePublicClientAuthenticationConverter.REQUEST_GRANT_TYPE,
            AuthorizationGrantType.DEVICE_CODE.getValue()
        )
    );
  }
}
