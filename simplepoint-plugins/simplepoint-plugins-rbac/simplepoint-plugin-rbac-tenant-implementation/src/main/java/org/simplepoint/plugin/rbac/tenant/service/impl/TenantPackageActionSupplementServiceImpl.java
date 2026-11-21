package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance.Acquisition;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance.State;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageResourceDefinition;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.Code;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageActionSupplementService;
import org.simplepoint.security.entity.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records explicit action supplements and their credits with the existing package instance. */
@Service
public class TenantPackageActionSupplementServiceImpl implements TenantPackageActionSupplementService {
  private final EntityManager em;
  private final ResourceAuthorizationVersionService authorization;
  private final Clock clock;

  /** Uses identity's selected persistence binding. */
  @Autowired
  public TenantPackageActionSupplementServiceImpl(
      EntityManager em, ResourceAuthorizationVersionService authorization) {
    this(em, authorization, Clock.systemUTC());
  }

  TenantPackageActionSupplementServiceImpl(
      EntityManager em, ResourceAuthorizationVersionService authorization, Clock clock) {
    this.em = em;
    this.authorization = authorization;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void supplement(String tenantId, String instanceId, Set<String> resourceCodes) {
    String actor = TenantPackagePlatformBoundary.administrator();
    if (instanceId == null || instanceId.isBlank() || instanceId.length() > 36
        || resourceCodes == null || resourceCodes.isEmpty() || resourceCodes.size() > 100
        || resourceCodes.stream().anyMatch(code -> code == null || code.isBlank() || code.length() > 120)) {
      throw new TenantPackageException(Code.INVALID_REQUEST);
    }
    TenantResourceSourceBoundary.enter(em, () -> new TenantPackageException(Code.RESOURCE_UNAVAILABLE));
    TenantPackagePlatformBoundary.lockTenant(em, tenantId);
    var instance = em.find(TenantPackageInstance.class, instanceId);
    if (instance == null || !instance.getTenantId().equals(tenantId)) {
      throw new TenantPackageException(Code.INSTANCE_NOT_FOUND);
    }
    if (instance.getState() == State.REVOKED) {
      throw new TenantPackageException(Code.INSTANCE_REVOKED);
    }
    if (instance.getState() != State.ACTIVE || instance.getDurationDays() != null
        || instance.getAcquisition() != Acquisition.ASSIGNED
            && instance.getAcquisition() != Acquisition.LEGACY_PACKAGE) {
      throw new TenantPackageException(Code.INSTANCE_NOT_SUPPLEMENTABLE);
    }
    new TenantPackageReceipts(em).activation(instance);
    var existing = instance.getResources().values().stream()
        .map(TenantPackageResourceDefinition::getResourceCode)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    var missing = resourceCodes.stream().filter(code -> !existing.contains(code)).collect(Collectors.toSet());
    if (missing.isEmpty()) {
      return;
    }

    var actions = em.createQuery(
            "select r from Resource r where r.code in :codes and r.type = 'ACTION'"
                + " and r.grantable = true and r.deletedAt is null and r.disabled = false"
                + " and exists (select 1 from TenantPackageResourceDefinition original"
                + " where original.instanceId = :instance and original.resourceId = r.parentId"
                + " and original.supplemental = false)"
                + " and exists (select 1 from Package p, PackageApplicationRelevance pa,"
                + " Application a, ApplicationResourceRelevance ar where p.code = :packageCode"
                + " and p.enabled = true and p.deletedAt is null and pa.packageCode = p.code"
                + " and pa.deletedAt is null and a.code = pa.applicationCode and a.enabled = true"
                + " and a.deletedAt is null and ar.applicationCode = a.code and ar.deletedAt is null"
                + " and ar.resourceCode = r.code) order by r.id",
            Resource.class)
        .setParameter("codes", missing)
        .setParameter("instance", instanceId)
        .setParameter("packageCode", instance.getPackageCode())
        .getResultList();
    if (actions.size() != missing.size()) {
      throw new TenantPackageException(Code.RESOURCE_CONFLICT);
    }
    var additions = actions.stream().filter(action -> !instance.getResourceIds().contains(action.getId())).toList();
    if (additions.isEmpty()) {
      return;
    }
    if (instance.getResourceIds().size() + additions.size() > TenantPackageReceipts.MAX_RESOURCES) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    if (now.isBefore(instance.getActivatedAt())) {
      throw new TenantPackageException(Code.INCONSISTENT_ACTIVATION);
    }
    var ids = additions.stream().map(Resource::getId).toList();
    new TenantResourcePlatformImporter(em).importForActivation(tenantId, ids, now);
    for (Resource action : additions) {
      var definition = new TenantPackageResourceDefinition(action.getCode(), action.getName());
      definition.setInstanceId(instanceId);
      definition.setResourceId(action.getId());
      definition.setSupplemental(true);
      em.persist(definition);
    }
    em.flush();
    var credits = new TenantResourceCreditIssuer(em);
    for (String resourceId : ids) {
      credits.credit(instance, resourceId, now, actor);
    }
    em.flush();
    if (!new TenantResourceReadQuery(em, tenantId, now).eligibleResourceIds(ids).containsAll(ids)) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
    authorization.refreshTenant(tenantId);
  }
}
