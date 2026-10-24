package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageInstance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageResourceDefinition;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceCredit;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceEntitlement;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageActivation;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageAssignment;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageActivationService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageAssignmentService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageQueryService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageRevocationService;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

class TenantPackageActivationServiceImplTest {
  private static final Instant NOW = Instant.parse("2026-10-23T01:02:03.123456Z");
  private SessionFactory factory;
  private TenantEntitlementDatabase database;
  private EntityManager em;
  private JpaTransactionManager manager;
  private String tenantId;
  private TenantPackageActivationService service;
  private ResourceAuthorizationVersionService authorization;

  @BeforeEach
  void setup() throws Exception {
    database = new TenantEntitlementDatabase();
    factory =
        database.create(
            Resource.class,
            org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup.class,
            Tenant.class,
            TenantResource.class,
            TenantPackageInstance.class,
            TenantResourceCredit.class,
            TenantResourceEntitlement.class,
            TenantPackageResourceDefinition.class,
            Package.class,
            Application.class,
            PackageApplicationRelevance.class,
            ApplicationResourceRelevance.class);
    em = SharedEntityManagerCreator.createSharedEntityManager(factory);
    manager = new JpaTransactionManager(factory);
    tenantId =
        transaction(
            () -> {
              var tenant = new Tenant();
              tenant.setName("Activation tenant");
              tenant.setTenantType(TenantType.ORGANIZATION);
              tenant.setAuthorizationVersion(0L);
              tenant.setOwnerId("owner");
              em.persist(tenant);
              var setup = new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup();
              setup.setTenantId(tenant.getId());
              setup.setState(
                  org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup.State.READY);
              setup.setInitializedAt(NOW);
              em.persist(setup);
              return tenant.getId();
            });
    actor("owner", tenantId);
    authorization = mock(ResourceAuthorizationVersionService.class);
    service = at(NOW);
    transaction(
        () -> {
          for (String id : List.of("customer", "order", "report")) {
            var resource = new Resource();
            resource.setId(id);
            resource.setCode("example." + id);
            resource.setName(id);
            resource.setType(ResourceType.PAGE);
            resource.setScopeTypes(Set.of(ResourceScopeType.TENANT));
            resource.setPublicAccess(false);
            resource.setRequireOrgTenant(false);
            resource.setDisabled(false);
            resource.setGrantable(true);
            em.persist(resource);
          }
          return null;
        });
  }

  @AfterEach
  void cleanup() throws Exception {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    database.close();
  }

  @Test
  void pendingGrantsNothingAndActivationsAccumulateWithStableReceipts() {
    String a = assign(30, "customer", "order", "customer");
    final String b = assign(60, "customer", "report");
    assertThat(count(TenantResourceCredit.class)).isZero();
    assertThat(count(TenantResourceEntitlement.class)).isZero();
    assertThat(count(TenantResource.class)).isZero();
    final var receiptA = service.activate(a);
    service.activate(b);
    assertThat(expiry("customer")).isEqualTo(NOW.plus(Duration.ofDays(90)));
    assertThat(expiry("order")).isEqualTo(NOW.plus(Duration.ofDays(30)));
    assertThat(expiry("report")).isEqualTo(NOW.plus(Duration.ofDays(60)));
    assertThat(at(NOW.plus(Duration.ofDays(10))).activate(a)).isEqualTo(receiptA);
    assertThat(count(TenantResourceCredit.class)).isEqualTo(4);
  }

  @Test
  void concurrentActivationOfTheSameInstanceCreditsOnce() throws Exception {
    String instance = assign(30, "customer");
    var receipts = concurrently(instance, instance);
    assertThat(receipts.get(0)).isEqualTo(receipts.get(1));
    assertThat(expiry("customer")).isEqualTo(NOW.plus(Duration.ofDays(30)));
    assertThat(count(TenantResourceCredit.class)).isEqualTo(1);
  }

  @Test
  void concurrentDifferentInstancesDoNotLoseAnExtension() throws Exception {
    concurrently(assign(30, "customer"), assign(60, "customer"));
    assertThat(expiry("customer")).isEqualTo(NOW.plus(Duration.ofDays(90)));
    assertThat(count(TenantResourceCredit.class)).isEqualTo(2);
  }

  @Test
  void missingSourceLeavesNoImportedResourcesOrCreditsAndTheInstancePending() {
    String instance = assign(30, "customer", "zz-missing");
    assertThatThrownBy(() -> service.activate(instance)).isInstanceOf(TenantPackageException.class);
    assertThat(count(TenantResourceCredit.class)).isZero();
    assertThat(count(TenantResourceEntitlement.class)).isZero();
    assertThat(count(TenantResource.class)).isZero();
    assertThat(transaction(() -> em.find(TenantPackageInstance.class, instance).getState()))
        .isEqualTo(TenantPackageInstance.State.PENDING);
  }

  @Test
  void currentDatabaseOwnerWinsOverAdministratorFlagsAndPreviousOwnership() {
    final String instance = assign(30, "customer");
    actor("administrator", tenantId);
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    context.setIsAdministrator(true);
    context.setActorRole(org.simplepoint.core.AuthorizationActorRole.TENANT_ADMIN);
    assertThatThrownBy(() -> service.activate(instance)).isInstanceOf(TenantPackageException.class);
    actor("owner", tenantId);
    transaction(
        () -> {
          em.find(Tenant.class, tenantId).setOwnerId("new-owner");
          return null;
        });
    assertThatThrownBy(() -> service.activate(instance)).isInstanceOf(TenantPackageException.class);
    actor("new-owner", tenantId);
    assertThat(service.activate(instance).resources()).hasSize(1);
  }

  @Test
  void resourceBalanceCannotLeakBetweenTenantsEvenWithTheSameResourceId() {
    String instance = assign(30, "customer");
    String anotherTenant =
        transaction(
            () -> {
              var tenant = new Tenant();
              tenant.setName("Another tenant");
              tenant.setOwnerId("owner");
              tenant.setTenantType(TenantType.ORGANIZATION);
              tenant.setAuthorizationVersion(0L);
              em.persist(tenant);
              var setup = new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup();
              setup.setTenantId(tenant.getId());
              setup.setState(
                  org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup.State.READY);
              setup.setInitializedAt(NOW);
              em.persist(setup);
              return tenant.getId();
            });
    actor("owner", anotherTenant);
    assertThatThrownBy(() -> service.activate(instance)).isInstanceOf(TenantPackageException.class);
    assertThat(count(TenantResourceCredit.class)).isZero();
  }

  @Test
  void revokedInstancesAndDamagedReceiptsCannotBeReplayed() {
    String pending = assign(30, "customer");
    transaction(
        () -> {
          var instance = em.find(TenantPackageInstance.class, pending);
          instance.setState(TenantPackageInstance.State.REVOKED);
          instance.setRevokedAt(NOW);
          return null;
        });
    assertThatThrownBy(() -> service.activate(pending)).isInstanceOf(TenantPackageException.class);
    String activated = assign(30, "customer");
    service.activate(activated);
    transaction(
        () -> {
          em.createQuery("delete from TenantResourceCredit c where c.instanceId = :id")
              .setParameter("id", activated)
              .executeUpdate();
          return null;
        });
    assertThatThrownBy(() -> service.activate(activated))
        .isInstanceOf(TenantPackageException.class);
    assertThat(expiry("customer")).isEqualTo(NOW.plus(Duration.ofDays(30)));
    assertThat(count(TenantResourceCredit.class)).isZero();
  }

  @Test
  void failedAuthorizationInvalidationRollsBackTheEntireActivation() {
    String instance = assign(30, "customer");
    org.mockito.Mockito.doThrow(new IllegalStateException("Simulated invalidation failure"))
        .when(authorization)
        .refreshTenant(tenantId);
    assertThatThrownBy(() -> service.activate(instance)).isInstanceOf(IllegalStateException.class);
    assertThat(count(TenantResourceCredit.class)).isZero();
    assertThat(count(TenantResourceEntitlement.class)).isZero();
    assertThat(count(TenantResource.class)).isZero();
    assertThat(transaction(() -> em.find(TenantPackageInstance.class, instance).getState()))
        .isEqualTo(TenantPackageInstance.State.PENDING);
  }

  @Test
  void expiryRenewalAndUnlimitedArePersistedWithoutResettingExistingCredit() {
    service.activate(assign(30, "customer"));
    at(NOW.plus(Duration.ofDays(40))).activate(assign(60, "customer"));
    assertThat(expiry("customer")).isEqualTo(NOW.plus(Duration.ofDays(100)));
    at(NOW.plus(Duration.ofDays(41))).activate(assign(null, "customer"));
    assertThat(expiry("customer")).isNull();
    at(NOW.plus(Duration.ofDays(42))).activate(assign(10, "customer"));
    assertThat(expiry("customer")).isNull();
    assertThat(count(TenantResourceCredit.class)).isEqualTo(4);
  }

  @Test
  void allocationFreezesDurationAndDeduplicatesResourcesWithoutStartingTime() {
    definePackage();
    platformActor();
    var allocation =
        new TenantPackageAssignment(tenantId, "purchased", UUID.randomUUID().toString());
    var assigned = assignmentService().assign(allocation);
    assertThat(assigned.state()).isEqualTo(TenantPackageInstance.State.PENDING);
    assertThat(assigned.resourceCount()).isEqualTo(1);
    assertThat(assigned.activatedAt()).isNull();
    assertThat(count(TenantResource.class)).isZero();
    assertThat(count(TenantResourceCredit.class)).isZero();
    transaction(
        () -> {
          em.createQuery("update Package p set p.durationDays = 90 where p.code = 'purchased'")
              .executeUpdate();
          return null;
        });
    assertThat(assignmentService().assign(allocation)).isEqualTo(assigned);
    assertThat(
            assignmentService()
                .assign(
                    new TenantPackageAssignment(
                        tenantId, "purchased", UUID.randomUUID().toString()))
                .durationDays())
        .isEqualTo(90);
    actor("owner", tenantId);
    assertThat(service.activate(assigned.id()).resources()).hasSize(1);
    assertThat(expiry("customer")).isEqualTo(NOW.plus(Duration.ofDays(30)));
  }

  @Test
  void explicitlyPackagedNonGrantableHostsReceiveTimeWithoutBecomingRolePermissions() {
    definePackage();
    transaction(
        () -> {
          var host = em.find(Resource.class, "customer");
          host.setGrantable(false);
          host.setPublicAccess(false);
          host.setComponent("app/FormRuntime");
          host.setRouteKind("hidden");
          return null;
        });
    platformActor();
    var assigned =
        assignmentService()
            .assign(
                new TenantPackageAssignment(tenantId, "purchased", UUID.randomUUID().toString()));
    assertThat(assigned.resourceCount()).isEqualTo(1);
    actor("owner", tenantId);
    service.activate(assigned.id());
    var mirror = resource("customer");
    assertThat(mirror.isGrantable()).isFalse();
    assertThat(mirror.isPublicAccess()).isFalse();
    assertThat(mirror.getComponent()).isEqualTo("app/FormRuntime");
    assertThat(expiry("customer")).isEqualTo(NOW.plus(Duration.ofDays(30)));
    // An unrelated non-grantable resource is never added merely because its endpoint is public.
    assertThat(resource("order")).isNull();
    transaction(
        () -> {
          var query = new TenantResourceReadQuery(em, tenantId, NOW);
          assertThat(query.eligibleHostIds(Set.of("customer"))).containsExactly("customer");
          assertThat(query.authorization(Set.of("example.customer"), true).resourceCodes())
              .isEmpty();
          assertThat(
                  new TenantResourceReadQuery(em, tenantId, NOW.plus(Duration.ofDays(30)))
                      .eligibleHostIds(Set.of("customer")))
              .isEmpty();
          return null;
        });
  }

  @Test
  void allocationRequiresPlatformAdministratorAndRejectsConflictingRetry() {
    definePackage();
    var allocation =
        new TenantPackageAssignment(tenantId, "purchased", UUID.randomUUID().toString());
    org.simplepoint.core.AuthorizationContextHolder.getContext().setIsAdministrator(true);
    assertThatThrownBy(() -> assignmentService().assign(allocation))
        .isInstanceOf(TenantPackageException.class)
        .hasMessageContaining("PLATFORM_ADMIN_REQUIRED");
    platformActor();
    assignmentService().assign(allocation);
    assertThatThrownBy(
            () ->
                assignmentService()
                    .assign(
                        new TenantPackageAssignment(
                            tenantId, "different", allocation.allocationId())))
        .isInstanceOf(TenantPackageException.class)
        .hasMessageContaining("ALLOCATION_CONFLICT");
    assertThat(count(TenantPackageInstance.class)).isEqualTo(1);
  }

  @Test
  void disabledApplicationsAndDeletedRelationsCannotIssueResources() {
    definePackage();
    transaction(
        () -> {
          em.createQuery("update Application a set a.enabled = false where a.code = 'sales'")
              .executeUpdate();
          em.createQuery(
                  "update ApplicationResourceRelevance ar set ar.deletedAt = :now "
                      + "where ar.applicationCode = 'sales-copy'")
              .setParameter("now", NOW)
              .executeUpdate();
          return null;
        });
    platformActor();
    assertThatThrownBy(
            () ->
                assignmentService()
                    .assign(
                        new TenantPackageAssignment(
                            tenantId, "purchased", UUID.randomUUID().toString())))
        .isInstanceOf(TenantPackageException.class)
        .hasMessageContaining("RESOURCE_UNAVAILABLE");
    assertThat(count(TenantPackageInstance.class)).isZero();
  }

  @Test
  void activationImportsAncestorsAndMetadataWithoutGrantingAncestorTime() {
    transaction(
        () -> {
          var parent = em.find(Resource.class, "order");
          parent.setType(ResourceType.GROUP);
          parent.setGrantable(false);
          var child = em.find(Resource.class, "customer");
          child.setParentId(parent.getId());
          child.setPath("/customers");
          child.setPermissionMetadata("{\"fields\":[]}");
          child.setMethod("GET");
          return null;
        });
    service.activate(assign(30, "customer"));
    var child = resource("customer");
    assertThat(child.getPlatformResourceId()).isEqualTo("customer");
    assertThat(child.getParentId()).isEqualTo("order");
    assertThat(child.getPermissionMetadata()).isEqualTo("{\"fields\":[]}");
    assertThat(child.getMethod()).isEqualTo("GET");
    assertThat(child.getCreatedAt()).isEqualTo(NOW);
    assertThat(child.getUpdatedAt()).isEqualTo(NOW);
    assertThat(child.getLastSyncedAt()).isEqualTo(NOW);
    assertThat(count(TenantResource.class)).isEqualTo(2);
    assertThat(count(TenantResourceEntitlement.class)).isEqualTo(1);
    assertThat(resource("order").isGrantable()).isFalse();
  }

  @Test
  void unchangedDefinitionOnlyUpdatesSyncTimeAndCurrentDefinitionPreservesDisplayOverrides() {
    service.activate(assign(30, "customer"));
    Instant nextDay = NOW.plus(Duration.ofDays(1));
    at(nextDay).activate(assign(30, "customer"));
    assertThat(resource("customer").getUpdatedAt()).isEqualTo(NOW);
    assertThat(resource("customer").getLastSyncedAt()).isEqualTo(nextDay);
    transaction(
        () -> {
          var target = em.find(TenantResource.class, new TenantResourceKey(tenantId, "customer"));
          target.setName("Local name");
          target.setLocalModified(true);
          target.setManualDisabled(true);
          var source = em.find(Resource.class, "customer");
          source.setName("Current platform name");
          source.setPath("/new-customer-path");
          return null;
        });
    Instant updateTime = nextDay.plus(Duration.ofDays(1));
    at(updateTime).activate(assign(30, "customer"));
    var updated = resource("customer");
    assertThat(updated.getName()).isEqualTo("Local name");
    assertThat(updated.getPath()).isEqualTo("/new-customer-path");
    assertThat(updated.isEnabled()).isFalse();
    assertThat(updated.getCreatedAt()).isEqualTo(NOW);
    assertThat(updated.getUpdatedAt()).isEqualTo(updateTime);
    assertThat(updated.getLastSyncedAt()).isEqualTo(updateTime);
  }

  @Test
  void cyclicPlatformTreeCannotPartiallyImportOrActivate() {
    transaction(
        () -> {
          em.find(Resource.class, "customer").setParentId("order");
          em.find(Resource.class, "order").setParentId("customer");
          return null;
        });
    assertThatThrownBy(() -> service.activate(assign(30, "customer")))
        .isInstanceOf(TenantPackageException.class)
        .hasMessageContaining("RESOURCE_UNAVAILABLE");
    assertThat(count(TenantResource.class)).isZero();
    assertThat(count(TenantResourceCredit.class)).isZero();
  }

  @Test
  void existingCustomPathCannotBeOverwrittenByImportedPlatformResource() {
    transaction(
        () -> {
          em.find(Resource.class, "customer").setPath("/shared-path");
          var local = new TenantResource();
          local.setTenantId(tenantId);
          local.setId("custom");
          local.setCode("tenant.custom");
          local.setName("Custom resource");
          local.setType(ResourceType.PAGE);
          local.setPath("/shared-path");
          em.persist(local);
          return null;
        });
    String instance = assign(30, "customer");
    assertThatThrownBy(() -> service.activate(instance))
        .isInstanceOf(TenantPackageException.class)
        .hasMessageContaining("RESOURCE_CONFLICT");
    assertThat(count(TenantResource.class)).isEqualTo(1);
    assertThat(resource("custom").getName()).isEqualTo("Custom resource");
    assertThat(count(TenantResourceCredit.class)).isZero();
    assertThat(transaction(() -> em.find(TenantPackageInstance.class, instance).getState()))
        .isEqualTo(TenantPackageInstance.State.PENDING);
  }

  @Test
  void ownerQueriesApplyFiltersOrderingCountsAndTenantBoundaryBeforePagination() {
    final String older = assignAt(NOW.minusSeconds(60), 60, "customer", "order");
    String newer = assign(30, "customer");
    final String foreignId =
        transaction(
            () -> {
              var other = new Tenant();
              other.setName("Foreign tenant");
              other.setTenantType(TenantType.ORGANIZATION);
              other.setOwnerId("owner");
              other.setAuthorizationVersion(0L);
              em.persist(other);
              var foreign = new TenantPackageInstance();
              foreign.setId(UUID.randomUUID().toString());
              foreign.setTenantId(other.getId());
              foreign.setPackageCode("foreign");
              foreign.setPackageName("Foreign package");
              foreign.setAssignedAt(NOW.plusSeconds(60));
              foreign.setAssignedBy("platform-admin");
              foreign.setDurationDays(30);
              foreign.setResources(
                  Map.of(
                      "report", new TenantPackageResourceDefinition("example.report", "Report")));
              em.persist(foreign);
              return foreign.getId();
            });
    var queries = queryService();
    var first = queries.query(Map.of(), PageRequest.of(0, 1));
    assertThat(first.getTotalElements()).isEqualTo(2);
    assertThat(first.getContent()).extracting(row -> row.id()).containsExactly(newer);
    assertThat(queries.query(Map.of(), PageRequest.of(1, 1)).getContent())
        .extracting(row -> row.id())
        .containsExactly(older);
    assertThat(queries.query(Map.of(), PageRequest.of(0, 1, Sort.by("assignedAt"))).getContent())
        .extracting(row -> row.id())
        .containsExactly(older);
    var filtered = queries.query(Map.of("resourceCount", "2"), PageRequest.of(0, 10));
    assertThat(filtered.getTotalElements()).isEqualTo(1);
    assertThat(filtered.getContent()).extracting(row -> row.id()).containsExactly(older);
    assertThat(queries.get(newer).resourceCount()).isEqualTo(1);
    assertThatThrownBy(() -> queries.get(foreignId)).hasMessageContaining("INSTANCE_NOT_FOUND");
    assertThat(queries.schema()).containsKeys("schema", "buttons");
  }

  @Test
  void allPackageReadsRevalidateOwnerAndSupportPersonalTenants() {
    final String instance = assign(30, "customer");
    var queries = queryService();
    actor("administrator", tenantId);
    org.simplepoint.core.AuthorizationContextHolder.getContext().setIsAdministrator(true);
    assertThatThrownBy(queries::schema).hasMessageContaining("OWNER_REQUIRED");
    assertThatThrownBy(() -> queries.query(Map.of(), PageRequest.of(0, 10)))
        .hasMessageContaining("OWNER_REQUIRED");
    assertThatThrownBy(() -> queries.get(instance)).hasMessageContaining("OWNER_REQUIRED");
    actor("owner", tenantId);
    transaction(
        () -> {
          em.find(Tenant.class, tenantId).setTenantType(TenantType.PERSONAL);
          return null;
        });
    assertThatThrownBy(() -> queries.get(instance)).hasMessageContaining("OWNER_REQUIRED");
    var personal = new AuthorizationContext();
    personal.setUserId("owner");
    personal.setScopeType(AuthorizationScopeType.PERSONAL);
    personal.setAttributes(Map.of("X-Tenant-Id", tenantId));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, personal);
    assertThat(queries.get(instance).id()).isEqualTo(instance);
    transaction(
        () -> {
          em.find(Tenant.class, tenantId).setOwnerId("new-owner");
          return null;
        });
    assertThatThrownBy(() -> queries.get(instance)).hasMessageContaining("OWNER_REQUIRED");
  }

  @Test
  void revocationRemovesOnlyUnconsumedCreditAndRetainsReceiptsAndResourceDefinitions() {
    String first = assign(30, "customer");
    service.activate(first);
    final String second = assign(60, "customer");
    final var secondReceipt = at(NOW.plus(Duration.ofDays(10))).activate(second);
    platformActor();
    final var revoked = revocationsAt(NOW.plus(Duration.ofDays(20))).revoke(tenantId, first);
    assertThat(expiry("customer")).isEqualTo(NOW.plus(Duration.ofDays(80)));
    assertThat(revocationsAt(NOW.plus(Duration.ofDays(40))).revoke(tenantId, first))
        .isEqualTo(revoked);
    assertThat(count(TenantResource.class)).isEqualTo(1);
    assertThat(count(TenantResourceCredit.class)).isEqualTo(2);
    assertThat(transaction(() -> em.find(TenantPackageInstance.class, first).getRevokedBy()))
        .isEqualTo("platform-admin");
    actor("owner", tenantId);
    assertThat(at(NOW.plus(Duration.ofDays(40))).activate(second)).isEqualTo(secondReceipt);
    assertThatThrownBy(() -> service.activate(first)).hasMessageContaining("INSTANCE_REVOKED");
  }

  @Test
  void pendingRevocationNeverImportsResourcesAndRequiresActualPlatformScope() {
    String instance = assign(30, "customer");
    org.simplepoint.core.AuthorizationContextHolder.getContext().setIsAdministrator(true);
    assertThatThrownBy(() -> revocationsAt(NOW).revoke(tenantId, instance))
        .hasMessageContaining("PLATFORM_ADMIN_REQUIRED");
    platformActor();
    var receipt = revocationsAt(NOW).revoke(tenantId, instance);
    assertThat(revocationsAt(NOW.plusSeconds(1)).revoke(tenantId, instance)).isEqualTo(receipt);
    assertThat(count(TenantResource.class)).isZero();
    assertThat(count(TenantResourceCredit.class)).isZero();
    assertThat(count(TenantResourceEntitlement.class)).isZero();
  }

  @Test
  void revokingUnlimitedReleasesQueuedFinitePurchaseAndLastRevocationRemovesTheBalance() {
    String unlimited = assign(null, "customer");
    service.activate(unlimited);
    String finite = assign(10, "customer");
    at(NOW.plus(Duration.ofDays(5))).activate(finite);
    platformActor();
    revocationsAt(NOW.plus(Duration.ofDays(20))).revoke(tenantId, unlimited);
    assertThat(expiry("customer")).isEqualTo(NOW.plus(Duration.ofDays(30)));
    revocationsAt(NOW.plus(Duration.ofDays(21))).revoke(tenantId, finite);
    assertThat(count(TenantResourceEntitlement.class)).isZero();
    assertThat(count(TenantResource.class)).isEqualTo(1);
    assertThat(count(TenantResourceCredit.class)).isEqualTo(2);
  }

  @Test
  void failedRevocationInvalidationRollsBackStateCreditAndBalanceTogether() {
    String instance = assign(30, "customer");
    service.activate(instance);
    org.mockito.Mockito.doThrow(new IllegalStateException("Simulated invalidation failure"))
        .when(authorization)
        .refreshTenant(tenantId);
    platformActor();
    assertThatThrownBy(() -> revocationsAt(NOW.plusSeconds(1)).revoke(tenantId, instance))
        .isInstanceOf(IllegalStateException.class);
    assertThat(expiry("customer")).isEqualTo(NOW.plus(Duration.ofDays(30)));
    assertThat(transaction(() -> em.find(TenantPackageInstance.class, instance).getState()))
        .isEqualTo(TenantPackageInstance.State.ACTIVE);
    assertThat(
            transaction(
                () ->
                    em.createQuery(
                            "select count(c) from TenantResourceCredit c where c.revokedAt is not"
                                + " null",
                            Long.class)
                        .getSingleResult()))
        .isZero();
  }

  @Test
  void revocationSerializesWithRenewalWithoutLosingRemainingTime() throws Exception {
    String first = assign(30, "customer");
    service.activate(first);
    String renewal = assign(60, "customer");
    final Instant moment = NOW.plus(Duration.ofDays(20));
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var revoked =
          executor.submit(
              () -> {
                platformActor();
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                  return revocationsAt(moment).revoke(tenantId, first);
                } finally {
                  RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
                }
              });
      var activated =
          executor.submit(
              () -> {
                actor("owner", tenantId);
                try {
                  barrier.await(5, TimeUnit.SECONDS);
                  return at(moment).activate(renewal);
                } finally {
                  RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
                }
              });
      assertThat(revoked.get(15, TimeUnit.SECONDS).instanceId()).isEqualTo(first);
      assertThat(activated.get(15, TimeUnit.SECONDS).instanceId()).isEqualTo(renewal);
    }
    assertThat(expiry("customer")).isEqualTo(NOW.plus(Duration.ofDays(80)));
    assertThat(count(TenantResourceCredit.class)).isEqualTo(2);
  }

  private TenantPackageRevocationService revocationsAt(Instant instant) {
    var target =
        new TenantPackageRevocationServiceImpl(
            em, authorization, Clock.fixed(instant, ZoneOffset.UTC));
    var proxy = new ProxyFactory(target);
    proxy.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    return (TenantPackageRevocationService) proxy.getProxy();
  }

  private TenantPackageQueryService queryService() {
    return queryAt(NOW);
  }

  private TenantPackageQueryService queryAt(Instant instant) {
    var proxy =
        new ProxyFactory(
            new TenantPackageQueryServiceImpl(em, Clock.fixed(instant, ZoneOffset.UTC)));
    proxy.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    return (TenantPackageQueryService) proxy.getProxy();
  }

  @Test
  void pendingContentRetainsAssignedLabelsWithoutReadingPlatformDefinitions() {
    definePackage();
    platformActor();
    String instance =
        assignmentService()
            .assign(
                new TenantPackageAssignment(tenantId, "purchased", UUID.randomUUID().toString()))
            .id();
    transaction(
        () -> {
          em.find(Resource.class, "customer").setName("Renamed platform resource");
          return null;
        });
    actor("owner", tenantId);
    var details = queryService().resources(instance, PageRequest.of(0, 10));
    assertThat(details.getTotalElements()).isEqualTo(1);
    var resource = details.getContent().getFirst();
    assertThat(resource.resourceCode()).isEqualTo("example.customer");
    assertThat(resource.resourceName()).isEqualTo("customer");
    assertThat(resource.activatedAt()).isNull();
    assertThat(resource.currentState().name()).isEqualTo("NONE");
    assertThat(count(TenantResource.class)).isZero();
    assertThat(queryService().get(instance).getAllowedActions())
        .containsExactly("resources", "activate");
  }

  @Test
  void detailPagesKeepOriginalCreditAndCurrentExpirySeparateAndEnforceExactExpiry() {
    String first = assign(30, "customer", "order");
    service.activate(first);
    at(NOW.plus(Duration.ofDays(10))).activate(assign(60, "customer"));
    var details =
        queryAt(NOW.plus(Duration.ofDays(20)))
            .resources(first, PageRequest.of(0, 1, Sort.by("resourceName")));
    assertThat(details.getTotalElements()).isEqualTo(2);
    var customer = details.getContent().getFirst();
    assertThat(customer.id()).isEqualTo("customer");
    assertThat(customer.creditedExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    assertThat(customer.currentExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
    assertThat(customer.currentState().name()).isEqualTo("ACTIVE");
    assertThat(
            queryAt(NOW.plus(Duration.ofDays(90)))
                .resources(first, PageRequest.of(0, 1, Sort.by("resourceName")))
                .getContent()
                .getFirst()
                .currentState()
                .name())
        .isEqualTo("EXPIRED");
    assertThat(
            queryService()
                .resources(first, PageRequest.of(1, 1, Sort.by("resourceName")))
                .getContent())
        .extracting(row -> row.id())
        .containsExactly("order");
    assertThat(queryService().get(first).getAllowedActions()).containsExactly("resources");
    assertThatThrownBy(() -> queryService().resources(first, PageRequest.of(0, 501)))
        .hasMessageContaining("INVALID_REQUEST");
    assertThatThrownBy(
            () -> queryService().resources(first, PageRequest.of(0, 10, Sort.by("tenantId"))))
        .hasMessageContaining("INVALID_REQUEST");
    actor("ordinary-member", tenantId);
    assertThatThrownBy(() -> queryService().resources(first, PageRequest.of(0, 10)))
        .hasMessageContaining("OWNER_REQUIRED");
  }

  private TenantResource resource(String id) {
    return transaction(() -> em.find(TenantResource.class, new TenantResourceKey(tenantId, id)));
  }

  private TenantPackageAssignmentService assignmentService() {
    var proxy = new ProxyFactory(new TenantPackageAssignmentServiceImpl(em));
    proxy.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    return (TenantPackageAssignmentService) proxy.getProxy();
  }

  private void platformActor() {
    var context = new AuthorizationContext();
    context.setScopeType(AuthorizationScopeType.PLATFORM);
    context.setUserId("platform-admin");
    context.setIsAdministrator(true);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private void definePackage() {
    transaction(
        () -> {
          var definition = new Package();
          definition.setCode("purchased");
          definition.setName("Purchased package");
          definition.setEnabled(true);
          definition.setDurationDays(30);
          em.persist(definition);
          for (String code : List.of("sales", "sales-copy")) {
            var application = new Application();
            application.setCode(code);
            application.setName(code);
            application.setEnabled(true);
            em.persist(application);
            var member = new PackageApplicationRelevance();
            member.setPackageCode(definition.getCode());
            member.setApplicationCode(code);
            em.persist(member);
            var resource = new ApplicationResourceRelevance();
            resource.setApplicationCode(code);
            resource.setResourceCode("example.customer");
            em.persist(resource);
          }
          return null;
        });
  }

  private List<TenantPackageActivation> concurrently(String first, String second) throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures =
          List.of(first, second).stream()
              .map(
                  id ->
                      executor.submit(
                          (Callable<TenantPackageActivation>)
                              () -> {
                                actor("owner", tenantId);
                                try {
                                  barrier.await(5, TimeUnit.SECONDS);
                                  return service.activate(id);
                                } finally {
                                  RequestContextHolder.clearContext(
                                      RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
                                }
                              }))
              .toList();
      return List.of(
          futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
    }
  }

  private TenantPackageActivationService at(Instant instant) {
    var target =
        new TenantPackageActivationServiceImpl(
            em, authorization, Clock.fixed(instant, ZoneOffset.UTC));
    var proxy = new ProxyFactory(target);
    proxy.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    return (TenantPackageActivationService) proxy.getProxy();
  }

  private void actor(String userId, String tenant) {
    var context = new AuthorizationContext();
    context.setUserId(userId);
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", tenant));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private String assign(Integer days, String... resources) {
    return assignAt(NOW, days, resources);
  }

  private String assignAt(Instant assignedAt, Integer days, String... resources) {
    return transaction(
        () -> {
          var instance = new TenantPackageInstance();
          instance.setId(UUID.randomUUID().toString());
          instance.setTenantId(tenantId);
          instance.setPackageCode("example");
          instance.setPackageName("Example package");
          instance.setDurationDays(days);
          instance.setAssignedAt(assignedAt);
          instance.setAssignedBy("platform-admin");
          var definitions = new LinkedHashMap<String, TenantPackageResourceDefinition>();
          for (String resource : resources) {
            definitions.put(
                resource, new TenantPackageResourceDefinition("example." + resource, resource));
          }
          instance.setResources(definitions);
          em.persist(instance);
          return instance.getId();
        });
  }

  private Instant expiry(String resourceId) {
    return transaction(
        () ->
            em.find(TenantResourceEntitlement.class, new TenantResourceKey(tenantId, resourceId))
                .getExpiresAt());
  }

  private long count(Class<?> type) {
    return transaction(
        () ->
            em.createQuery("select count(e) from " + type.getSimpleName() + " e", Long.class)
                .getSingleResult());
  }

  private <T> T transaction(Supplier<T> action) {
    return new TransactionTemplate(manager).execute(status -> action.get());
  }
}
