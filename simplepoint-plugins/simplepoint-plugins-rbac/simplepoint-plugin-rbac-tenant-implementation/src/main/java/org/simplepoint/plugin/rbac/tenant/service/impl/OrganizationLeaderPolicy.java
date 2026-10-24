package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.error.OrganizationLeaderException;
import org.simplepoint.security.entity.User;
import org.springframework.stereotype.Component;

/** Identity-owned association policy. Linking a leader does not change membership or grants. */
@Component
public class OrganizationLeaderPolicy {
  private final EntityManager em;
  public OrganizationLeaderPolicy(EntityManager em) {this.em = em;}

  /** Uses the membership owner's tenant lock order; the association grants no account access. */
  public void validate(String tenantId, String userId) {
    if (userId == null) return;
    if (userId.length() > 36) throw new OrganizationLeaderException();
    var tenant = em.find(Tenant.class, tenantId, LockModeType.PESSIMISTIC_WRITE);
    var user = em.find(User.class, userId);
    if (tenant == null || tenant.getDeletedAt() != null || user == null || user.getDeletedAt() != null
        || Boolean.FALSE.equals(user.getEnabled())) throw new OrganizationLeaderException();
    if (userId.equals(tenant.getOwnerId())) return;
    var membership = em.createQuery("select m from TenantUserRelevance m where m.tenantId=:tenant and m.userId=:user and m.deletedAt is null", TenantUserRelevance.class)
        .setParameter("tenant", tenantId).setParameter("user", userId)
        .getResultStream().findFirst();
    if (membership.isEmpty() || Boolean.FALSE.equals(membership.get().getEnabled())) throw new OrganizationLeaderException();
  }

  /** Mirrors the current-tenant picker; inactive/departed identities retain no display label. */
  public String label(String tenantId, String userId) {
    if (userId == null) return null;
    return em.createQuery("select " + org.simplepoint.security.identity.UserDisplayName.HQL
        + " from User u, Tenant t where t.id=:tenant and t.deletedAt is null and u.id=:user"
        + " and u.deletedAt is null and coalesce(u.enabled,true)=true and (u.id=t.ownerId or exists"
        + " (select m.id from TenantUserRelevance m where m.tenantId=t.id and m.userId=u.id"
        + " and m.deletedAt is null and coalesce(m.enabled,true)=true))", String.class)
        .setParameter("tenant", tenantId).setParameter("user", userId).getResultList().stream().filter(java.util.Objects::nonNull).filter(value -> !value.isBlank()).findFirst().orElse(null);
  }
}
