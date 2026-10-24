package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.List;
import java.util.Optional;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantLegacyAdoption;

/** Local installer capability; never a remotely exported service or a browser authorization bypass. */
public interface TenantLegacyAdoptionMaintenance {
  /** Lists at most 100 pending active tenant identities after an exclusive stable cursor. */
  List<String> pending(String after, int limit);

  /** Returns advisory current facts without writing data or pinning a resource version. */
  TenantLegacyAdoption.Preview preview(String tenantId);

  /** Atomically adopts one tenant using the offline technical principal. */
  TenantLegacyAdoption adopt(String tenantId);

  /** Reads the original durable completion, without applying any effects. */
  Optional<TenantLegacyAdoption> completed(String tenantId);
}
