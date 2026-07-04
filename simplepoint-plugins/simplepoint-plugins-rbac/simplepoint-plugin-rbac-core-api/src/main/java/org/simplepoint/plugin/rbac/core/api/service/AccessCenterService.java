package org.simplepoint.plugin.rbac.core.api.service;

import java.util.List;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.AccessCenterRoleAuthorizationDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterResourceNodeVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterRoleDetailVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterRoleOverviewVo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Aggregated access-control operations for role authorization workflows.
 */
public interface AccessCenterService {

  /**
   * Returns role rows enriched with permission, user and scope summaries.
   *
   * @param pageable pagination information
   * @return paged role overviews
   */
  Page<AccessCenterRoleOverviewVo> roleOverviews(Pageable pageable);

  /**
   * Returns the full authorization state for a role.
   *
   * @param roleId role identifier
   * @return role authorization detail
   */
  AccessCenterRoleDetailVo roleDetail(String roleId);

  /**
   * Returns a menu-feature-permission resource tree for a role.
   *
   * @param roleId role identifier
   * @return resource tree nodes with role authorization state
   */
  List<AccessCenterResourceNodeVo> resourceTree(String roleId);

  /**
   * Replaces the permission and scope assignment for a role.
   *
   * @param dto role authorization payload
   * @return refreshed role authorization detail
   */
  AccessCenterRoleDetailVo saveRoleAuthorization(AccessCenterRoleAuthorizationDto dto);
}
