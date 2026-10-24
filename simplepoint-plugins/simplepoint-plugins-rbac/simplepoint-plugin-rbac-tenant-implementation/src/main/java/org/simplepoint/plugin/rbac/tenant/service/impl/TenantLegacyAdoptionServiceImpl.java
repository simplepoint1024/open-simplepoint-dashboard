package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantLegacyAdoption;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantLegacyAdoptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform-authorized adoption entry; offline installation uses a separate, non-discovered adapter. */
@Service
@Transactional(readOnly = true)
public class TenantLegacyAdoptionServiceImpl implements TenantLegacyAdoptionService {
  private final TenantLegacyAdoptionStore store;

  /** The store proxy owns identity-local transactions and is never exposed as an RPC contract. */
  @Autowired
  public TenantLegacyAdoptionServiceImpl(TenantLegacyAdoptionStore store) {
    this.store = store;
  }

  TenantLegacyAdoptionServiceImpl(EntityManager em, ResourceAuthorizationVersionService authorization, Clock clock) {
    this(new TenantLegacyAdoptionStore(em, authorization, clock));
  }

  @Override
  public TenantLegacyAdoption.Preview preview(String tenantId) {
    TenantPackagePlatformBoundary.administrator();
    return store.preview(tenantId);
  }

  @Override
  @Transactional
  public TenantLegacyAdoption adopt(String tenantId) {
    String actor = TenantPackagePlatformBoundary.administrator();
    return store.adopt(tenantId, actor);
  }
}
