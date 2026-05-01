package org.simplepoint.cloud.oauth.server.handler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.simplepoint.security.token.TokenRevocationService;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.oidc.web.authentication.OidcLogoutAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.util.StringUtils;

/**
 * Revokes tokens belonging to the OIDC session before completing RP-initiated logout.
 */
public class RevokingOidcLogoutSuccessHandler implements AuthenticationSuccessHandler {

  private final OidcLogoutAuthenticationSuccessHandler delegate =
      new OidcLogoutAuthenticationSuccessHandler();

  private final OAuth2AuthorizationService authorizationService;

  private final TokenRevocationService tokenRevocationService;

  public RevokingOidcLogoutSuccessHandler(
      final OAuth2AuthorizationService authorizationService,
      final TokenRevocationService tokenRevocationService
  ) {
    this.authorizationService = authorizationService;
    this.tokenRevocationService = tokenRevocationService;
  }

  @Override
  public void onAuthenticationSuccess(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Authentication authentication
  ) throws IOException, ServletException {
    revokeAuthorizationTokens(authentication);
    delegate.onAuthenticationSuccess(request, response, authentication);
  }

  private void revokeAuthorizationTokens(final Authentication authentication) {
    if (!(authentication instanceof OidcLogoutAuthenticationToken logoutAuthentication)
        || logoutAuthentication.getIdToken() == null) {
      return;
    }

    OAuth2Authorization authorization = authorizationService.findByToken(
        logoutAuthentication.getIdToken().getTokenValue(),
        new OAuth2TokenType(OidcParameterNames.ID_TOKEN)
    );
    if (authorization == null) {
      return;
    }

    OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
    if (accessToken != null) {
      revokeAccessToken(accessToken);
    }

    OAuth2Authorization.Builder builder = OAuth2Authorization.from(authorization);
    invalidate(builder, accessToken);
    invalidate(builder, authorization.getRefreshToken());
    authorizationService.save(builder.build());
  }

  private void revokeAccessToken(final OAuth2Authorization.Token<OAuth2AccessToken> accessToken) {
    Map<String, Object> claims = accessToken.getClaims();
    Object tokenId = claims.get(JwtClaimNames.JTI);
    if (tokenId instanceof String value && StringUtils.hasText(value)) {
      tokenRevocationService.revoke(value, accessToken.getToken().getExpiresAt());
    }
  }

  private static <T extends OAuth2Token> void invalidate(
      final OAuth2Authorization.Builder builder,
      final OAuth2Authorization.Token<T> token
  ) {
    if (token != null) {
      builder.invalidate(token.getToken());
    }
  }
}
