package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RolePermissionsRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.UserRoleRelevanceVo;
import org.simplepoint.security.entity.Permissions;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * PermissionsRepositoryApi provides an interface for managing Permissions entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Permissions entities.
 */
public interface PermissionsRepository extends BaseRepository<Permissions, String> {
  /**
   * Removes the specified authorities from the given role authority.
   *
   * @param roleAuthority the authority of the role
   * @param authorities   the set of permission authorities to be removed
   */
  void unauthorized(String roleAuthority, Set<String> authorities);

  /**
   * Retrieves a paginated list of permission items.
   *
   * @param pageable the pagination information
   * @return a page of RolePermissionsRelevanceVo representing permission items
   */
  Page<RolePermissionsRelevanceVo> permissionItems(Pageable pageable);

  /**
   * Retrieve a collection of permission authorities associated with a specific roleAuthority.
   *
   * @param roleAuthority The roleAuthority to filter the permission authorities.
   * @return A collection of permission authorities for the given roleAuthority.
   */
  Collection<String> authorized(String roleAuthority);
}
