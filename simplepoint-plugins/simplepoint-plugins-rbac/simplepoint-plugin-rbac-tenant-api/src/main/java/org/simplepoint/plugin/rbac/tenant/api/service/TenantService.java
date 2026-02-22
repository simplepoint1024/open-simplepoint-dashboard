package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;

/**
 * TenantService is an interface that defines methods for managing tenant-related operations.
 * It is part of the RBAC (Role-Based Access Control) module of the SimplePoint application.
 * This service will handle operations related to tenants, such as retrieving tenant information,
 * managing tenant data, and other related functionalities.
 */
public interface TenantService extends BaseService<Tenant, String> {

  /**
   * Retrieves a set of tenants associated with a specific user ID.
   *
   * @param userId the ID of the user for whom to retrieve associated tenants
   * @return a set of NamedTenantVo objects representing the tenants associated with the user
   */
  Set<NamedTenantVo> getTenantsByUserId(String userId);

  /**
   * Retrieves a set of tenants associated with the currently authenticated user.
   *
   * @return a set of NamedTenantVo objects representing the tenants associated with the current user
   */
  Set<NamedTenantVo> getCurrentUserTenants();
}
