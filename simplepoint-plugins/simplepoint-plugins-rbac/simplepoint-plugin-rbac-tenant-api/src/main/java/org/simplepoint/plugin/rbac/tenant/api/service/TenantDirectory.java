package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.simplepoint.remoting.RemoteContract;

/** Tenant directory for authenticated platform services; contains no entity management API. */
@RemoteContract(name = "saas.tenant", version = "2")
public interface TenantDirectory {

  /** Returns tenant names for a verified user identity supplied by a trusted service. */
  Set<NamedTenantVo> getTenantsByUserId(String userId);

  /** Resolves at most 50 requested active member IDs within one tenant. */
  Collection<String> resolveActiveMemberIds(String tenantId, Collection<String> userIds);

  /** Resolves at most 50 requested active role authorities within one tenant. */
  Collection<String> resolveActiveRoleAuthorities(String tenantId, Collection<String> authorities);

  /** Returns at most 200 members of up to 50 roles, after an optional stable user ID cursor. */
  Collection<String> resolveActiveRoleMemberIds(
      String tenantId, Collection<String> authorities, String afterUserId, int limit);
}
