package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.vo.UserRelevanceVo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository for tenant user relations.
 */
public interface TenantUserRelevanceRepository {

  <S extends TenantUserRelevance> List<S> saveAll(Iterable<S> entities);

  void unauthorized(String tenantId, Set<String> userIds);

  Collection<String> authorized(String tenantId);

  void deleteAllByTenantIds(Collection<String> tenantIds);

  Page<UserRelevanceVo> items(Pageable pageable);

  Set<String> existingUserIds(Collection<String> userIds);
}
