package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;

/**
 * TenantRepository provides an interface for managing Tenant entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Tenant entities.
 */
public interface TenantRepository extends BaseRepository<Tenant, String> {

  /**
   * Retrieves a set of tenants associated with a specific user ID.
   *
   * @param userId the ID of the user for whom to retrieve associated tenants
   * @return a set of NamedTenantVo objects representing the tenants associated with the user
   */
  Set<NamedTenantVo> getTenantsByUserId(String userId);

  /**
   * Retrieves the permission version for a given tenant ID.
   *
   * @param tenantId the ID of the tenant for which to retrieve the permission version
   * @return the permission version as a String
   */
  Long getTenantPermissionVersion(String tenantId);

  /**
   * Increments the permission version for the specified tenants.
   *
   * @param tenantIds the tenant IDs to refresh
   */
  void increasePermissionVersion(Collection<String> tenantIds);
}
