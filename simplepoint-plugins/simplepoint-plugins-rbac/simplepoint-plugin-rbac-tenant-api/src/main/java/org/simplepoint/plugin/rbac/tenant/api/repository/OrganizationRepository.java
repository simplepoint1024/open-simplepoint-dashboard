package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;

/**
 * Repository for tenant-scoped organizations.
 */
public interface OrganizationRepository extends BaseRepository<Organization, String> {

  boolean existsByTenantIdAndCode(String tenantId, String code);

  boolean existsByTenantIdAndCodeAndIdNot(String tenantId, String code, String id);

  Optional<Organization> findByIdAndTenantId(String id, String tenantId);

  Collection<Organization> findAllByIdsAndTenantId(Collection<String> ids, String tenantId);

  Collection<Organization> findAllByTenantId(String tenantId);

  Collection<String> findIdsByParentIds(Collection<String> parentIds, String tenantId);
}
