package org.simplepoint.common.server.controller;

import static org.mockito.Mockito.mock;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.service.impl.TenantResourceCatalogImpl;
import org.springframework.test.util.ReflectionTestUtils;

/** Real tenant directory eligibility shared by permission metadata fixtures. */
final class TenantPermissionFixture {
  private TenantPermissionFixture() {}

  static void prepare(EntityManager em, String id) {
    var tenant = em.find(Tenant.class, id);
    if (tenant == null) {
      tenant = new Tenant();
      tenant.setId(id);
      tenant.setName(id);
      tenant.setOwnerId("fixture-owner");
      tenant.setTenantType(TenantType.ORGANIZATION);
      tenant.setAuthorizationVersion(0L);
      em.persist(tenant);
    }
    var setup = new TenantResourceSetup();
    setup.setTenantId(id);
    setup.setState(TenantResourceSetup.State.READY);
    setup.setInitializedAt(Instant.now());
    em.persist(setup);
    em.flush();
  }

  static TenantResource resource(String tenant, String id) {
    var resource = new TenantResource();
    resource.setTenantId(tenant);
    resource.setId(id);
    resource.setPlatformResourceId(id);
    resource.setBaselineEntitled(true);
    resource.setScopeTypes(
        java.util.Set.of(
            org.simplepoint.security.entity.ResourceScopeType.TENANT,
            org.simplepoint.security.entity.ResourceScopeType.PERSONAL));
    resource.setGrantable(true);
    resource.setCreatedAt(Instant.now());
    resource.setUpdatedAt(Instant.now());
    return resource;
  }

  static <T> T attach(EntityManager em, T target) {
    ReflectionTestUtils.setField(
        target,
        "tenantResources",
        new TenantResourceCatalogImpl(
            em,
            mock(org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository.class),
            mock(
                org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceProvisioningService
                    .class)));
    return target;
  }
}
