package org.simplepoint.security.oauth2.resourceserver.delegate;

import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.core.context.UserContext;
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
   * Constructs a JwtAuthenticationConverterDelegate with a custom UserContext.
   *
   * @param userContext the UserContext to be used for authority conversion
   */
  public JwtAuthenticationConverterDelegate(UserContext<BaseUser> userContext) {
    delegate.setJwtGrantedAuthoritiesConverter(new JwtGrantedAuthoritiesConverterDelegate(userContext));
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
