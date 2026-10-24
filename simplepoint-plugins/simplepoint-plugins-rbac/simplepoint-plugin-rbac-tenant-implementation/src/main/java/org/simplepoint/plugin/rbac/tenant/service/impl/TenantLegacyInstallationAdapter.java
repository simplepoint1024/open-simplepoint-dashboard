package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantLegacyAdoption;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantLegacyAdoptionMaintenance;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;

/** Explicitly constructed offline adapter; deliberately has no component or RPC declaration. */
public final class TenantLegacyInstallationAdapter implements TenantLegacyAdoptionMaintenance {
  private final TenantLegacyAdoptionStore store;

  /** Receives the transaction-proxied identity store from the restricted installation context. */
  public TenantLegacyInstallationAdapter(TenantLegacyAdoptionStore store) {
    this.store = Objects.requireNonNull(store);
  }

  @Override
  public List<String> pending(String after, int limit) {
    offline();
    return store.pending(after, limit);
  }

  @Override
  public TenantLegacyAdoption.Preview preview(String tenantId) {
    offline();
    return store.preview(tenantId);
  }

  @Override
  public TenantLegacyAdoption adopt(String tenantId) {
    offline();
    return store.adopt(tenantId, "installation");
  }

  @Override
  public Optional<TenantLegacyAdoption> completed(String tenantId) {
    offline();
    return store.completed(tenantId);
  }

  private static void offline() {
    if (RequestContextHolder.getRequestAttributes() != null || AuthorizationContextHolder.getContext() != null
        || SecurityContextHolder.getContext().getAuthentication() != null) {
      throw new TenantPackageException(TenantPackageException.Code.PLATFORM_ADMIN_REQUIRED);
    }
  }
}
