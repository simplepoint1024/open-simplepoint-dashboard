package org.simplepoint.security.oauth2.resourceserver.delegate;

import org.simplepoint.core.AuthorizationGrantedAuthorityLoader;
import org.simplepoint.security.token.TokenRevocationService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.util.StringUtils;

/**
 * A delegate converter that adds a custom role to the authorities of the JWT authentication token.
 */
public class JwtAuthenticationConverterDelegate implements Converter<Jwt, AbstractAuthenticationToken> {
  /**
   * Delegate JwtAuthenticationConverter to handle the initial conversion.
   */
  private final JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();

  private final TokenRevocationService tokenRevocationService;

  private final String requiredAudience;

  /**
   * Constructs a JwtAuthenticationConverterDelegate with the specified custom authorities converter.
   *
   * @param authorizationGrantedAuthorityLoader a function that converts JWT claims into a collection of granted authorities
   */
  public JwtAuthenticationConverterDelegate(
      AuthorizationGrantedAuthorityLoader authorizationGrantedAuthorityLoader,
      TokenRevocationService tokenRevocationService,
      String requiredAudience
  ) {
    this.tokenRevocationService = tokenRevocationService;
    this.requiredAudience = requiredAudience;
    delegate.setJwtGrantedAuthoritiesConverter(new JwtGrantedAuthoritiesConverterDelegate(authorizationGrantedAuthorityLoader));
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt source) {
    validateAudience(source);
    validateNotRevoked(source);
    return this.delegate.convert(source);
  }

  private void validateAudience(Jwt source) {
    if (!StringUtils.hasText(requiredAudience) || source.getAudience().contains(requiredAudience)) {
      return;
    }
    throw invalidToken("JWT audience does not contain required audience '%s'.".formatted(requiredAudience));
  }

  private void validateNotRevoked(Jwt source) {
    String tokenId = source.getClaimAsString(JwtClaimNames.JTI);
    if (StringUtils.hasText(tokenId) && tokenRevocationService.isRevoked(tokenId)) {
      throw invalidToken("JWT has been revoked.");
    }
  }

  private static OAuth2AuthenticationException invalidToken(String description) {
    return new OAuth2AuthenticationException(new OAuth2Error("invalid_token", description, null));
  }

  @Override
  public <U> Converter<Jwt, U> andThen(Converter<? super AbstractAuthenticationToken, ? extends U> after) {
    return this.delegate.andThen(after);
  }
}
