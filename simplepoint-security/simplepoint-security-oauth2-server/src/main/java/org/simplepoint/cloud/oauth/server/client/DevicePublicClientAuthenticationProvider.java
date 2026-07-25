package org.simplepoint.cloud.oauth.server.client;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Validates public device clients without applying authorization-code PKCE rules.
 */
public final class DevicePublicClientAuthenticationProvider
    implements AuthenticationProvider {

  private final RegisteredClientRepository registeredClientRepository;

  /**
   * Creates the public device-client provider.
   *
   * @param registeredClientRepository OAuth2 client repository
   */
  public DevicePublicClientAuthenticationProvider(
      final RegisteredClientRepository registeredClientRepository
  ) {
    this.registeredClientRepository = registeredClientRepository;
  }

  @Override
  public Authentication authenticate(final Authentication authentication)
      throws AuthenticationException {
    OAuth2ClientAuthenticationToken clientAuthentication =
        (OAuth2ClientAuthenticationToken) authentication;
    if (!ClientAuthenticationMethod.NONE.equals(
        clientAuthentication.getClientAuthenticationMethod()
    ) || !Boolean.TRUE.equals(clientAuthentication.getAdditionalParameters().get(
        DevicePublicClientAuthenticationConverter.DEVICE_PUBLIC_CLIENT
    ))) {
      return null;
    }

    String clientId = String.valueOf(clientAuthentication.getPrincipal());
    String requestGrantType = String.valueOf(
        clientAuthentication.getAdditionalParameters().get(
            DevicePublicClientAuthenticationConverter.REQUEST_GRANT_TYPE
        )
    );
    RegisteredClient registeredClient =
        registeredClientRepository.findByClientId(clientId);
    if (registeredClient == null
        || !registeredClient.getClientAuthenticationMethods().contains(
            ClientAuthenticationMethod.NONE
        )
        || !registeredClient.getAuthorizationGrantTypes().contains(
            AuthorizationGrantType.DEVICE_CODE
        )
        || (AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(
            requestGrantType
        ) && !registeredClient.getAuthorizationGrantTypes().contains(
            AuthorizationGrantType.REFRESH_TOKEN
        ))) {
      throw invalidClient();
    }
    return new OAuth2ClientAuthenticationToken(
        registeredClient,
        ClientAuthenticationMethod.NONE,
        null
    );
  }

  @Override
  public boolean supports(final Class<?> authentication) {
    return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
  }

  private static OAuth2AuthenticationException invalidClient() {
    return new OAuth2AuthenticationException(
        new OAuth2Error(
            "invalid_client",
            "Public client is not registered for the device authorization grant",
            null
        )
    );
  }
}
