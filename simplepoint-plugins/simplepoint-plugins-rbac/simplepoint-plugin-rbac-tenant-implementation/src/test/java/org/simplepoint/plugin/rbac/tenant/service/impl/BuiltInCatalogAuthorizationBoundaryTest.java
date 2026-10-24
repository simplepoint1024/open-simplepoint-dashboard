package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceEntitlement;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationRepository;
import org.simplepoint.plugin.rbac.tenant.repository.JpaApplicationResourceRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.service.properties.BuiltInTenantBootstrapProperties;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

/** Registration and replay must change application membership without granting tenant access. */
class BuiltInCatalogAuthorizationBoundaryTest {
  @Test
  void newAndReintroducedResourcesDoNotChangeExistingOrRevokedRoleGrants() throws Exception {
    try (var database = new TenantEntitlementDatabase()) {
      var factory =
          database.create(
              Tenant.class,
              ApplicationResourceRelevance.class,
              Role.class,
              RoleResourceGrant.class,
              TenantResourceEntitlement.class);
      var em = SharedEntityManagerCreator.createSharedEntityManager(factory);
      var tx = new TransactionTemplate(new JpaTransactionManager(factory));
      final Instant revokedAt = Instant.parse("2026-10-01T00:00:00Z");
      tx.executeWithoutResult(
          status -> {
            var tenant = new Tenant();
            tenant.setName(new BuiltInTenantBootstrapProperties().getOrganizationName());
            tenant.setOwnerId("owner");
            tenant.setAuthorizationVersion(0L);
            em.persist(tenant);
            var role = new Role();
            role.setTenantId(tenant.getId());
            role.setRoleName("Tenant administrator");
            role.setAuthority(BuiltInTenantCodes.ADMIN_ROLE_AUTHORITY);
            em.persist(role);
            for (String code : List.of("dashboard.view", "storage.objects.view")) {
              var grant = new RoleResourceGrant();
              grant.setRoleId(role.getId());
              grant.setTenantId(tenant.getId());
              grant.setResourceCode(code);
              grant.setDataScopeId("kept-data-scope");
              grant.setFieldScopeId("kept-field-scope");
              if (code.equals("dashboard.view")) {
                grant.setDeletedAt(revokedAt);
              }
              em.persist(grant);
            }
          });
      var applications = mock(ApplicationRepository.class);
      when(applications.findAll(anyMap())).thenReturn(List.of(new Application()));
      var relations =
          new JpaRepositoryFactory(em)
              .getRepository(JpaApplicationResourceRelevanceRepository.class);
      var provisioner =
          new BuiltInTenantProvisionerImpl(
              applications, relations, mock(BuiltInTenantResourcePreparation.class));
      for (int attempt = 0; attempt < 2; attempt++) {
        tx.executeWithoutResult(
            status ->
                provisioner.provisionApplicationResources(
                    "common",
                    Set.of("dashboard.view", "dashboard.new", "storage.objects.view"),
                    Set.of()));
      }
      tx.executeWithoutResult(
          status -> {
            var grants =
                em.createQuery(
                        "from RoleResourceGrant g order by g.resourceCode", RoleResourceGrant.class)
                    .getResultList();
            assertThat(grants).hasSize(2);
            assertThat(grants.getFirst().getResourceCode()).isEqualTo("dashboard.view");
            assertThat(grants.getFirst().getDeletedAt()).isEqualTo(revokedAt);
            assertThat(grants.getLast().getDeletedAt()).isNull();
            assertThat(grants)
                .allSatisfy(
                    grant -> {
                      assertThat(grant.getDataScopeId()).isEqualTo("kept-data-scope");
                      assertThat(grant.getFieldScopeId()).isEqualTo("kept-field-scope");
                    });
            assertThat(relations.authorized(BuiltInTenantCodes.CORE_APPLICATION))
                .containsExactlyInAnyOrder("dashboard.view", "dashboard.new");
            assertThat(relations.authorized(BuiltInTenantCodes.STORAGE_APPLICATION))
                .containsExactly("storage.objects.view");
            assertThat(
                    em.createQuery("select count(e) from TenantResourceEntitlement e", Long.class)
                        .getSingleResult())
                .isZero();
          });
    }
  }
}
