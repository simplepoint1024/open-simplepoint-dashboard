package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Collection;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.PermissionsRelevanceVo;
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
   * Retrieves a paginated list of permission items.
   *
   * @param pageable the pagination information
   * @return a page of RolePermissionsRelevanceVo representing permission items
   */
  Page<PermissionsRelevanceVo> permissionItems(Pageable pageable, Collection<String> permissions);

  /**
   * Retrieves permission detail rows by authority.
   *
   * @param permissions permission authorities
   * @return permission detail rows
   */
  Collection<PermissionsRelevanceVo> permissionItems(Collection<String> permissions);

  /**
   * Retrieves a paginated list of permission items.
   *
   * @param pageable the pagination information
   * @return a page of RolePermissionsRelevanceVo representing permission items
   */
  Page<PermissionsRelevanceVo> permissionItemsAll(Pageable pageable);
}
