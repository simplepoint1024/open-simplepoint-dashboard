package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;

/**
 * Repository for tenant-scoped organizations.
 */
public interface OrganizationRepository extends BaseRepository<Organization, String> {

  /**
   * Checks whether a tenant already owns the supplied organization code.
   *
   * @param tenantId the tenant identifier
   * @param code     the organization code
   * @return {@code true} when the code already exists
   */
  boolean existsByTenantIdAndCode(String tenantId, String code);

  /**
   * Checks whether another organization in the tenant already uses the supplied code.
   *
   * @param tenantId the tenant identifier
   * @param code     the organization code
   * @param id       the organization id to exclude
   * @return {@code true} when another organization already uses the code
   */
  boolean existsByTenantIdAndCodeAndIdNot(String tenantId, String code, String id);

  /**
   * Finds a tenant-scoped organization by id.
   *
   * @param id       the organization id
   * @param tenantId the tenant identifier
   * @return the matching organization when present
   */
  Optional<Organization> findByIdAndTenantId(String id, String tenantId);

  /**
   * Finds all tenant-scoped organizations for the supplied ids.
   *
   * @param ids      the organization ids
   * @param tenantId the tenant identifier
   * @return the matching organizations
   */
  Collection<Organization> findAllByIdsAndTenantId(Collection<String> ids, String tenantId);

  /**
   * Lists all organizations for a tenant.
   *
   * @param tenantId the tenant identifier
   * @return the organizations that belong to the tenant
   */
  Collection<Organization> findAllByTenantId(String tenantId);

  /**
   * Finds child organization ids for the supplied parent ids within a tenant.
   *
   * @param parentIds the parent organization ids
   * @param tenantId  the tenant identifier
   * @return the matching child organization ids
   */
  Collection<String> findIdsByParentIds(Collection<String> parentIds, String tenantId);
}
