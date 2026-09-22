package org.simplepoint.plugin.rbac.core.service.impl;

import org.simplepoint.plugin.rbac.core.api.repository.DataScopeRepository;
import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Tenant and lifecycle validation shared by role-policy assignment entry points. */
@Component
public class ScopePolicyValidator {
  @jakarta.persistence.PersistenceContext
  private jakarta.persistence.EntityManager entityManager;
  private final DataScopeRepository dataScopes;
  private final FieldScopeRepository fieldScopes;

  public ScopePolicyValidator(DataScopeRepository dataScopes, FieldScopeRepository fieldScopes) {
    this.dataScopes = dataScopes;
    this.fieldScopes = fieldScopes;
  }

  public void validate(String tenantId, String dataScopeId, String fieldScopeId) {
    if (tenantId == null || tenantId.isBlank()) {
      throw new AccessDeniedException("Tenant context is required");
    }
    if (entityManager != null) {
      if (dataScopeId != null) {
        var policy = entityManager.find(org.simplepoint.security.entity.DataScope.class, dataScopeId, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (policy == null || policy.getDeletedAt() != null || !tenantId.equals(policy.getTenantId()))
          throw new AccessDeniedException("数据范围不存在或不属于当前租户");
      }
      if (fieldScopeId != null) {
        var policy = entityManager.find(org.simplepoint.security.entity.FieldScope.class, fieldScopeId, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (policy == null || policy.getDeletedAt() != null || !tenantId.equals(policy.getTenantId()))
          throw new AccessDeniedException("字段范围不存在或不属于当前租户");
      }
    }
    if (dataScopeId != null && dataScopes.findByIdAndTenantIdAndDeletedAtIsNull(dataScopeId, tenantId).isEmpty()) {
      throw new AccessDeniedException("数据范围不存在或不属于当前租户");
    }
    if (dataScopeId != null && dataScopes.findByIdAndTenantIdAndDeletedAtIsNull(dataScopeId, tenantId)
        .filter(policy -> policy.getType() == org.simplepoint.security.entity.DataScopeType.EXPRESSION).isPresent())
      throw new AccessDeniedException("表达式规则必须通过页面资源授权绑定");
    if (fieldScopeId != null && fieldScopes.findByIdAndTenantIdAndDeletedAtIsNull(fieldScopeId, tenantId).isEmpty()) {
      throw new AccessDeniedException("字段范围不存在或不属于当前租户");
    }
  }
}
