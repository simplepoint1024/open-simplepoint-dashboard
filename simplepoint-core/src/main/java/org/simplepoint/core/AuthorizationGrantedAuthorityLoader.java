package org.simplepoint.core;

import java.util.Collection;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;

/**
 * An interface for loading granted authorities based on claims.
 */
public interface AuthorizationGrantedAuthorityLoader {

  /**
   * Loads granted authorities based on the provided claims.
   *
   * @param chaims a map of claims that may be used to determine the granted authorities
   * @return a collection of granted authorities derived from the claims
   */
  Collection<? extends GrantedAuthority> load(Map<String, Object> chaims);
}
