package org.simplepoint.security.oauth2.resourceserver.delegate;

import java.util.Collection;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * A delegate converter for extracting granted authorities from a JWT.
 */
public class JwtGrantedAuthoritiesConverterDelegate implements Converter<Jwt, Collection<GrantedAuthority>> {
  private final JwtGrantedAuthoritiesConverter delegate = new JwtGrantedAuthoritiesConverter();

  @Override
  public Collection<GrantedAuthority> convert(Jwt source) {
    Collection<GrantedAuthority> convert = delegate.convert(source);
//    convert.add(new SimpleGrantedAuthority("users.view"));
    //  todo extract more authorities from JWT claims if needed
    return convert;
  }
}
