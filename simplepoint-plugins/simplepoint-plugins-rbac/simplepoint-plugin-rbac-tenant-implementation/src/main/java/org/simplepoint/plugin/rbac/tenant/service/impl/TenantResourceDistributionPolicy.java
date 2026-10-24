package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import java.util.LinkedHashSet;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantResourceSetupException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantResourceSetupException.Code;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;

/**
 * Baseline eligibility follows explicit application distribution, independently of publicAccess.
 */
final class TenantResourceDistributionPolicy {
  private final EntityManager em;

  TenantResourceDistributionPolicy(EntityManager em) {
    this.em = em;
  }

  Set<String> baseline(TenantType tenantType) {
    var applications = BuiltInTenantCodes.BASELINE_APPLICATIONS;
    long ready =
        em.createQuery(
                "select count(a) from Application a where a.code in :codes "
                    + "and a.enabled = true and a.deletedAt is null",
                Long.class)
            .setParameter("codes", applications)
            .getSingleResult();
    if (ready != applications.size()) {
      throw new TenantResourceSetupException(Code.CATALOG_NOT_READY);
    }
    var selected = new LinkedHashSet<String>();
    ResourceScopeType scope =
        tenantType == TenantType.PERSONAL ? ResourceScopeType.PERSONAL : ResourceScopeType.TENANT;
    String after = null;
    while (true) {
      var query =
          em.createQuery(
                  "select r from Resource r where r.deletedAt is null and r.disabled = false and"
                      + " (r.grantable = true or (r.publicAccess = true and r.type not in"
                      + " ('GROUP','MODULE'))) and exists (select 1 from"
                      + " ApplicationResourceRelevance ar where ar.applicationCode in :codes and"
                      + " ar.resourceCode = r.code and ar.deletedAt is null) "
                      + (after == null ? "" : "and r.id > :after ")
                      + "order by r.id",
                  Resource.class)
              .setParameter("codes", applications)
              .setMaxResults(100);
      if (after != null) {
        query.setParameter("after", after);
      }
      var rows = query.getResultList();
      for (Resource resource : rows) {
        if (ResourceScopePolicy.effectiveScopes(resource.getScopeTypes()).contains(scope)) {
          selected.add(resource.getId());
        }
      }
      if (selected.size() > 10000) {
        throw new TenantResourceSetupException(Code.RESOURCE_UNAVAILABLE);
      }
      if (rows.size() < 100) {
        break;
      }
      after = rows.getLast().getId();
    }
    if (selected.isEmpty()) {
      throw new TenantResourceSetupException(Code.CATALOG_NOT_READY);
    }
    return Set.copyOf(selected);
  }
}
