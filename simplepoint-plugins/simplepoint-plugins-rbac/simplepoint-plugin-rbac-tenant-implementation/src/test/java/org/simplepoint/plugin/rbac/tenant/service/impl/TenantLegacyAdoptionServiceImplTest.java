package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageResourceDefinition;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceCredit;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceEntitlement;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantLegacyAdoption;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantLegacyAdoptionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageActivationService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageQueryService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageRevocationService;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

class TenantLegacyAdoptionServiceImplTest {
  private static final Instant NOW = Instant.parse("2026-10-23T08:00:00.123456Z");
  private TenantEntitlementDatabase database;
  private EntityManager em;
  private JpaTransactionManager transactions;
  private ResourceAuthorizationVersionService authorization;
  private TenantLegacyAdoptionService service;
  private String tenantId;

  @BeforeEach
  void setup() throws Exception {
    database = new TenantEntitlementDatabase();
    var factory =
        database.create(
            Tenant.class,
            TenantResource.class,
            TenantResourceSetup.class,
            Resource.class,
            Application.class,
            ApplicationResourceRelevance.class,
            Package.class,
            PackageApplicationRelevance.class,
            TenantPackageRelevance.class,
            TenantPackageInstance.class,
            TenantPackageResourceDefinition.class,
            TenantResourceCredit.class,
            TenantResourceEntitlement.class,
            Role.class,
            RoleResourceGrant.class);
    em = SharedEntityManagerCreator.createSharedEntityManager(factory);
    transactions = new JpaTransactionManager(factory);
    authorization = mock(ResourceAuthorizationVersionService.class);
    service =
        proxy(
            new TenantLegacyAdoptionServiceImpl(em, authorization, clock(NOW)),
            TenantLegacyAdoptionService.class);
    tenantId =
        tx(
            () -> {
              var tenant = new Tenant();
              tenant.setName("Legacy tenant");
              tenant.setTenantType(TenantType.ORGANIZATION);
              tenant.setResourceMode(TenantResourceMode.PLATFORM);
              tenant.setOwnerId("owner");
              tenant.setAuthorizationVersion(0L);
              em.persist(tenant);
              var setup = new TenantResourceSetup();
              setup.setTenantId(tenant.getId());
              setup.setState(TenantResourceSetup.State.ADOPTION_REQUIRED);
              em.persist(setup);
              return tenant.getId();
            });
    tx(
        () -> {
          for (String code : BuiltInTenantCodes.BASELINE_APPLICATIONS) {
            var application = new Application();
            application.setCode(code);
            application.setName(code);
            application.setEnabled(true);
            em.persist(application);
          }
          source("root", false).setType(ResourceType.GROUP);
          source("base", true);
          link(BuiltInTenantCodes.CORE_APPLICATION, "base");
          source("paid", true);
          source("shared", true);
          source("public", true).setPublicAccess(true);
          source("direct", true);
          source("revoked", true);
          source("outside", true).setScopeTypes(Set.of(ResourceScopeType.PLATFORM));
          source("unlicensed", true);
          legacyPackage("old-a", List.of("paid", "shared", "outside"));
          legacyPackage("old-b", List.of("shared"));
          var role = new Role();
          role.setRoleName("Old role");
          role.setTenantId(tenantId);
          role.setAuthority("ROLE_OLD");
          em.persist(role);
          grant(role, "direct", false);
          grant(role, "revoked", true);
          return null;
        });
    administrator();
  }

  @AfterEach
  void close() throws Exception {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    database.close();
  }

  @Test
  void preflightDoesNotWriteAndAdoptionPreservesFactsWithoutInventingPurchaseDates() {
    var preview = service.preview(tenantId);
    assertThat(preview.sources()).hasSize(4);
    assertThat(preview.legacyResourceCount()).isEqualTo(4);
    assertThat(count(TenantResource.class)).isZero();
    assertThat(count(TenantResourceCredit.class)).isZero();
    assertThat(state()).isEqualTo(TenantResourceSetup.State.ADOPTION_REQUIRED);
    var receipt = service.adopt(tenantId);
    assertThat(receipt.resourceCount()).isEqualTo(4);
    assertThat(receipt.instanceCount()).isEqualTo(4);
    assertThat(receipt.adoptedAt()).isEqualTo(NOW);
    assertThat(receipt.adoptedBy()).isEqualTo("platform-admin");
    assertThat(resource("outside")).isNull();
    assertThat(resource("unlicensed")).isNull();
    assertThat(resource("revoked")).isNull();
    assertThat(resource("base").isBaselineEntitled()).isTrue();
    assertThat(balance("paid").getExpiresAt()).isNull();
    assertThat(count(RoleResourceGrant.class)).isEqualTo(2);
    assertThat(
            tx(
                () ->
                    em.createQuery(
                            "select count(g) from RoleResourceGrant g where g.deletedAt is not"
                                + " null",
                            Long.class)
                        .getSingleResult()))
        .isEqualTo(1);
    owner();
    var queries =
        proxy(new TenantPackageQueryServiceImpl(em, clock(NOW)), TenantPackageQueryService.class);
    var rows = queries.query(Map.of(), PageRequest.of(0, 20));
    assertThat(rows.getContent())
        .hasSize(4)
        .allSatisfy(
            row -> {
              assertThat(row.activatedAt()).isNull();
              assertThat(queries.resources(row.id(), PageRequest.of(0, 20)).getContent())
                  .allSatisfy(resource -> assertThat(resource.activatedAt()).isNull());
              assertThat(row.adoptedAt()).isEqualTo(NOW);
              assertThat(row.durationDays()).isNull();
              assertThat(row.getAllowedActions()).containsExactly("resources");
            });
    assertThat(
            queries
                .query(Map.of("acquisition", "LEGACY_PACKAGE"), PageRequest.of(0, 20))
                .getTotalElements())
        .isEqualTo(2);
    rows.stream()
        .filter(
            row ->
                row.acquisition() == TenantPackageInstance.Acquisition.LEGACY_PUBLIC
                    || row.acquisition() == TenantPackageInstance.Acquisition.LEGACY_ROLE)
        .forEach(
            row -> {
              assertThat(row.packageName()).isNull();
              assertThat(row.packageCode()).isNull();
            });
    var activation =
        proxy(
            new TenantPackageActivationServiceImpl(em, authorization, clock(NOW)),
            TenantPackageActivationService.class);
    assertThatThrownBy(() -> activation.activate(rows.getContent().getFirst().id()))
        .hasMessageContaining("INSTANCE_NOT_ACTIVATABLE");
  }

  @Test
  void existingGeneratedPagesAdoptOnlyTheirPrivateRuntimeHostAndRetainRevocation() {
    tx(
        () -> {
          source("runtime-host", false).setComponent("app/FormRuntime");
          source("unreferenced-host", false).setComponent("app/FormRuntime");
          managedPage("first-business", "runtime-host", tenantId, "app");
          managedPage("second-business", "runtime-host", tenantId, "report");
          return null;
        });
    var preview = service.preview(tenantId);
    assertThat(preview.sources())
        .filteredOn(row -> row.acquisition() == TenantPackageInstance.Acquisition.LEGACY_HOST)
        .singleElement()
        .satisfies(row -> assertThat(row.resourceCount()).isEqualTo(1));
    service.adopt(tenantId);
    final String instanceId =
        tx(
            () -> {
              var adopted =
                  em.createQuery(
                          "select i from TenantPackageInstance i where i.tenantId = :tenant and"
                              + " i.acquisition = :source",
                          TenantPackageInstance.class)
                      .setParameter("tenant", tenantId)
                      .setParameter("source", TenantPackageInstance.Acquisition.LEGACY_HOST)
                      .getSingleResult();
              assertThat(adopted.getResourceIds()).containsExactly("runtime-host");
              assertThat(adopted.getDurationDays()).isNull();
              assertThat(
                      em.find(
                          TenantResource.class,
                          new TenantResourceKey(tenantId, "unreferenced-host")))
                  .isNull();
              var query = new TenantResourceReadQuery(em, tenantId, NOW);
              assertThat(query.authorization(Set.of(), true).resourceCodes())
                  .contains("business.first-business", "business.second-business")
                  .doesNotContain("test.runtime-host", "test.unreferenced-host");
              return adopted.getId();
            });
    proxy(
            new TenantPackageRevocationServiceImpl(em, authorization, clock(NOW.plusSeconds(1))),
            TenantPackageRevocationService.class)
        .revoke(tenantId, instanceId);
    tx(
        () -> {
          assertThat(
                  new TenantResourceReadQuery(em, tenantId, NOW.plusSeconds(1))
                      .authorization(Set.of(), true)
                      .resourceCodes())
              .doesNotContain("business.first-business", "business.second-business");
          assertThat(
                  em.find(TenantResource.class, new TenantResourceKey(tenantId, "first-business"))
                      .isEnabled())
              .isTrue();
          return null;
        });
  }

  @Test
  void inactiveForeignUnknownAndInvalidHostReferencesNeverAcquireHistoricalRights() {
    tx(
        () -> {
          for (String id :
              List.of(
                  "foreign",
                  "offline",
                  "disabled",
                  "manual",
                  "unknown",
                  "invalid-scope",
                  "no-component",
                  "grantable",
                  "stopped")) {
            var host = source("host-" + id, false);
            host.setComponent("app/FormRuntime");
            var row =
                managedPage(
                    "business-" + id,
                    host.getId(),
                    "foreign".equals(id) ? "another-tenant" : tenantId,
                    "unknown".equals(id) ? "untrusted-owner" : "app");
            switch (id) {
              case "offline" -> row.setFormallyOffline(true);
              case "disabled" -> row.setEnabled(false);
              case "manual" -> row.setManualDisabled(true);
              case "invalid-scope" -> host.setScopeTypes(Set.of(ResourceScopeType.PLATFORM));
              case "no-component" -> host.setComponent(null);
              case "grantable" -> host.setGrantable(true);
              case "stopped" -> host.setDisabled(true);
              default -> {}
            }
          }
          return null;
        });
    assertThat(service.preview(tenantId).sources())
        .noneMatch(row -> row.acquisition() == TenantPackageInstance.Acquisition.LEGACY_HOST);
    service.adopt(tenantId);
    tx(
        () -> {
          assertThat(
                  em.createQuery(
                          "select count(c) from TenantResourceCredit c where c.tenantId = :tenant"
                              + " and c.resourceId like 'host-%'",
                          Long.class)
                      .setParameter("tenant", tenantId)
                      .getSingleResult())
              .isZero();
          return null;
        });
  }

  private TenantResource managedPage(String id, String host, String tenant, String owner) {
    var row = new TenantResource();
    row.setTenantId(tenant);
    row.setId(id);
    row.setCode("business." + id);
    row.setName(id);
    row.setType(ResourceType.PAGE);
    row.setPath("/business/" + id);
    row.setComponent("app/FormRuntime");
    row.setManagedOwner(owner);
    row.setManagedKey(id);
    row.setManagedHostResourceId(host);
    row.setGrantable(true);
    row.setScopeTypes(Set.of(ResourceScopeType.TENANT));
    row.setLifecycleGeneration(1);
    em.persist(row);
    return row;
  }

  @Test
  void adoptedInstanceCannotBeReusedForNewPurchaseAllocation() {
    service.adopt(tenantId);
    String instanceId =
        tx(
            () ->
                em.createQuery(
                        "select i.id from TenantPackageInstance i where i.packageCode = 'old-a'",
                        String.class)
                    .getSingleResult());
    var assignment =
        proxy(
            new TenantPackageAssignmentServiceImpl(em),
            org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageAssignmentService.class);
    assertThatThrownBy(
            () ->
                assignment.assign(
                    new org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageAssignment(
                        tenantId, "old-a", instanceId)))
        .hasMessageContaining("ALLOCATION_CONFLICT");
  }

  @Test
  void executionRereadsCurrentFactsButRepeatedAdoptionNeverIssuesMoreCredits() {
    service.preview(tenantId);
    tx(
        () -> {
          source("new-public", true).setPublicAccess(true);
          return null;
        });
    var result = service.adopt(tenantId);
    long credits = count(TenantResourceCredit.class);
    tx(
        () -> {
          source("later-public", true).setPublicAccess(true);
          return null;
        });
    assertThat(service.adopt(tenantId)).isEqualTo(result);
    assertThat(count(TenantResourceCredit.class)).isEqualTo(credits);
    assertThat(resource("new-public")).isNotNull();
    assertThat(resource("later-public")).isNull();
  }

  @Test
  void customCatalogRetainsOverridesAndInactiveRowsWithoutEnablingOmittedPaidPages() {
    tx(
        () -> {
          em.find(Tenant.class, tenantId).setResourceMode(TenantResourceMode.CUSTOM);
          var row = new TenantResource();
          row.setTenantId(tenantId);
          row.setId("paid");
          row.setPlatformResourceId("paid");
          row.setCode("test.paid");
          row.setName("My retained title");
          row.setType(ResourceType.PAGE);
          row.setLocalModified(true);
          row.setEnabled(false);
          row.setManualDisabled(true);
          row.setFormallyOffline(true);
          row.setCreatedAt(NOW.minusSeconds(500));
          row.setUpdatedAt(NOW.minusSeconds(100));
          em.persist(row);
          return null;
        });
    service.adopt(tenantId);
    assertThat(resource("paid").getName()).isEqualTo("My retained title");
    assertThat(resource("paid").getCreatedAt()).isEqualTo(NOW.minusSeconds(500));
    assertThat(resource("paid").isFormallyOffline()).isTrue();
    assertThat(resource("paid").isEnabled()).isFalse();
    assertThat(resource("shared").isEnabled()).isFalse();
    assertThat(resource("root").isEnabled()).isTrue();
    assertThat(resource("base").isEnabled()).isTrue();
    assertThat(resource("direct")).isNull();
    assertThat(balance("paid")).isNotNull();
  }

  @Test
  void anyFinalizationFailureRollsBackResourcesCreditsAndReadiness() {
    doThrow(new IllegalStateException("invalidation failed"))
        .when(authorization)
        .refreshTenant(anyString());
    assertThatThrownBy(() -> service.adopt(tenantId)).hasMessageContaining("invalidation failed");
    assertThat(count(TenantResource.class)).isZero();
    assertThat(count(TenantPackageInstance.class)).isZero();
    assertThat(count(TenantResourceCredit.class)).isZero();
    assertThat(count(TenantResourceEntitlement.class)).isZero();
    assertThat(state()).isEqualTo(TenantResourceSetup.State.ADOPTION_REQUIRED);
    assertThat(count(RoleResourceGrant.class)).isEqualTo(2);
  }

  @Test
  void legacyRevocationUsesNormalLedgerAndPreservesOtherSourcesAndQueuedRenewal() {
    service.adopt(tenantId);
    final String legacyId =
        tx(
            () ->
                em.createQuery(
                        "select i.id from TenantPackageInstance i where i.packageCode = 'old-a'",
                        String.class)
                    .getSingleResult());
    String renewalId =
        tx(
            () -> {
              var instance = new TenantPackageInstance();
              instance.setId(UUID.randomUUID().toString());
              instance.setTenantId(tenantId);
              instance.setPackageCode("renewal");
              instance.setPackageName("Renewal");
              instance.setDurationDays(30);
              instance.setAssignedAt(NOW);
              instance.setAssignedBy("platform-admin");
              instance.setResources(
                  Map.of("paid", new TenantPackageResourceDefinition("test.paid", "paid")));
              em.persist(instance);
              return instance.getId();
            });
    owner();
    proxy(
            new TenantPackageActivationServiceImpl(em, authorization, clock(NOW.plusSeconds(60))),
            TenantPackageActivationService.class)
        .activate(renewalId);
    administrator();
    var revoke =
        proxy(
            new TenantPackageRevocationServiceImpl(em, authorization, clock(NOW.plusSeconds(120))),
            TenantPackageRevocationService.class);
    var receipt = revoke.revoke(tenantId, legacyId);
    assertThat(revoke.revoke(tenantId, legacyId)).isEqualTo(receipt);
    assertThat(balance("paid").getExpiresAt()).isEqualTo(NOW.plusSeconds(120 + 30 * 86400));
    assertThat(balance("shared").getExpiresAt()).isNull();
    assertThat(resource("paid")).isNotNull();
  }

  @Test
  void onlyVerifiedPlatformScopeCanAdoptAndNewTenantsCannotClaimHistoricalRights() {
    owner();
    org.simplepoint.core.AuthorizationContextHolder.getContext().setIsAdministrator(true);
    assertThatThrownBy(() -> service.adopt(tenantId))
        .hasMessageContaining("PLATFORM_ADMIN_REQUIRED");
    administrator();
    tx(
        () -> {
          em.remove(em.find(TenantResourceSetup.class, tenantId));
          return null;
        });
    assertThatThrownBy(() -> service.adopt(tenantId)).hasMessageContaining("ADOPTION_NOT_REQUIRED");
    assertThat(count(TenantResourceCredit.class)).isZero();
  }

  @Test
  void concurrentAdoptionCommitsOnce() throws Exception {
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<TenantLegacyAdoption> action =
          () -> {
            administrator();
            try {
              if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("start timeout");
              }
              return service.adopt(tenantId);
            } finally {
              RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
            }
          };
      var first = executor.submit(action);
      var second = executor.submit(action);
      start.countDown();
      assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(second.get(20, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertThat(count(TenantPackageInstance.class)).isEqualTo(4);
    assertThat(count(TenantResourceCredit.class)).isEqualTo(5);
  }

  @Test
  void deletedPackageRelationsNeverReturnAsHistoricalEntitlements() {
    tx(
        () -> {
          em.createQuery(
                  "update TenantPackageRelevance p set p.deletedAt = :now where p.packageCode ="
                      + " 'old-a'")
              .setParameter("now", NOW.minusSeconds(60))
              .executeUpdate();
          return null;
        });
    service.adopt(tenantId);
    assertThat(resource("paid")).isNull();
    assertThat(balance("paid")).isNull();
    assertThat(balance("shared")).isNotNull();
    assertThat(count(TenantPackageInstance.class)).isEqualTo(3);
  }

  @Test
  void personalAdoptionDoesNotCopyOrganizationOnlyEntitlements() {
    tx(
        () -> {
          em.find(Tenant.class, tenantId).setTenantType(TenantType.PERSONAL);
          for (String id : List.of("root", "base", "public")) {
            em.find(Resource.class, id)
                .setScopeTypes(Set.of(ResourceScopeType.PERSONAL, ResourceScopeType.TENANT));
          }
          return null;
        });
    var receipt = service.adopt(tenantId);
    assertThat(receipt.instanceCount()).isEqualTo(1);
    assertThat(receipt.resourceCount()).isEqualTo(1);
    assertThat(resource("paid")).isNull();
    assertThat(resource("direct")).isNull();
    assertThat(resource("public")).isNotNull();
    assertThat(resource("base").isBaselineEntitled()).isTrue();
  }

  @Test
  void ambiguousHistoricalGrantOwnershipRequiresRepairBeforeAdoption() {
    tx(
        () -> {
          em.createQuery(
                  "update RoleResourceGrant g set g.tenantId = 'another-tenant' where"
                      + " g.resourceCode = 'test.direct'")
              .executeUpdate();
          return null;
        });
    assertThatThrownBy(() -> service.preview(tenantId)).hasMessageContaining("RESOURCE_CONFLICT");
    assertThatThrownBy(() -> service.adopt(tenantId)).hasMessageContaining("RESOURCE_CONFLICT");
    assertThat(count(TenantResourceCredit.class)).isZero();
    assertThat(state()).isEqualTo(TenantResourceSetup.State.ADOPTION_REQUIRED);
  }

  @Test
  void offlineInstallationUsesTheSameAtomicLedgerWithoutLogin() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    var installer = installer();
    assertThat(installer.pending(null, 100)).containsExactly(tenantId);
    assertThat(installer.completed(tenantId)).isEmpty();
    assertThat(installer.preview(tenantId).legacyResourceCount()).isEqualTo(4);
    assertThat(count(TenantResourceCredit.class)).isZero();
    var receipt = installer.adopt(tenantId);
    assertThat(receipt.adoptedBy()).isEqualTo("installation");
    assertThat(receipt.resourceCount()).isEqualTo(4);
    assertThat(installer.adopt(tenantId)).isEqualTo(receipt);
    assertThat(installer.completed(tenantId)).contains(receipt);
    assertThat(installer.pending(null, 100)).isEmpty();
    assertThat(count(TenantResourceCredit.class)).isEqualTo(5);
    assertThatThrownBy(() -> service.adopt(tenantId))
        .hasMessageContaining("PLATFORM_ADMIN_REQUIRED");
  }

  @Test
  void offlineCapabilityCannotBeUsedFromAnAuthorizedRequest() {
    var installer = installer();
    assertThatThrownBy(() -> installer.pending(null, 100))
        .hasMessageContaining("PLATFORM_ADMIN_REQUIRED");
    assertThatThrownBy(() -> installer.preview(tenantId))
        .hasMessageContaining("PLATFORM_ADMIN_REQUIRED");
    assertThatThrownBy(() -> installer.adopt(tenantId))
        .hasMessageContaining("PLATFORM_ADMIN_REQUIRED");
    assertThatThrownBy(() -> installer.completed(tenantId))
        .hasMessageContaining("PLATFORM_ADMIN_REQUIRED");
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    try {
      org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
          new org.springframework.web.context.request.ServletRequestAttributes(
              new org.springframework.mock.web.MockHttpServletRequest()));
      assertThatThrownBy(() -> installer.adopt(tenantId))
          .hasMessageContaining("PLATFORM_ADMIN_REQUIRED");
    } finally {
      org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }
    try {
      org.springframework.security.core.context.SecurityContextHolder.getContext()
          .setAuthentication(
              org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                  .authenticated("user", null, List.of()));
      assertThatThrownBy(() -> installer.adopt(tenantId))
          .hasMessageContaining("PLATFORM_ADMIN_REQUIRED");
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    assertThat(count(TenantResourceCredit.class)).isZero();
  }

  @Test
  void offlineFailureRollsBackReadinessAndCreditsBeforeRetry() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    doThrow(new IllegalStateException("invalidation failed"))
        .when(authorization)
        .refreshTenant(anyString());
    var installer = installer();
    assertThatThrownBy(() -> installer.adopt(tenantId)).hasMessageContaining("invalidation failed");
    assertThat(installer.completed(tenantId)).isEmpty();
    assertThat(installer.pending(null, 100)).containsExactly(tenantId);
    assertThat(count(TenantResourceCredit.class)).isZero();
    assertThat(count(TenantResource.class)).isZero();
    org.mockito.Mockito.reset(authorization);
    assertThat(installer.adopt(tenantId).resourceCount()).isEqualTo(4);
  }

  @Test
  void pendingInventoryUsesBoundedExclusiveCursorsAndExcludesDeletedTenants() {
    String second =
        tx(
            () -> {
              var tenant = new Tenant();
              tenant.setOwnerId("other-owner");
              tenant.setName("Other legacy tenant");
              tenant.setAuthorizationVersion(0L);
              em.persist(tenant);
              var setup = new TenantResourceSetup();
              setup.setTenantId(tenant.getId());
              setup.setState(TenantResourceSetup.State.ADOPTION_REQUIRED);
              em.persist(setup);
              return tenant.getId();
            });
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    var installer = installer();
    var sorted = List.of(tenantId, second).stream().sorted().toList();
    assertThat(installer.pending(null, 1)).containsExactly(sorted.getFirst());
    assertThat(installer.pending(sorted.getFirst(), 1)).containsExactly(sorted.getLast());
    assertThat(installer.pending(sorted.getLast(), 1)).isEmpty();
    assertThatThrownBy(() -> installer.pending(null, 101)).hasMessageContaining("INVALID_REQUEST");
    assertThatThrownBy(() -> installer.pending(" ", 100)).hasMessageContaining("INVALID_REQUEST");
    tx(
        () -> {
          em.find(Tenant.class, second).setDeletedAt(NOW);
          return null;
        });
    assertThat(installer.pending(null, 100)).containsExactly(tenantId);
  }

  @Test
  void inventoryRejectsActiveTenantsMissingTheirMigrationOrInitializationMarker() {
    tx(
        () -> {
          var tenant = new Tenant();
          tenant.setName("Unmarked legacy writer output");
          tenant.setOwnerId("owner");
          tenant.setAuthorizationVersion(0L);
          em.persist(tenant);
          return null;
        });
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    assertThatThrownBy(() -> installer().pending(null, 100))
        .hasMessageContaining("RESOURCE_CONFLICT");
    assertThat(count(TenantResourceCredit.class)).isZero();
  }

  private TenantLegacyInstallationAdapter installer() {
    return new TenantLegacyInstallationAdapter(
        proxy(
            new TenantLegacyAdoptionStore(em, authorization, clock(NOW)),
            TenantLegacyAdoptionStore.class));
  }

  private Resource source(String id, boolean grantable) {
    var source = new Resource();
    source.setId(id);
    source.setCode("test." + id);
    source.setName(id);
    source.setType(ResourceType.PAGE);
    source.setPath("/" + id);
    source.setParentId("root".equals(id) ? null : "root");
    source.setGrantable(grantable);
    source.setDisabled(false);
    source.setPublicAccess(false);
    source.setRequireOrgTenant(false);
    source.setScopeTypes(Set.of(ResourceScopeType.TENANT));
    em.persist(source);
    return source;
  }

  private void link(String application, String resource) {
    var relation = new ApplicationResourceRelevance();
    relation.setApplicationCode(application);
    relation.setResourceCode("test." + resource);
    em.persist(relation);
  }

  private void legacyPackage(String code, List<String> resources) {
    var item = new Package();
    item.setCode(code);
    item.setName(code);
    item.setDurationDays(30);
    item.setEnabled(false);
    em.persist(item);
    var app = new Application();
    app.setCode(code);
    app.setName(code);
    app.setEnabled(false);
    em.persist(app);
    var membership = new PackageApplicationRelevance();
    membership.setPackageCode(code);
    membership.setApplicationCode(code);
    em.persist(membership);
    var held = new TenantPackageRelevance();
    held.setPackageCode(code);
    held.setTenantId(tenantId);
    em.persist(held);
    resources.forEach(resource -> link(code, resource));
  }

  private void grant(Role role, String resource, boolean revoked) {
    var grant = new RoleResourceGrant();
    grant.setRoleId(role.getId());
    grant.setTenantId(tenantId);
    grant.setResourceCode("test." + resource);
    if (revoked) {
      grant.setDeletedAt(NOW.minusSeconds(60));
    }
    em.persist(grant);
  }

  private void administrator() {
    var context = new AuthorizationContext();
    context.setScopeType(AuthorizationScopeType.PLATFORM);
    context.setUserId("platform-admin");
    context.setIsAdministrator(true);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private void owner() {
    var context = new AuthorizationContext();
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setUserId("owner");
    context.setAttributes(Map.of("X-Tenant-Id", tenantId));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private TenantResource resource(String id) {
    return tx(() -> em.find(TenantResource.class, new TenantResourceKey(tenantId, id)));
  }

  private TenantResourceEntitlement balance(String id) {
    return tx(() -> em.find(TenantResourceEntitlement.class, new TenantResourceKey(tenantId, id)));
  }

  private TenantResourceSetup.State state() {
    return tx(() -> em.find(TenantResourceSetup.class, tenantId).getState());
  }

  private long count(Class<?> type) {
    return tx(
        () ->
            em.createQuery("select count(e) from " + type.getSimpleName() + " e", Long.class)
                .getSingleResult());
  }

  private static Clock clock(Instant now) {
    return Clock.fixed(now, ZoneOffset.UTC);
  }

  private <T> T proxy(Object target, Class<T> type) {
    var proxy = new ProxyFactory(target);
    proxy.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    return type.cast(proxy.getProxy());
  }

  private <T> T tx(Supplier<T> action) {
    return new TransactionTemplate(transactions).execute(status -> action.get());
  }
}
