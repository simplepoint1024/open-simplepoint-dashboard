package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantResourceSetupException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantResourceSetupException.Code;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceProvisioningService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Identity-local handoff from fresh bootstrap creation to authoritative common resource registration. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class BuiltInTenantResourcePreparation {
  private final EntityManager em;
  private final TenantResourceProvisioningService resources;

  /** Shares the owning identity transaction and its ordinary directory initialization pipeline. */
  public BuiltInTenantResourcePreparation(EntityManager em, TenantResourceProvisioningService resources) {
    this.em = em;
    this.resources = resources;
  }

  /** Called only for the tenant just created by the bootstrap writer, in its creation transaction. */
  public void stageNewTenant(String tenantId) {
    em.flush();
    Tenant tenant = em.find(Tenant.class, tenantId, LockModeType.PESSIMISTIC_WRITE);
    if (tenant == null || tenant.getDeletedAt() != null || tenant.getTenantType() != TenantType.ORGANIZATION
        || em.find(TenantResourceSetup.class, tenantId) != null) {
      throw new TenantResourceSetupException(Code.RESOURCE_CONFLICT);
    }
    long resourcesPresent = em.createQuery("select count(r) from TenantResource r where r.tenantId = :tenant", Long.class)
        .setParameter("tenant", tenantId).getSingleResult();
    if (resourcesPresent != 0) {
      throw new TenantResourceSetupException(Code.RESOURCE_CONFLICT);
    }
    var setup = new TenantResourceSetup();
    setup.setTenantId(tenantId);
    setup.setState(TenantResourceSetup.State.INITIALIZATION_REQUIRED);
    em.persist(setup);
  }

  /** Completes only freshly staged tenants; never adopts history or updates already-ready directories. */
  public void completePending() {
    String after = null;
    while (true) {
      var query = em.createQuery("select s.tenantId from TenantResourceSetup s, Tenant t "
              + "where s.tenantId = t.id and t.deletedAt is null and s.state = :pending "
              + (after == null ? "" : "and s.tenantId > :after ") + "order by s.tenantId", String.class)
          .setParameter("pending", TenantResourceSetup.State.INITIALIZATION_REQUIRED).setMaxResults(100);
      if (after != null) {
        query.setParameter("after", after);
      }
      var ids = query.getResultList();
      ids.forEach(resources::initialize);
      if (ids.size() < 100) {
        return;
      }
      after = ids.getLast();
    }
  }
}
