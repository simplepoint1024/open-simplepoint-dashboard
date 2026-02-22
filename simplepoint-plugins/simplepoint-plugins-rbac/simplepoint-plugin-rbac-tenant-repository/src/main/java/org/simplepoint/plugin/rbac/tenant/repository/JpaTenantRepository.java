package org.simplepoint.plugin.rbac.tenant.repository;

import java.util.Set;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JpaTenantRepository provides an interface for managing Tenant entities.
 * It extends the BaseRepository to inherit basic CRUD operations and the TenantRepository
 * to include specific methods for handling tenant data.
 * This interface is used to interact with the persistence layer for Tenant entities.
 */
@Repository
public interface JpaTenantRepository extends BaseRepository<Tenant, String>, TenantRepository {

  @Override
  @Query("""
      select
          new org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo(t.id, t.name)
      from UserTenantRelevance utr
      join Tenant t on utr.tenantId = t.id
      where utr.userId = :userId
      """)
  Set<NamedTenantVo> getTenantsByUserId(@Param("userId") String userId);
}
