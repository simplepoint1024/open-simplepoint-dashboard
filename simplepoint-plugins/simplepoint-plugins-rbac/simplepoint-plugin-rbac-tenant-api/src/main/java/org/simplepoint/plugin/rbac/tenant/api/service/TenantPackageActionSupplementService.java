package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Set;

/** Adds newly available actions to an existing active, unlimited package. */
public interface TenantPackageActionSupplementService {
  /** Adds only the requested actions and safely repeats an already applied supplement. */
  void supplement(String tenantId, String instanceId, Set<String> resourceCodes);
}
