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
   * @param tenantId the tenant scope
   * @param pageable Pagination information.
   * @return A page of RoleSelectDto containing role selection data.
   */
  Page<RoleRelevanceVo> roleSelectItems(String tenantId, Pageable pageable);

  /**
   * Removes resource grants from the given role.
   *
   * @param roleId the role id
   * @param resourceCodes resource codes to be removed
   */
  void unauthorized(String tenantId, String roleId, Set<String> resourceCodes);

  /**
   * Retrieve resource codes granted to a role.
   *
   * @param roleId The roleId to filter.
   * @return resource codes granted to the role.
   */
  Collection<String> authorized(String tenantId, String roleId);
}
