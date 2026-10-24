package org.simplepoint.plugin.rbac.tenant.api.service;

import org.simplepoint.plugin.rbac.tenant.api.vo.AuditIdentityLabels;
import org.simplepoint.plugin.rbac.tenant.api.vo.AuditIdentityRequest;
import org.simplepoint.remoting.RemoteContract;

/** Internal name projection for audit IDs already authorized by the owning business service. */
@RemoteContract(name = "saas.audit-identities", version = "2")
public interface AuditIdentityDirectory {
  /** Resolves only requested identities in one tenant; never grants directory search access. */
  AuditIdentityLabels resolve(AuditIdentityRequest request);
}
