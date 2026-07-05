package org.simplepoint.plugin.oidc.api.pojo.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Data;

/**
 * Structured OAuth2 client configuration used by management UIs.
 */
@Data
public class OidcClientConfigurationDto implements Serializable {

  private String id;

  private String clientId;

  private Instant clientIdIssuedAt;

  private String clientName;

  private String clientSecret;

  private Instant clientSecretExpiresAt;

  private Set<String> clientAuthenticationMethods = new LinkedHashSet<>();

  private Set<String> authorizationGrantTypes = new LinkedHashSet<>();

  private Set<String> redirectUris = new LinkedHashSet<>();

  private Set<String> postLogoutRedirectUris = new LinkedHashSet<>();

  private Set<String> scopes = new LinkedHashSet<>();

  private Boolean requireProofKey = true;

  private Boolean requireAuthorizationConsent = true;

  private String jwkSetUrl;

  private String tokenEndpointAuthenticationSigningAlgorithm = "PS256";

  private Boolean reuseRefreshTokens = true;

  private Boolean x509CertificateBoundAccessTokens = false;

  private String idTokenSignatureAlgorithm = "PS256";

  private String accessTokenFormat = "self-contained";

  private Long accessTokenTtlSeconds = 1800L;

  private Long refreshTokenTtlSeconds = 28800L;

  private Long authorizationCodeTtlSeconds = 300L;

  private Long deviceCodeTtlSeconds = 300L;
}
