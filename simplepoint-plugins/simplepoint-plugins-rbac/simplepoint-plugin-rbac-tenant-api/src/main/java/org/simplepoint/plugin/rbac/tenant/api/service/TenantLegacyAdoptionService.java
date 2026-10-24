package org.simplepoint.plugin.rbac.tenant.api.service;

import org.simplepoint.plugin.rbac.tenant.api.model.TenantLegacyAdoption;

/** Local identity migration port requiring the current verified platform administrator. */
public interface TenantLegacyAdoptionService {
  /** Inspects the legacy tenant without changing resources, grants, time balances or setup state. */
  TenantLegacyAdoption.Preview preview(String tenantId);

  /** Adopts existing eligibility once; never guesses elapsed time or restores revoked role grants. */
  TenantLegacyAdoption adopt(String tenantId);
}
