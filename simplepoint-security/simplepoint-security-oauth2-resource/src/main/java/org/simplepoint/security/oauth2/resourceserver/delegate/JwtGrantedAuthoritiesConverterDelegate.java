package org.simplepoint.security.oauth2.resourceserver.delegate;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.core.context.UserContext;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * A delegate converter for extracting granted authorities from a JWT.
 */
public class JwtGrantedAuthoritiesConverterDelegate implements Converter<Jwt, Collection<GrantedAuthority>> {
  private final JwtGrantedAuthoritiesConverter delegate = new JwtGrantedAuthoritiesConverter();

  private final UserContext<BaseUser> userContext;

  /**
   * Constructs a JwtGrantedAuthoritiesConverterDelegate with a custom UserContext.
   *
   * @param userContext the UserContext to be used for authority conversion
   */
  public JwtGrantedAuthoritiesConverterDelegate(UserContext<BaseUser> userContext) {
    this.userContext = userContext;
  }

  /**
   * Converts a JWT into a collection of granted authorities, adding custom permissions from the UserContext.
   *
   * @param source the JWT to convert
   * @return a collection of granted authorities
   */
  @Override
  public Collection<GrantedAuthority> convert(Jwt source) {
    Collection<GrantedAuthority> authorities = delegate.convert(source);
    String subject = source.getSubject();
    Set<String> permissions = userContext.getPermissionsByUsername(subject);
    for (String permission : permissions) {
      authorities.add(new SimpleGrantedAuthority(permission));
    }
    return authorities;
  }
}
