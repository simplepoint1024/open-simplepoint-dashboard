package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.security.entity.RoleScopeBinding;

/** Tenant-qualified access to role policy bindings. */
public interface RoleScopeBindingRepository {
  Optional<RoleScopeBinding> findByTenantIdAndRoleId(String tenantId, String roleId);
  List<RoleScopeBinding> findByTenantIdAndRoleIdIn(String tenantId, Collection<String> roleIds);
  RoleScopeBinding save(RoleScopeBinding binding);
}
