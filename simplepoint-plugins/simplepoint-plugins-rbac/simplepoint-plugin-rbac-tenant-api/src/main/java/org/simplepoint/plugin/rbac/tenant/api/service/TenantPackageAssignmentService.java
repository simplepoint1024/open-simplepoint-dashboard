package org.simplepoint.plugin.rbac.tenant.api.service;

import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageAssignment;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageInstanceView;

/** Identity-owned platform management. Assignment itself grants no access. */
public interface TenantPackageAssignmentService {
  /** Allocates one immutable package instance or returns the matching previous allocation. */
  TenantPackageInstanceView assign(TenantPackageAssignment command);
}
