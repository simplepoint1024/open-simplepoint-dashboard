package org.simplepoint.security.oauth2.resourceserver.delegate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.simplepoint.core.AuthorizationGrantedAuthorityLoader;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * A delegate converter for extracting granted authorities from a JWT.
 */
public class JwtGrantedAuthoritiesConverterDelegate implements Converter<Jwt, Collection<GrantedAuthority>> {
  private final JwtGrantedAuthoritiesConverter delegate = new JwtGrantedAuthoritiesConverter();

  private final AuthorizationGrantedAuthorityLoader authorizationGrantedAuthorityLoader;

  /**
   * Constructs a JwtGrantedAuthoritiesConverterDelegate with the specified custom authorities converter.
   *
   * @param authorizationGrantedAuthorityLoader a function that converts JWT claims into a collection of granted authorities
   */
  public JwtGrantedAuthoritiesConverterDelegate(
      AuthorizationGrantedAuthorityLoader authorizationGrantedAuthorityLoader) {
    this.authorizationGrantedAuthorityLoader = authorizationGrantedAuthorityLoader;
  }

  /**
   * Converts a JWT into a collection of granted authorities, adding custom permissions from the UserContext.
   *
   * @param source the JWT to convert
   * @return a collection of granted authorities
   */
  @Override
  public Collection<GrantedAuthority> convert(Jwt source) {
    Collection<GrantedAuthority> authorities = new ArrayList<>(delegate.convert(source));
    Map<String, Object> claims = source.getClaims();
    if (this.authorizationGrantedAuthorityLoader != null) {
      authorities.addAll(authorizationGrantedAuthorityLoader.load(claims));
    }
    return authorities;
  }
}
