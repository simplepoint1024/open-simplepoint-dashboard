package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceEntitlement;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantUserRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.PersonalTenantProvisioningService;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceProvisioningService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.simplepoint.security.entity.User;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

class TenantResourceProvisioningServiceImplTest {
  private static final Instant NOW = Instant.parse("2026-10-23T01:00:00.123456Z");
  private TenantEntitlementDatabase database;
  private EntityManager em;
  private JpaTransactionManager transactions;
  private ResourceAuthorizationVersionService authorization;
  private String tenantId;
  private TenantResourceProvisioningService service;

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
            TenantResourceEntitlement.class,
            TenantUserRelevance.class,
            User.class);
    em = SharedEntityManagerCreator.createSharedEntityManager(factory);
    transactions = new JpaTransactionManager(factory);
    authorization = mock(ResourceAuthorizationVersionService.class);
    service = at(NOW);
    tenantId =
        transaction(
            () -> {
              var tenant = new Tenant();
              tenant.setName("Provisioning tenant");
              tenant.setOwnerId("owner");
              tenant.setTenantType(TenantType.ORGANIZATION);
              tenant.setAuthorizationVersion(0L);
              em.persist(tenant);
              for (String code : BuiltInTenantCodes.BASELINE_APPLICATIONS) {
                var application = new Application();
                application.setCode(code);
                application.setName(code);
                application.setEnabled(true);
                em.persist(application);
              }
              Resource root = source("root", null, false);
              root.setType(ResourceType.GROUP);
              source("dashboard", "root", true);
              source("upload", "root", true);
              source("unlicensed-public", "root", true).setPublicAccess(true);
              source("paid", "root", true);
              link("dashboard", BuiltInTenantCodes.CORE_APPLICATION);
              link("upload", BuiltInTenantCodes.STORAGE_APPLICATION);
              return tenant.getId();
            });
    owner("owner", AuthorizationScopeType.TENANT);
  }

  @AfterEach
  void cleanup() throws Exception {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    SecurityContextHolder.clearContext();
    database.close();
  }

  @Test
  void concurrentFirstImportWaitsForSourceCommitBeforeLockingTenant() throws Exception {
    try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var entered = new java.util.concurrent.CountDownLatch(1);
      var result =
          new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
      transaction(
          () -> {
            em.createNativeQuery(
                    "update {h-schema}simpoint_resource_catalog_revision"
                        + " set revision=revision+1 where id='catalog'")
                .executeUpdate();
            em.find(Resource.class, "dashboard").setName("Current committed definition");
            em.flush();
            result.set(
                pool.submit(
                    () -> {
                      entered.countDown();
                      initialize();
                    }));
            try {
              assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
              assertThatThrownBy(
                      () -> result.get().get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                  .isInstanceOf(java.util.concurrent.TimeoutException.class);
              // The importing transaction must not already hold the tenant lock while waiting for
              // source.
              em.createNativeQuery(
                      "select id from {h-schema}simpoint_saas_tenants where id=:id for update"
                          + " nowait")
                  .setParameter("id", tenantId)
                  .getSingleResult();
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(error);
            }
            return null;
          });
      result.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(resource("dashboard").getName()).isEqualTo("Current committed definition");
    }
  }

  @Test
  void sourceUpdateWaitsForFirstImportThenPropagatesToItsNewMirror() throws Exception {
    try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var entered = new java.util.concurrent.CountDownLatch(1);
      var result =
          new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
      transaction(
          () -> {
            service.initialize(tenantId);
            em.flush();
            result.set(
                pool.submit(
                    () ->
                        transaction(
                            () -> {
                              entered.countDown();
                              em.createNativeQuery(
                                      "update {h-schema}simpoint_resource_catalog_revision"
                                          + " set revision=revision+1 where id='catalog'")
                                  .executeUpdate();
                              em.find(Resource.class, "dashboard").setDisabled(true);
                              em.flush();
                              new PlatformResourceCatalogEffectsImpl(em, authorization)
                                  .synchronize(Set.of("dashboard"));
                              return null;
                            })));
            try {
              assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
              assertThatThrownBy(
                      () -> result.get().get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                  .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(error);
            }
            return null;
          });
      result.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(resource("dashboard").isSourceAvailable()).isFalse();
      assertThat(resource("dashboard").isEnabled()).isFalse();
    }
  }

  @Test
  void missingCatalogFenceRejectsInitializationWithoutPartialDirectory() {
    transaction(
        () -> {
          em.createNativeQuery("delete from {h-schema}simpoint_resource_catalog_revision")
              .executeUpdate();
          return null;
        });
    assertThatThrownBy(this::initialize)
        .isInstanceOf(
            org.simplepoint.plugin.rbac.tenant.api.error.TenantResourceSetupException.class)
        .hasMessageContaining("RESOURCE_UNAVAILABLE");
    assertThat(count(TenantResource.class)).isZero();
    assertThat(count(TenantResourceSetup.class)).isZero();
  }

  @Test
  void
      explicitBaselineImportsNonGrantablePublicPagesAndActionsWithoutMakingAllPublicResourcesFree() {
    transaction(
        () -> {
          source("items", "root", false).setPublicAccess(true);
          var action = source("items-add", "items", false);
          action.setType(ResourceType.ACTION);
          action.setPublicAccess(true);
          source("private-internal", "root", false);
          link("items", BuiltInTenantCodes.CORE_APPLICATION);
          link("items-add", BuiltInTenantCodes.CORE_APPLICATION);
          link("private-internal", BuiltInTenantCodes.CORE_APPLICATION);
          return null;
        });
    initialize();
    assertThat(resource("items").isBaselineEntitled()).isTrue();
    assertThat(resource("items-add").isBaselineEntitled()).isTrue();
    assertThat(resource("unlicensed-public")).isNull();
    assertThat(resource("private-internal")).isNull();
    var selection =
        transaction(
            () -> new TenantResourceReadQuery(em, tenantId, NOW).authorization(Set.of(), false));
    assertThat(selection.resourceCodes()).containsExactlyInAnyOrder("test.items", "test.items-add");
    assertThat(
            transaction(
                () ->
                    new TenantResourceReadQuery(em, tenantId, NOW)
                        .authorizationCodes(Set.of("test.items", "test.items-add"), false)))
        .isEmpty();
  }

  @Test
  void initializationRequiresCreationTransactionAndImportsOnlyExplicitBaseline() {
    assertThatThrownBy(() -> service.initialize(tenantId))
        .isInstanceOf(IllegalTransactionStateException.class);
    initialize();
    assertThat(count(TenantResource.class)).isEqualTo(3);
    assertThat(resource("dashboard").getPlatformResourceId()).isEqualTo("dashboard");
    assertThat(resource("dashboard").isBaselineEntitled()).isTrue();
    assertThat(resource("root").isBaselineEntitled()).isFalse();
    assertThat(resource("unlicensed-public")).isNull();
    assertThat(resource("paid")).isNull();
    assertThat(count(TenantResourceEntitlement.class)).isZero();
    assertThat(transaction(() -> em.find(TenantResourceSetup.class, tenantId).getInitializedAt()))
        .isEqualTo(NOW);
    clearInvocations(authorization);
    initialize();
    verify(authorization, never()).refreshTenant(tenantId);
  }

  @Test
  void previewsDoNotWriteAndExecutionReadsCurrentDefinitionsAgain() {
    initialize();
    transaction(
        () -> {
          source("new-public", "root", true);
          link("new-public", BuiltInTenantCodes.CORE_APPLICATION);
          em.find(Resource.class, "dashboard").setName("Previewed title");
          return null;
        });
    var later = at(NOW.plusSeconds(60));
    var preview = later.preview(false);
    assertThat(preview.added()).isEqualTo(1);
    assertThat(count(TenantResource.class)).isEqualTo(3);
    assertThat(resource("dashboard").getName()).isEqualTo("dashboard");
    assertThat(resource("dashboard").getLastSyncedAt()).isEqualTo(NOW);
    transaction(
        () -> {
          em.find(Resource.class, "dashboard").setName("Current title");
          return null;
        });
    assertThat(later.synchronize(false).added()).isEqualTo(1);
    assertThat(resource("dashboard").getName()).isEqualTo("Current title");
    assertThat(resource("new-public").isBaselineEntitled()).isTrue();
    assertThat(resource("dashboard").getCreatedAt()).isEqualTo(NOW);
    assertThat(count(TenantResourceEntitlement.class)).isZero();
  }

  @Test
  void unchangedSynchronizationOnlyChangesLastSyncTime() {
    initialize();
    clearInvocations(authorization);
    var result = at(NOW.plusSeconds(60)).synchronize(false);
    assertThat(result.refreshed()).isZero();
    assertThat(resource("dashboard").getUpdatedAt()).isEqualTo(NOW);
    assertThat(resource("dashboard").getLastSyncedAt()).isEqualTo(NOW.plusSeconds(60));
    verify(authorization, never()).refreshTenant(tenantId);
  }

  @Test
  void currentExecutionMetadataUpdatesWhileDisplayOverridesAndDisablementSurvive() {
    initialize();
    transaction(
        () -> {
          var current = em.find(TenantResource.class, new TenantResourceKey(tenantId, "dashboard"));
          current.setName("My title");
          current.setLocalModified(true);
          current.setManualDisabled(true);
          var source = em.find(Resource.class, "dashboard");
          source.setName("New platform title");
          source.setPath("/new-dashboard");
          source.setPermissionMetadata("{\"fields\":[]}");
          return null;
        });
    service.synchronize(false);
    assertThat(resource("dashboard").getName()).isEqualTo("My title");
    assertThat(resource("dashboard").getPath()).isEqualTo("/new-dashboard");
    assertThat(resource("dashboard").getPermissionMetadata()).isEqualTo("{\"fields\":[]}");
    assertThat(resource("dashboard").isEnabled()).isFalse();
    service.synchronize(true);
    assertThat(resource("dashboard").getName()).isEqualTo("New platform title");
    assertThat(resource("dashboard").isLocalModified()).isFalse();
    assertThat(resource("dashboard").isManualDisabled()).isTrue();
    assertThat(resource("dashboard").isEnabled()).isFalse();
  }

  @Test
  void disappearedSourceIsRetainedAndFormalOfflineCannotBeUndoneByUpdate() {
    initialize();
    transaction(
        () -> {
          em.createQuery("delete from Resource r where r.id = 'upload'").executeUpdate();
          var offline = em.find(TenantResource.class, new TenantResourceKey(tenantId, "dashboard"));
          offline.setFormallyOffline(true);
          offline.setEnabled(false);
          return null;
        });
    service.synchronize(true);
    assertThat(count(TenantResource.class)).isEqualTo(3);
    assertThat(resource("upload").isSourceAvailable()).isFalse();
    assertThat(resource("upload").isEnabled()).isFalse();
    assertThat(resource("upload").isBaselineEntitled()).isFalse();
    assertThat(resource("dashboard").isFormallyOffline()).isTrue();
    assertThat(resource("dashboard").isEnabled()).isFalse();
  }

  @Test
  void combinedPlatformAndLocalParentCycleRollsBackTheWholeUpdate() {
    initialize();
    transaction(
        () -> {
          var local = em.find(TenantResource.class, new TenantResourceKey(tenantId, "dashboard"));
          local.setLocalModified(true);
          local.setParentId("upload");
          em.find(Resource.class, "upload").setParentId("dashboard");
          em.find(Resource.class, "upload").setName("Must not be copied");
          return null;
        });
    assertThatThrownBy(() -> service.synchronize(false)).hasMessageContaining("RESOURCE_CONFLICT");
    assertThat(resource("upload").getName()).isEqualTo("upload");
    assertThat(resource("upload").getParentId()).isEqualTo("root");
  }

  @Test
  void oldTenantRequiresExplicitAdoptionAndNeverReceivesAutomaticNewRights() {
    transaction(
        () -> {
          var setup = new TenantResourceSetup();
          setup.setTenantId(tenantId);
          setup.setState(TenantResourceSetup.State.ADOPTION_REQUIRED);
          em.persist(setup);
          return null;
        });
    assertThatThrownBy(this::initialize).hasMessageContaining("ADOPTION_REQUIRED");
    assertThatThrownBy(() -> service.preview(false)).hasMessageContaining("ADOPTION_REQUIRED");
    assertThatThrownBy(() -> service.synchronize(false)).hasMessageContaining("ADOPTION_REQUIRED");
    assertThat(count(TenantResource.class)).isZero();
  }

  @Test
  void personalSpaceUsesItsOwnScopeAndOnlyTheCurrentOwnerCanManageIt() {
    transaction(
        () -> {
          em.find(Tenant.class, tenantId).setTenantType(TenantType.PERSONAL);
          source("team-only", "root", true).setScopeTypes(Set.of(ResourceScopeType.TENANT));
          link("team-only", BuiltInTenantCodes.CORE_APPLICATION);
          return null;
        });
    initialize();
    assertThat(resource("team-only")).isNull();
    assertThatThrownBy(() -> service.synchronize(false)).hasMessageContaining("OWNER_REQUIRED");
    owner("administrator", AuthorizationScopeType.PERSONAL);
    org.simplepoint.core.AuthorizationContextHolder.getContext().setIsAdministrator(true);
    assertThatThrownBy(() -> service.preview(false)).hasMessageContaining("OWNER_REQUIRED");
    owner("owner", AuthorizationScopeType.PERSONAL);
    assertThat(service.synchronize(false).added()).isZero();
    transaction(
        () -> {
          em.find(Tenant.class, tenantId).setOwnerId("new-owner");
          return null;
        });
    assertThatThrownBy(() -> service.synchronize(false)).hasMessageContaining("OWNER_REQUIRED");
  }

  @Test
  void tenantCreationCommitsOwnerMembershipAndPreparedDirectoryTogether() {
    Tenant created = creationService().create(newTenant("Created through tenant service", "owner"));
    assertThat(count(Tenant.class)).isEqualTo(2);
    assertThat(count(TenantUserRelevance.class)).isEqualTo(1);
    assertThat(transaction(() -> em.find(TenantResourceSetup.class, created.getId()).getState()))
        .isEqualTo(TenantResourceSetup.State.READY);
    assertThat(
            transaction(
                    () ->
                        em.find(
                            TenantResource.class,
                            new TenantResourceKey(created.getId(), "dashboard")))
                .isBaselineEntitled())
        .isTrue();
    assertThat(count(TenantResourceEntitlement.class)).isZero();
  }

  @Test
  void bulkCreationRollsBackEarlierTenantsMembershipsAndDirectoriesWhenLaterOwnerIsInvalid() {
    TenantService tenants = creationService();
    assertThatThrownBy(
            () ->
                tenants.create(
                    List.of(
                        newTenant("First tenant", "owner"),
                        newTenant("Invalid owner tenant", "missing-owner"))))
        .hasMessageContaining("用户不存在");
    assertNoCreatedTenant();
  }

  @Test
  void directoryFailureRollsBackTenantCreationInTheSameTransaction() {
    transaction(
        () -> {
          em.find(Resource.class, "root").setParentId("dashboard");
          return null;
        });
    TenantService tenants = creationService();
    assertThatThrownBy(() -> tenants.create(newTenant("Must roll back", "owner")))
        .hasMessageContaining("RESOURCE_UNAVAILABLE");
    assertNoCreatedTenant();
  }

  private void assertNoCreatedTenant() {
    assertThat(count(Tenant.class)).isEqualTo(1);
    assertThat(count(TenantUserRelevance.class)).isZero();
    assertThat(count(TenantResourceSetup.class)).isZero();
    assertThat(count(TenantResource.class)).isZero();
    assertThat(count(TenantResourceEntitlement.class)).isZero();
  }

  private TenantService creationService() {
    // Only repository adapters are replaced: domain services and their transaction propagation are
    // real.
    TenantRepository tenants = mock(TenantRepository.class);
    when(tenants.save(any()))
        .thenAnswer(
            invocation -> {
              Tenant row = invocation.getArgument(0);
              em.persist(row);
              return row;
            });
    TenantUserRelevanceRepository members = mock(TenantUserRelevanceRepository.class);
    when(members.existingUserIds(any()))
        .thenAnswer(
            invocation -> {
              Set<String> ids = invocation.getArgument(0);
              return ids.contains("owner") ? Set.of("owner") : Set.of();
            });
    when(members.saveAll(any()))
        .thenAnswer(
            invocation -> {
              Iterable<TenantUserRelevance> rows = invocation.getArgument(0);
              List<TenantUserRelevance> saved = new ArrayList<>();
              rows.forEach(
                  row -> {
                    em.persist(row);
                    saved.add(row);
                  });
              return saved;
            });
    var target =
        new TenantServiceImpl(
            tenants,
            mock(DetailsProviderService.class),
            null,
            members,
            authorization,
            null,
            null,
            null,
            service);
    var proxy = new ProxyFactory(target);
    proxy.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "platform-admin", null, List.of(new SimpleGrantedAuthority("ROLE_Administrator"))));
    owner("platform-admin", AuthorizationScopeType.PLATFORM);
    return (TenantService) proxy.getProxy();
  }

  private static Tenant newTenant(String name, String ownerId) {
    var tenant = new Tenant();
    tenant.setName(name);
    tenant.setOwnerId(ownerId);
    tenant.setTenantType(TenantType.ORGANIZATION);
    return tenant;
  }

  @Test
  void personalCreationIsIdempotentAndEqualUnicodeNamesDoNotCollide() {
    String first = account("相同名🙂".repeat(12));
    String second = account("相同名🙂".repeat(12));
    var personal = personalService();
    Tenant one = personal.ensureForUser(first);
    Tenant two = personal.ensureForUser(second);
    assertThat(one.getId()).isNotEqualTo(two.getId());
    assertThat(one.getName()).isNotEqualTo(two.getName());
    assertThat(one.getName().codePointCount(0, one.getName().length())).isLessThanOrEqualTo(128);
    assertThat(personal.ensureForUser(first).getId()).isEqualTo(one.getId());
    assertThat(count(TenantUserRelevance.class)).isEqualTo(2);
    assertThat(count(TenantResource.class)).isEqualTo(6);
    assertThat(count(TenantResourceEntitlement.class)).isZero();
    assertThat(transaction(() -> em.find(TenantResourceSetup.class, one.getId()).getState()))
        .isEqualTo(TenantResourceSetup.State.READY);
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
      named = "SP_TENANT_PACKAGE_TEST_POSTGRES_URL",
      matches = ".+")
  void postgresPersonalNameLimitCountsUnicodeCodePointsWithoutSplittingSurrogates() {
    String id = account("🙂".repeat(100));
    Tenant tenant = personalService().ensureForUser(id);
    assertThat(tenant.getName().codePointCount(0, tenant.getName().length())).isEqualTo(128);
    assertThat(tenant.getName()).endsWith(" 的个人空间 (" + id + ")");
    assertThat(tenant.getName()).doesNotContain("�");
  }

  @Test
  void concurrentPersonalCreationReturnsOneWorkspaceAndOneMembership() throws Exception {
    String userId = account("Concurrent owner");
    var personal = personalService();
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<String> create =
          () -> {
            if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
              throw new IllegalStateException("start timeout");
            }
            return personal.ensureForUser(userId).getId();
          };
      var first = executor.submit(create);
      var second = executor.submit(create);
      start.countDown();
      assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(second.get(20, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertThat(count(Tenant.class)).isEqualTo(2);
    assertThat(count(TenantUserRelevance.class)).isEqualTo(1);
    assertThat(count(TenantResource.class)).isEqualTo(3);
  }

  @Test
  void personalPreparationFailureRollsBackTheEnclosingAccountRegistration() {
    transaction(
        () -> {
          em.find(Resource.class, "root").setParentId("dashboard");
          return null;
        });
    var personal = personalService();
    assertThatThrownBy(
            () ->
                transaction(
                    () -> {
                      var user = new User();
                      user.setName("Must not register");
                      em.persist(user);
                      personal.ensureForUser(user.getId());
                      return null;
                    }))
        .hasMessageContaining("RESOURCE_UNAVAILABLE");
    assertThat(count(User.class)).isZero();
    assertNoCreatedTenant();
  }

  @Test
  void personalLookupNeverAutoAdoptsAnExistingWorkspace() {
    String userId = account("Historical owner");
    transaction(
        () -> {
          var tenant = em.find(Tenant.class, tenantId);
          tenant.setTenantType(TenantType.PERSONAL);
          tenant.setOwnerId(userId);
          return null;
        });
    assertThatThrownBy(() -> personalService().ensureForUser(userId))
        .hasMessageContaining("ADOPTION_REQUIRED");
    assertThat(count(TenantResource.class)).isZero();
    assertThat(count(TenantUserRelevance.class)).isZero();
  }

  @Test
  void personalOwnerMembershipRepairReusesTheHistoricalRowWithoutChangingResourceDefinitions() {
    String userId = account("Membership owner");
    var personal = personalService();
    String id = personal.ensureForUser(userId).getId();
    String memberId =
        transaction(
            () -> {
              var member =
                  em.createQuery("from TenantUserRelevance", TenantUserRelevance.class)
                      .getSingleResult();
              member.setDeletedAt(NOW);
              member.setEnabled(false);
              em.find(Resource.class, "dashboard").setName("Must require explicit update");
              return member.getId();
            });
    clearInvocations(authorization);
    assertThat(personal.ensureForUser(userId).getId()).isEqualTo(id);
    transaction(
        () -> {
          var member = em.find(TenantUserRelevance.class, memberId);
          assertThat(member.getDeletedAt()).isNull();
          assertThat(member.getEnabled()).isTrue();
          assertThat(
                  em.find(TenantResource.class, new TenantResourceKey(id, "dashboard")).getName())
              .isEqualTo("dashboard");
          return null;
        });
    assertThat(count(TenantUserRelevance.class)).isEqualTo(1);
    verify(authorization).refreshTenant(id);
  }

  @Test
  void deletedAccountCannotPreparePersonalWorkspace() {
    String userId = account("Deleted owner");
    transaction(
        () -> {
          em.find(User.class, userId).setDeletedAt(NOW);
          return null;
        });
    assertThatThrownBy(() -> personalService().ensureForUser(userId))
        .hasMessageContaining("TENANT_UNAVAILABLE");
    assertNoCreatedTenant();
  }

  private String account(String name) {
    return transaction(
        () -> {
          var user = new User();
          user.setName(name);
          em.persist(user);
          return user.getId();
        });
  }

  private PersonalTenantProvisioningService personalService() {
    var proxy =
        new ProxyFactory(new PersonalTenantProvisioningServiceImpl(em, service, authorization));
    proxy.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    return (PersonalTenantProvisioningService) proxy.getProxy();
  }

  private void initialize() {
    transaction(
        () -> {
          service.initialize(tenantId);
          return null;
        });
  }

  private Resource source(String id, String parent, boolean grantable) {
    var resource = new Resource();
    resource.setId(id);
    resource.setCode("test." + id);
    resource.setName(id);
    resource.setParentId(parent);
    resource.setType(ResourceType.PAGE);
    resource.setPath("/" + id);
    resource.setGrantable(grantable);
    resource.setPublicAccess(false);
    resource.setDisabled(false);
    resource.setRequireOrgTenant(false);
    resource.setScopeTypes(Set.of(ResourceScopeType.TENANT, ResourceScopeType.PERSONAL));
    em.persist(resource);
    return resource;
  }

  private void link(String id, String application) {
    var link = new ApplicationResourceRelevance();
    link.setApplicationCode(application);
    link.setResourceCode("test." + id);
    em.persist(link);
  }

  private TenantResource resource(String id) {
    return transaction(() -> em.find(TenantResource.class, new TenantResourceKey(tenantId, id)));
  }

  private long count(Class<?> type) {
    return transaction(
        () ->
            em.createQuery("select count(e) from " + type.getSimpleName() + " e", Long.class)
                .getSingleResult());
  }

  private TenantResourceProvisioningService at(Instant instant) {
    var proxy =
        new ProxyFactory(
            new TenantResourceProvisioningServiceImpl(
                em, authorization, Clock.fixed(instant, ZoneOffset.UTC)));
    proxy.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    return (TenantResourceProvisioningService) proxy.getProxy();
  }

  private void owner(String id, AuthorizationScopeType scope) {
    var context = new AuthorizationContext();
    context.setUserId(id);
    context.setScopeType(scope);
    context.setAttributes(Map.of("X-Tenant-Id", tenantId));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private <T> T transaction(Supplier<T> work) {
    return new TransactionTemplate(transactions).execute(status -> work.get());
  }
}
