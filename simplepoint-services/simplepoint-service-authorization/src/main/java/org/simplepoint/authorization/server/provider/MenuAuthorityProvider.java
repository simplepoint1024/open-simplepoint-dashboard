package org.simplepoint.authorization.server.provider;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuPermissionsRelevanceRepository;
import org.simplepoint.security.provider.AuthorityProvider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * MenuAuthorityProvider is an implementation of the AuthorityProvider interface
 * that provides authorities based on menu permissions.
 */
@Component
public class MenuAuthorityProvider implements AuthorityProvider {
  private final MenuPermissionsRelevanceRepository menuPermissionsRelevanceRepository;

  /**
   * Constructs a new MenuAuthorityProvider with the specified repository.
   *
   * @param menuPermissionsRelevanceRepository the repository for menu-permission relationships
   */
  public MenuAuthorityProvider(MenuPermissionsRelevanceRepository menuPermissionsRelevanceRepository) {
    this.menuPermissionsRelevanceRepository = menuPermissionsRelevanceRepository;
  }

  @Override
  public Collection<GrantedAuthority> getAuthorities(UserDetails userDetails, List<String> roles, List<String> permissions) throws Exception {
    return menuPermissionsRelevanceRepository.loadAllMenuAuthorities(permissions)
        .stream().map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
  }
}
