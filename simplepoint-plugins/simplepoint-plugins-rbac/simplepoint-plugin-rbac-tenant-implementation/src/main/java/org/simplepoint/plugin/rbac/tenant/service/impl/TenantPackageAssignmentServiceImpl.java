package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageResourceDefinition;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.Code;
import org.simplepoint.plugin.rbac.tenant.api.model.ResourceEntitlementWindow;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageAssignment;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageInstanceView;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageAssignmentService;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Freezes purchased resource identities and duration without activating them. */
@Service
public class TenantPackageAssignmentServiceImpl implements TenantPackageAssignmentService {
  private static final int MAX_RESOURCES = 5000;
  private final EntityManager em;

  /** Uses identity's selected persistence binding. */
  public TenantPackageAssignmentServiceImpl(EntityManager em) {
    this.em = em;
  }

  @Override
  @Transactional
  public TenantPackageInstanceView assign(TenantPackageAssignment command) {
    final String actor = TenantPackagePlatformBoundary.administrator();
    validate(command);
    final TenantType tenantType = TenantPackagePlatformBoundary.lockTenant(em, command.tenantId());
    TenantPackageInstance previous = em.find(TenantPackageInstance.class, command.allocationId());
    if (previous != null) {
      if (previous.getAcquisition() != TenantPackageInstance.Acquisition.ASSIGNED
          || !Objects.equals(previous.getTenantId(), command.tenantId())
          || !Objects.equals(previous.getPackageCode(), command.packageCode())) {
        throw new TenantPackageException(Code.ALLOCATION_CONFLICT);
      }
      return TenantPackageInstanceView.of(previous);
    }
    var packages =
        em.createQuery(
                "select p from Package p where p.code = :code "
                    + "and p.deletedAt is null and p.enabled = true",
                Package.class)
            .setParameter("code", command.packageCode())
            .setMaxResults(1)
            .getResultList();
    if (packages.isEmpty()) {
      throw new TenantPackageException(Code.PACKAGE_UNAVAILABLE);
    }
    Package definition = packages.getFirst();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    try {
      ResourceEntitlementWindow.append(null, now, definition.getDurationDays());
    } catch (IllegalArgumentException exception) {
      throw new TenantPackageException(Code.INVALID_DURATION);
    }
    var resources =
        em.createQuery(
                "select distinct r from Resource r, PackageApplicationRelevance pa,"
                    + " ApplicationResourceRelevance ar, Application a where pa.packageCode = :code"
                    + " and pa.applicationCode = ar.applicationCode and ar.resourceCode = r.code"
                    + " and a.code = pa.applicationCode and a.deletedAt is null and a.enabled ="
                    + " true and pa.deletedAt is null and ar.deletedAt is null and r.deletedAt is"
                    + " null and (r.disabled = false or r.disabled is null) and r.type not in"
                    + " ('GROUP','MODULE') order by r.id",
                Resource.class)
            .setParameter("code", definition.getCode())
            .setMaxResults(MAX_RESOURCES + 1)
            .getResultList();
    if (resources.size() > MAX_RESOURCES) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
    ResourceScopeType scope =
        tenantType == TenantType.PERSONAL ? ResourceScopeType.PERSONAL : ResourceScopeType.TENANT;
    var definitions = new LinkedHashMap<String, TenantPackageResourceDefinition>();
    for (Resource resource : resources) {
      if (ResourceScopePolicy.effectiveScopes(resource.getScopeTypes()).contains(scope)) {
        definitions.put(
            resource.getId(),
            new TenantPackageResourceDefinition(resource.getCode(), resource.getName()));
      }
    }
    if (definitions.isEmpty()) {
      throw new TenantPackageException(Code.RESOURCE_UNAVAILABLE);
    }
    var instance = new TenantPackageInstance();
    instance.setId(command.allocationId());
    instance.setTenantId(command.tenantId());
    instance.setPackageCode(definition.getCode());
    instance.setPackageName(definition.getName());
    instance.setDurationDays(definition.getDurationDays());
    instance.setResources(definitions);
    instance.setAssignedAt(now);
    instance.setAssignedBy(actor);
    em.persist(instance);
    em.flush();
    return TenantPackageInstanceView.of(instance);
  }

  private static void validate(TenantPackageAssignment command) {
    if (command == null
        || command.tenantId() == null
        || command.tenantId().isBlank()
        || command.tenantId().length() > 36
        || command.packageCode() == null
        || command.packageCode().isBlank()
        || command.packageCode().length() > 128
        || command.allocationId() == null) {
      throw new TenantPackageException(Code.INVALID_REQUEST);
    }
    try {
      if (!UUID.fromString(command.allocationId()).toString().equals(command.allocationId())) {
        throw new IllegalArgumentException();
      }
    } catch (IllegalArgumentException exception) {
      throw new TenantPackageException(Code.INVALID_REQUEST);
    }
  }
}
