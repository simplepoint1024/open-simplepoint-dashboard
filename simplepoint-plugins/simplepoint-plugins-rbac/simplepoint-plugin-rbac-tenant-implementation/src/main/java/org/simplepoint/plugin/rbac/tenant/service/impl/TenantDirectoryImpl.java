package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.core.security.ServiceClientIdentity;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantDirectory;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Trusted service adapter; all domain membership and role checks remain in the tenant service. */
@Service
@RemoteProvider
public class TenantDirectoryImpl implements TenantDirectory {

  private final TenantService tenants;

  /** Uses the local tenant domain without re-exporting its entity CRUD methods. */
  public TenantDirectoryImpl(TenantService tenants) {
    this.tenants = tenants;
  }

  @Override
  public Set<NamedTenantVo> getTenantsByUserId(String userId) {
    requireServiceCaller();
    requireIdentifier(userId);
    return tenants.getTenantsByUserId(userId);
  }

  @Override
  public Collection<String> resolveActiveMemberIds(String tenantId, Collection<String> userIds) {
    requireServiceCaller();
    requireIdentifier(tenantId);
    requireBatch(userIds, 64);
    return tenants.resolveActiveMemberIds(tenantId, userIds);
  }

  @Override
  public Collection<String> resolveActiveRoleAuthorities(
      String tenantId, Collection<String> authorities) {
    requireServiceCaller();
    requireIdentifier(tenantId);
    requireBatch(authorities, 100);
    return tenants.resolveActiveRoleAuthorities(tenantId, authorities);
  }

  @Override
  public Collection<String> resolveActiveRoleMemberIds(
      String tenantId, Collection<String> authorities, String afterUserId, int limit) {
    requireServiceCaller();
    requireIdentifier(tenantId);
    requireBatch(authorities, 100);
    if (afterUserId != null) {
      requireIdentifier(afterUserId);
    }
    if (limit < 1 || limit > 200) {
      throw new IllegalArgumentException("Tenant directory page size must be between 1 and 200");
    }
    return tenants.resolveActiveRoleMemberIds(tenantId, authorities, afterUserId, limit);
  }

  private static void requireServiceCaller() {
    if (ServiceClientIdentity.authenticatedClientId(
            SecurityContextHolder.getContext().getAuthentication())
        .isEmpty()) {
      throw new AccessDeniedException("Tenant directory requires an authenticated service client");
    }
  }

  private static void requireIdentifier(String value) {
    if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 64) {
      throw new IllegalArgumentException("Invalid tenant directory identifier");
    }
  }

  private static void requireBatch(Collection<String> values, int maxLength) {
    if (values == null
        || values.size() > 50
        || values.stream()
            .anyMatch(
                value ->
                    value == null
                        || value.isBlank()
                        || value.codePointCount(0, value.length()) > maxLength)) {
      throw new IllegalArgumentException("Invalid tenant directory batch");
    }
  }
}
