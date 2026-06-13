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

  /**
   * Save All.
   */
  <S extends TenantUserRelevance> List<S> saveAll(Iterable<S> entities);

  /**
   * Unauthorized.
   */
  void unauthorized(String tenantId, Set<String> userIds);

  /**
   * Authorized.
   */
  Collection<String> authorized(String tenantId);

  /**
   * Delete All By Tenant Ids.
   */
  void deleteAllByTenantIds(Collection<String> tenantIds);

  /**
   * Items.
   */
  Page<UserRelevanceVo> items(Pageable pageable);

  /**
   * Items by tenant.
   */
  Page<UserRelevanceVo> items(String tenantId, Pageable pageable);

  /**
   * Existing User Ids.
   */
  Set<String> existingUserIds(Collection<String> userIds);
}
