package org.simplepoint.plugin.oidc.service.initialize;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Configuration properties for OAuth2 client initialization.
 */
@Getter
@Configuration
@ConfigurationProperties("spring.security.oauth2.client")
public class Oauth2ClientInitializeProperties implements InitializingBean {

  /**
   * OAuth provider details.
   */
  private final Map<String, Provider> provider = new HashMap<>();

  /**
   * OAuth client registrations.
   */
  private final Map<String, Registration> registration = new HashMap<>();

  @Override
  public void afterPropertiesSet() {
    validate();
  }

  /**
   * Validates the client registrations.
   *
   * @throws IllegalStateException if any registration is invalid
   */
  public void validate() {
    getRegistration().forEach(this::validateRegistration);
  }

  private void validateRegistration(String id, Registration registration) {
    if (!StringUtils.hasText(registration.getClientId())) {
      throw new IllegalStateException("Client id of registration '%s' must not be empty.".formatted(id));
    }
  }

  /**
   * A single client registration.
   */
  public static class Registration {

    /**
     * Reference to the OAuth 2.0 provider to use. May reference an element from the
     * 'provider' property or used one of the commonly used providers (google, github,
     * facebook, okta).
     */
    private @Nullable String provider;

    /**
     * Client ID for the registration.
     */
    private @Nullable String clientId;

    /**
     * Client secret of the registration.
     */
    private @Nullable String clientSecret;

    /**
     * Client authentication method. May be left blank when using a pre-defined
     * provider.
     */
    private @Nullable String clientAuthenticationMethod;

    /**
     * Authorization grant type. May be left blank when using a pre-defined provider.
     */
    private @Nullable String authorizationGrantType;

    /**
     * Redirect URI. May be left blank when using a pre-defined provider.
     */
    private @Nullable String redirectUri;

    /**
     * Authorization scopes. When left blank the provider's default scopes, if any,
     * will be used.
     */
    private @Nullable Set<String> scope;

    /**
     * Client name. May be left blank when using a pre-defined provider.
     */
    private @Nullable String clientName;

    public @Nullable String getProvider() {
      return this.provider;
    }

    public void setProvider(@Nullable String provider) {
      this.provider = provider;
    }

    public @Nullable String getClientId() {
      return this.clientId;
    }

    public void setClientId(@Nullable String clientId) {
      this.clientId = clientId;
    }

    public @Nullable String getClientSecret() {
      return this.clientSecret;
    }

    public void setClientSecret(@Nullable String clientSecret) {
      this.clientSecret = clientSecret;
    }

    public @Nullable String getClientAuthenticationMethod() {
      return this.clientAuthenticationMethod;
    }

    public void setClientAuthenticationMethod(@Nullable String clientAuthenticationMethod) {
      this.clientAuthenticationMethod = clientAuthenticationMethod;
    }

    public @Nullable String getAuthorizationGrantType() {
      return this.authorizationGrantType;
    }

    public void setAuthorizationGrantType(@Nullable String authorizationGrantType) {
      this.authorizationGrantType = authorizationGrantType;
    }

    public @Nullable String getRedirectUri() {
      return this.redirectUri;
    }

    public void setRedirectUri(@Nullable String redirectUri) {
      this.redirectUri = redirectUri;
    }

    public @Nullable Set<String> getScope() {
      return this.scope;
    }

    public void setScope(@Nullable Set<String> scope) {
      this.scope = scope;
    }

    public @Nullable String getClientName() {
      return this.clientName;
    }

    public void setClientName(@Nullable String clientName) {
      this.clientName = clientName;
    }

  }

  /**
   * A single OAuth2 provider configuration.
   */
  public static class Provider {

    /**
     * Authorization URI for the provider.
     */
    private @Nullable String authorizationUri;

    /**
     * JWK set URI for the provider.
     */
    private @Nullable String jwkSetUri;

    public @Nullable String getAuthorizationUri() {
      return this.authorizationUri;
    }

    public void setAuthorizationUri(@Nullable String authorizationUri) {
      this.authorizationUri = authorizationUri;
    }

    public @Nullable String getJwkSetUri() {
      return this.jwkSetUri;
    }

    public void setJwkSetUri(@Nullable String jwkSetUri) {
      this.jwkSetUri = jwkSetUri;
    }

  }
}
