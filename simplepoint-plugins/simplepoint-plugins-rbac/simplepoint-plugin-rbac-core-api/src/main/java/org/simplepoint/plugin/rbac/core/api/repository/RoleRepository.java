package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;
import org.simplepoint.security.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * RolesRepository provides an interface for managing Role entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Role entities.
 */
public interface RoleRepository extends BaseRepository<Role, String> {

  /**
   * Retrieve a paginated list of RoleSelectDto for role selection purposes.
   *
   * @param pageable Pagination information.
   * @return A page of RoleSelectDto containing role selection data.
   */
  Page<RoleRelevanceVo> roleSelectItems(Pageable pageable);

  /**
   * Removes the specified permissionIds from the given role authority.
   *
   * @param roleId        the authority of the role
   * @param permissionIds the set of permission permissionIds to be removed
   */
  void unauthorized(String roleId, Set<String> permissionIds);

  /**
   * Retrieve a collection of permission permissionIds associated with a specific roleId.
   *
   * @param roleId The roleId to filter the permission permissionIds.
   * @return A collection of permission permissionIds for the given roleId.
   */
  Collection<String> authorized(String roleId);
}
