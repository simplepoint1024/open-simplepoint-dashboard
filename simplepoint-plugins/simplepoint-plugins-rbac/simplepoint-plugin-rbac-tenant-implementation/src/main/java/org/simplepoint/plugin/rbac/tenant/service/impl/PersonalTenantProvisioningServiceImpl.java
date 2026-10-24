package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantResourceSetupException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantResourceSetupException.Code;
import org.simplepoint.plugin.rbac.tenant.api.service.PersonalTenantProvisioningService;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceProvisioningService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serializes personal workspace creation on the account, including registration and login repair. */
@Service
public class PersonalTenantProvisioningServiceImpl implements PersonalTenantProvisioningService {
  private final EntityManager em;
  private final TenantResourceProvisioningService resources;
  private final ResourceAuthorizationVersionService authorization;

  /** Receives the identity module's own persistence and local lifecycle ports. */
  public PersonalTenantProvisioningServiceImpl(EntityManager em, TenantResourceProvisioningService resources,
      ResourceAuthorizationVersionService authorization) {
    this.em = em;
    this.resources = resources;
    this.authorization = authorization;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Tenant ensureForUser(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new TenantResourceSetupException(Code.TENANT_UNAVAILABLE);
    }
    var users = em.createQuery("select u.id, u.name from User u where u.id = :id "
            + "and u.deletedAt is null", Object[].class)
        .setParameter("id", userId).setLockMode(LockModeType.PESSIMISTIC_WRITE).setMaxResults(1).getResultList();
    if (users.isEmpty()) {
      throw new TenantResourceSetupException(Code.TENANT_UNAVAILABLE);
    }
    var tenants = em.createQuery("select t from Tenant t where t.ownerId = :owner "
            + "and t.tenantType = :type and t.deletedAt is null", Tenant.class)
        .setParameter("owner", userId).setParameter("type", TenantType.PERSONAL)
        .setLockMode(LockModeType.PESSIMISTIC_WRITE).setMaxResults(2).getResultList();
    if (tenants.size() > 1) {
      throw new TenantResourceSetupException(Code.RESOURCE_CONFLICT);
    }
    boolean created = tenants.isEmpty();
    Tenant tenant;
    if (created) {
      tenant = new Tenant();
      tenant.setOwnerId(userId);
      tenant.setName(workspaceName((String) users.getFirst()[1], userId));
      tenant.setDescription("个人租户");
      tenant.setTenantType(TenantType.PERSONAL);
      tenant.setAuthorizationVersion(0L);
      em.persist(tenant);
    } else {
      tenant = tenants.getFirst();
      var setup = em.find(TenantResourceSetup.class, tenant.getId());
      if (setup == null || setup.getState() != TenantResourceSetup.State.READY || setup.getInitializedAt() == null) {
        throw new TenantResourceSetupException(Code.ADOPTION_REQUIRED);
      }
    }
    boolean membershipChanged = ensureMembership(tenant.getId(), userId);
    resources.initialize(tenant.getId());
    em.flush();
    if (!created && membershipChanged) {
      authorization.refreshTenant(tenant.getId());
    }
    return tenant;
  }

  private boolean ensureMembership(String tenantId, String userId) {
    var rows = em.createQuery("select m from TenantUserRelevance m where m.tenantId = :tenant "
            + "and m.userId = :user", TenantUserRelevance.class)
        .setParameter("tenant", tenantId).setParameter("user", userId).setMaxResults(2).getResultList();
    if (rows.size() > 1) {
      throw new TenantResourceSetupException(Code.RESOURCE_CONFLICT);
    }
    if (rows.isEmpty()) {
      var member = new TenantUserRelevance();
      member.setTenantId(tenantId);
      member.setUserId(userId);
      em.persist(member);
      return true;
    }
    var member = rows.getFirst();
    if (member.getDeletedAt() == null && Boolean.TRUE.equals(member.getEnabled())) {
      return false;
    }
    // Ownership entails membership. Repair the existing identity without duplicating historical rows.
    member.setDeletedAt(null);
    member.setEnabled(true);
    return true;
  }

  private static String workspaceName(String name, String userId) {
    String suffix = " 的个人空间 (" + userId + ")";
    int available = 128 - suffix.codePointCount(0, suffix.length());
    if (available < 1) {
      throw new TenantResourceSetupException(Code.TENANT_UNAVAILABLE);
    }
    String display = name == null || name.isBlank() ? userId : name.trim();
    int length = Math.min(available, display.codePointCount(0, display.length()));
    return display.substring(0, display.offsetByCodePoints(0, length)) + suffix;
  }
}
