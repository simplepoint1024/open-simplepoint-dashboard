package org.simplepoint.plugin.oidc.service.support;

import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderProtocol;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.simplepoint.plugin.oidc.service.security.ExternalIdentityProviderUrlValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Builds Spring Security client registrations from database-managed providers. */
@Component
public class ExternalIdentityClientRegistrationFactory {

  /**
   * Builds a validated runtime client registration.
   *
   * @param provider decrypted provider configuration
   * @return Spring Security client registration
   */
  public ClientRegistration build(final ResolvedExternalIdentityProvider provider) {
    validateDestinations(provider, true);
    ClientRegistration.Builder builder = createBuilder(provider);
    builder.registrationId(provider.registrationId())
        .clientId(provider.clientId())
        .clientSecret(provider.clientSecret())
        .clientAuthenticationMethod(
            new ClientAuthenticationMethod(provider.clientAuthenticationMethod())
        )
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .clientName(provider.displayName())
        .scope(provider.scopes());

    if (provider.protocol() == ExternalIdentityProviderProtocol.OAUTH2) {
      builder.authorizationUri(provider.authorizationUri())
          .tokenUri(provider.tokenUri())
          .userInfoUri(provider.userInfoUri())
          .userNameAttributeName(provider.userNameAttribute());
      if (StringUtils.hasText(provider.jwkSetUri())) {
        builder.jwkSetUri(provider.jwkSetUri());
      }
    } else if (StringUtils.hasText(provider.userNameAttribute())) {
      builder.userNameAttributeName(provider.userNameAttribute());
    }
    return builder.build();
  }

  private ClientRegistration.Builder createBuilder(
      final ResolvedExternalIdentityProvider provider
  ) {
    if (provider.protocol() == ExternalIdentityProviderProtocol.OIDC
        && StringUtils.hasText(provider.issuerUri())) {
      return ClientRegistrations.fromIssuerLocation(provider.issuerUri());
    }
    return ClientRegistration.withRegistrationId(provider.registrationId());
  }

  private static void validateDestinations(
      final ResolvedExternalIdentityProvider provider,
      final boolean resolveHost
  ) {
    boolean allowPrivate = provider.allowPrivateNetwork();
    ExternalIdentityProviderUrlValidator.validate(
        provider.issuerUri(), "Issuer URI", allowPrivate, resolveHost
    );
    ExternalIdentityProviderUrlValidator.validate(
        provider.authorizationUri(), "Authorization URI", allowPrivate, resolveHost
    );
    ExternalIdentityProviderUrlValidator.validate(
        provider.tokenUri(), "Token URI", allowPrivate, resolveHost
    );
    ExternalIdentityProviderUrlValidator.validate(
        provider.userInfoUri(), "UserInfo URI", allowPrivate, resolveHost
    );
    ExternalIdentityProviderUrlValidator.validate(
        provider.jwkSetUri(), "JWK Set URI", allowPrivate, resolveHost
    );
  }
}
