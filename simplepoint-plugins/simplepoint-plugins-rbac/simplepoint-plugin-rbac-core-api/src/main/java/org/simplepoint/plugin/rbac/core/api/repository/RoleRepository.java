package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Collection;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleSelectVo;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RolePermissionsRelevance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * RolesRepository provides an interface for managing Role entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Role entities.
 */
public interface RoleRepository extends BaseRepository<Role, String> {

  /**
   * Load role-permission relationships based on a collection of role authorities.
   *
   * @param roleAuthorities A collection of role authorities to filter the relationships.
   * @return A collection of RolePermissionsRelevance entities associated with the given role authorities.
   */
  Collection<RolePermissionsRelevance> loadPermissionsByRoleAuthorities(Collection<String> roleAuthorities);

  /**
   * Retrieve a paginated list of RoleSelectDto for role selection purposes.
   *
   * @param pageable Pagination information.
   * @return A page of RoleSelectDto containing role selection data.
   */
  Page<RoleSelectVo> roleSelectItems(Pageable pageable);


  /**
   * Retrieve a collection of role authorities associated with a specific username.
   *
   * @param username The username to filter the role authorities.
   * @return A collection of role authorities for the given username.
   */
  Collection<String> userRoleAuthorities(String username);
}
