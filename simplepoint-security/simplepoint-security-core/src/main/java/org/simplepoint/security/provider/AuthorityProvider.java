package org.simplepoint.security.provider;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * AuthorityProvider is an interface that defines a method for retrieving
 * the authorities granted to a user based on their UserDetails.
 */
public interface AuthorityProvider {

  /**
   * Retrieves the collection of granted authorities for the specified user.
   *
   * @param userDetails the UserDetails object representing the user
   * @param roles       a list of roles associated with the user
   * @param permissions a list of permissions associated with the user
   * @return a collection of GrantedAuthority objects representing the user's authorities
   * @throws Exception if an error occurs while retrieving the authorities
   */
  Collection<GrantedAuthority> getAuthorities(UserDetails userDetails, List<String> roles, List<String> permissions) throws Exception;
}
