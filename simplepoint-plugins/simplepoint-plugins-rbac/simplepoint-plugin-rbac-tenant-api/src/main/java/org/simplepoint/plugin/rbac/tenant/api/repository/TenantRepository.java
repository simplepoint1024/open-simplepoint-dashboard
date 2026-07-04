package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.Optional;
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
   * Retrieves the authorization version for a given tenant ID.
   *
   * @param tenantId the ID of the tenant for which to retrieve the authorization version
   * @return the authorization version as a String
   */
  Long getTenantAuthorizationVersion(String tenantId);

  /**
   * Increments the authorization version for the specified tenants.
   *
   * @param tenantIds the tenant IDs to refresh
   */
  void increaseAuthorizationVersion(Collection<String> tenantIds);

  /**
   * Returns all tenant identifiers.
   *
   * @return all tenant IDs
   */
  Collection<String> findAllIds();

  /**
   * Finds the personal tenant owned by the given user.
   *
   * @param ownerId the user ID whose personal tenant is looked up
   * @return an Optional containing the personal Tenant, or empty if not found
   */
  Optional<Tenant> findPersonalTenantByOwnerId(String ownerId);

  /**
   * Checks whether the specified user belongs to the tenant.
   *
   * @param tenantId the tenant identifier
   * @param userId the user identifier
   * @return true when the user is the owner or a tenant member
   */
  boolean hasUser(String tenantId, String userId);
}
