package org.simplepoint.security.oauth2.resourceserver.delegate;

import org.simplepoint.core.AuthorizationGrantedAuthorityLoader;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * A delegate converter that adds a custom role to the authorities of the JWT authentication token.
 */
public class JwtAuthenticationConverterDelegate implements Converter<Jwt, AbstractAuthenticationToken> {
  /**
   * Delegate JwtAuthenticationConverter to handle the initial conversion.
   */
  private final JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();

  /**
   * Constructs a JwtAuthenticationConverterDelegate with the specified custom authorities converter.
   *
   * @param authorizationGrantedAuthorityLoader a function that converts JWT claims into a collection of granted authorities
   */
  public JwtAuthenticationConverterDelegate(
      AuthorizationGrantedAuthorityLoader authorizationGrantedAuthorityLoader
  ) {
    delegate.setJwtGrantedAuthoritiesConverter(new JwtGrantedAuthoritiesConverterDelegate(authorizationGrantedAuthorityLoader));
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt source) {
    return this.delegate.convert(source);
  }

  @Override
  public <U> Converter<Jwt, U> andThen(Converter<? super AbstractAuthenticationToken, ? extends U> after) {
    return this.delegate.andThen(after);
  }
}
