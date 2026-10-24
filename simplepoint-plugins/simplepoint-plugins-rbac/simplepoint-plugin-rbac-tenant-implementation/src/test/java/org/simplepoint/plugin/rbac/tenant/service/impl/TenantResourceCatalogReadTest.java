package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceEntitlement;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;

/**
 * H2 has no platform/legacy-package tables; PostgreSQL verifies the same tenant-only SQL against
 * real migrations.
 */
class TenantResourceCatalogReadTest {
  private static final Instant NOW = Instant.parse("2026-10-23T06:00:00Z");
  private TenantEntitlementDatabase database;
  private SessionFactory factory;
  private Session em;
  private TenantResourceCatalogImpl catalog;
  private TenantRepository tenants;
  private TenantPackageRelevanceRepository packages;
  private ResourceRepository platform;
  private final List<String> statements = new ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    database = new TenantEntitlementDatabase();
    factory =
        database.createWithSettings(
            java.util.Map.of(
                "hibernate.generate_statistics",
                true,
                "hibernate.session_factory.statement_inspector",
                (StatementInspector)
                    sql -> {
                      statements.add(sql);
                      return sql;
                    }),
            Tenant.class,
            TenantResource.class,
            TenantResourceEntitlement.class,
            TenantResourceSetup.class);
    em = factory.openSession();
    em.beginTransaction();
    tenants = mock(TenantRepository.class);
    packages = mock(TenantPackageRelevanceRepository.class);
    platform = mock(ResourceRepository.class);
    catalog =
        new TenantResourceCatalogImpl(
            em,
            tenants,
            mock(
                org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceProvisioningService
                    .class));
    tenant("tenant-a", TenantType.ORGANIZATION);
    tenant("tenant-b", TenantType.ORGANIZATION);
  }

  @AfterEach
  void cleanup() throws Exception {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (em != null) {
      if (em.getTransaction().isActive()) {
        em.getTransaction().rollback();
      }
      em.close();
    }
    if (database != null) {
      database.close();
    }
  }

  @Test
  void ownerAvailabilitySeparatesEntitlementStateFromRuntimeDependenciesAndLocalControls() {
    var resources = new ArrayList<TenantResource>();
    for (String id :
        List.of(
            "baseline",
            "timed",
            "unlimited",
            "future",
            "expired",
            "missing",
            "disabled",
            "offline",
            "source",
            "blocked")) {
      resources.add(
          platform(
              "tenant-a",
              id,
              id.equals("blocked") ? "expired" : null,
              Set.of("baseline", "disabled", "offline", "source", "blocked").contains(id)));
    }
    resources.get(6).setManualDisabled(true);
    resources.get(7).setFormallyOffline(true);
    resources.get(8).setSourceAvailable(false);
    // The real importer also clears effective enabled when the source becomes unavailable.
    resources.get(8).setEnabled(false);
    resources.add(alias("alias", null, "expired"));
    resources.add(group("tenant-a", "structure", null));
    credit("tenant-a", "timed", NOW.minusSeconds(5), NOW.plusSeconds(30));
    credit("tenant-a", "unlimited", NOW, null);
    credit("tenant-a", "future", NOW.plusSeconds(1), null);
    credit("tenant-a", "expired", NOW.minusSeconds(30), NOW);
    // A different tenant's unlimited balance cannot grant the missing resource.
    platform("tenant-b", "missing", null, false);
    credit("tenant-b", "missing", NOW, null);
    evidence();
    var projected = new TenantResourceAvailabilityProjector(em, "tenant-a", NOW).project(resources);
    var views =
        projected.stream()
            .map(org.simplepoint.plugin.rbac.tenant.api.vo.TenantResourceView.class::cast)
            .toList();
    assertThat(views)
        .extracting(view -> view.getAvailability().name())
        .containsExactly(
            "AVAILABLE",
            "AVAILABLE",
            "AVAILABLE",
            "NOT_STARTED",
            "EXPIRED",
            "NO_ENTITLEMENT",
            "DISABLED",
            "OFFLINE",
            "SOURCE_UNAVAILABLE",
            "DEPENDENCY_UNAVAILABLE",
            "DEPENDENCY_UNAVAILABLE",
            "AVAILABLE");
    assertThat(views)
        .extracting(view -> view.getEntitlementKind().name())
        .containsExactly(
            "BASELINE",
            "TIMED",
            "UNLIMITED",
            "UNLIMITED",
            "TIMED",
            "NONE",
            "BASELINE",
            "BASELINE",
            "BASELINE",
            "BASELINE",
            "NOT_REQUIRED",
            "NOT_REQUIRED");
    assertThat(views.get(1).getEntitlementExpiresAt()).isEqualTo(NOW.plusSeconds(30));
    assertThat(views.get(0).getEntitlementExpiresAt()).isNull();
    assertThat(projected.getFirst()).isNotSameAs(resources.getFirst());
    scalarEvidence(false);
  }

  @Test
  void availabilityProjectionReadsOnlyThePageAndItsDependenciesWithBoundedBinds() {
    var resources = new ArrayList<TenantResource>();
    for (int index = 0; index < 521; index++) {
      resources.add(platform("tenant-a", "page-" + index, null, true));
    }
    final var other = platform("tenant-b", "foreign", null, true);
    evidence();
    assertThat(
            new TenantResourceAvailabilityProjector(em, "tenant-a", NOW)
                .project(resources.subList(0, 20)))
        .hasSize(20);
    assertThat(factory.getStatistics().getQueryExecutionCount()).isLessThanOrEqualTo(3);
    scalarEvidence(false);
    statements.clear();
    assertThatThrownBy(
            () ->
                new TenantResourceAvailabilityProjector(em, "tenant-a", NOW)
                    .project(List.of(other)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(statements).isEmpty();
  }

  @Test
  void ownerCapabilitiesCannotBeGrantedOrReachedThroughAliasesEvenByAnotherAdministrator() {
    String resources =
        org.simplepoint.plugin.rbac.tenant.api.constants.TenantOwnerCapabilities.RESOURCES;
    String packages =
        org.simplepoint.plugin.rbac.tenant.api.constants.TenantOwnerCapabilities.PACKAGES;
    platform("tenant-a", resources, null, true).setPublicAccess(true);
    platform("tenant-a", packages, null, true).setGrantable(false);
    alias("owner-alias", null, resources).setPublicAccess(true);
    platform("tenant-a", "ordinary", null, true);
    evidence();
    var requested = Set.of(resources, packages, "owner-alias", "ordinary");
    for (String actor : List.of("member", "platform-admin", "previous-owner")) {
      assertThat(at(NOW).authorization(requested, true, actor).resourceCodes())
          .containsExactly("ordinary");
      assertThat(at(NOW).navigationResources(requested, actor))
          .extracting(TenantResource::getCode)
          .containsExactly("ordinary");
    }
    assertThat(at(NOW).authorization(requested, true).resourceCodes()).containsExactly("ordinary");
    assertThat(at(NOW).authorization(Set.of(), false, "owner").resourceCodes())
        .contains(resources, packages)
        .doesNotContain("ordinary");
    assertThat(catalog.grantableCodes("tenant-a", requested)).containsExactly("ordinary");
    assertThat(catalog.eligiblePermissionCodes("tenant-a", requested)).containsExactly("ordinary");
    assertThat(catalog.roleResourceIds("tenant-a")).containsExactly("ordinary");
    assertThat(at(NOW).navigationResources(requested, "owner"))
        .extracting(TenantResource::getCode)
        .contains(packages, "owner-alias", "ordinary");
    em.find(Tenant.class, "tenant-a").setOwnerId("new-owner");
    evidence();
    assertThat(at(NOW).authorization(requested, true, "owner").resourceCodes())
        .containsExactly("ordinary");
    assertThat(at(NOW).authorization(Set.of(), false, "new-owner").resourceCodes())
        .contains(resources, packages);
    scalarEvidence(false);
  }

  @Test
  void ownerCapabilitiesStillRequireCurrentTenantEntitlementsAndPersonalOwnership() {
    String code = org.simplepoint.plugin.rbac.tenant.api.constants.TenantOwnerCapabilities.PACKAGES;
    tenant("personal-owner", TenantType.PERSONAL);
    platform("personal-owner", code, null, false).setScopeTypes(Set.of(ResourceScopeType.PERSONAL));
    credit("personal-owner", code, NOW.minusSeconds(1), NOW.plusSeconds(1));
    evidence();
    assertThat(
            new TenantResourceReadQuery(em, "personal-owner", NOW)
                .authorization(Set.of(), false, "owner")
                .resourceCodes())
        .containsExactly(code);
    assertThat(
            new TenantResourceReadQuery(em, "personal-owner", NOW.plusSeconds(1))
                .authorization(Set.of(code), true, "owner")
                .resourceCodes())
        .isEmpty();
    assertThat(
            new TenantResourceReadQuery(em, "personal-owner", NOW)
                .authorization(Set.of(code), true, "member")
                .resourceCodes())
        .isEmpty();
    scalarEvidence(false);
  }

  @Test
  void roleDirectoryIncludesOnlyEligibleExecutablesAndTheirCompleteAncestors() {
    group("tenant-a", "root", null);
    group("tenant-a", "empty", "root");
    platform("tenant-a", "paid", "root", false);
    platform("tenant-a", "unlicensed", "empty", false);
    credit("tenant-a", "paid", NOW.minusSeconds(1), NOW.plusSeconds(1));
    evidence();
    assertThat(at(NOW).roleResourceIds()).containsExactlyInAnyOrder("root", "paid");
    assertThat(at(NOW.plusSeconds(1)).roleResourceIds()).isEmpty();
    scalarEvidence(false);
  }

  @Test
  void requestedPermissionEligibilityIncludesPublicEntriesWithoutExpandingTheRequest() {
    group("tenant-a", "root", null);
    var publicPage = platform("tenant-a", "public", "root", true);
    publicPage.setPublicAccess(true);
    publicPage.setGrantable(false);
    platform("tenant-a", "unrequested", null, true).setPublicAccess(true);
    platform("tenant-a", "paid", "root", false);
    alias("alias", "root", "paid");
    credit("tenant-a", "paid", NOW.minusSeconds(1), NOW.plusSeconds(1));
    platform("tenant-b", "foreign", null, true);
    evidence();
    assertThat(at(NOW).eligiblePermissionCodes(Set.of("root", "public", "alias", "foreign")))
        .containsExactlyInAnyOrder("public", "alias");
    assertThat(at(NOW.plusSeconds(1)).eligiblePermissionCodes(Set.of("public", "alias")))
        .containsExactly("public");
    scalarEvidence(false);
    assertThat(statements).noneMatch(sql -> sql.contains("permission_metadata"));
  }

  @Test
  void grantLockRejectsDeletedTenantEvenWithAnAlreadyLoadedEntity() {
    var loaded = em.find(Tenant.class, "tenant-a");
    catalog.lockForGrantModification("tenant-a");
    em.createMutationQuery("update Tenant t set t.deletedAt = :now where t.id = :tenant")
        .setParameter("now", NOW)
        .setParameter("tenant", "tenant-a")
        .executeUpdate();
    assertThat(loaded.getDeletedAt()).isNull();
    assertThatThrownBy(() -> catalog.lockForGrantModification("tenant-a"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void grantLockRejectsUnpreparedDirectoryAndBlankTenant() {
    em.createMutationQuery(
            "update TenantResourceSetup s set s.state = :state, s.initializedAt = null where"
                + " s.tenantId = :tenant")
        .setParameter("state", TenantResourceSetup.State.ADOPTION_REQUIRED)
        .setParameter("tenant", "tenant-a")
        .executeUpdate();
    assertThatThrownBy(() -> catalog.lockForGrantModification("tenant-a"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> catalog.lockForGrantModification(" "))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
      named = "SP_TENANT_PACKAGE_TEST_POSTGRES_URL",
      matches = ".+")
  void grantLockExcludesAnotherDatabaseTransactionUntilReleased() {
    em.getTransaction().commit();
    em.beginTransaction();
    catalog.lockForGrantModification("tenant-a");
    try (var contender = factory.openSession()) {
      contender.beginTransaction();
      try {
        assertThatThrownBy(
                () ->
                    contender
                        .createNativeQuery(
                            "select id from {h-schema}simpoint_saas_tenants where id = :tenant for"
                                + " update nowait",
                            String.class)
                        .setParameter("tenant", "tenant-a")
                        .getSingleResult())
            .hasRootCauseInstanceOf(java.sql.SQLException.class)
            .satisfies(
                error -> {
                  Throwable cause = error;
                  while (cause.getCause() != null) {
                    cause = cause.getCause();
                  }
                  assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("55P03");
                });
      } finally {
        contender.getTransaction().rollback();
      }
      em.getTransaction().rollback();
      contender.beginTransaction();
      try {
        assertThat(
                contender
                    .createNativeQuery(
                        "select id from {h-schema}simpoint_saas_tenants where id = :tenant for"
                            + " update nowait",
                        String.class)
                    .setParameter("tenant", "tenant-a")
                    .getSingleResult())
            .isEqualTo("tenant-a");
      } finally {
        contender.getTransaction().rollback();
      }
    }
  }

  @Test
  void versionAndPersonalTenantQueriesExcludeDeletedTenantsWithoutRequestFilters()
      throws Exception {
    var repository = org.simplepoint.plugin.rbac.tenant.repository.JpaTenantRepository.class;
    final String versionQuery =
        repository
            .getMethod("getTenantAuthorizationVersion", String.class)
            .getAnnotation(org.springframework.data.jpa.repository.Query.class)
            .value();
    final String personalQuery =
        repository
            .getMethod("findPersonalTenantByOwnerId", String.class)
            .getAnnotation(org.springframework.data.jpa.repository.Query.class)
            .value();
    var tenant = em.find(Tenant.class, "tenant-a");
    tenant.setTenantType(TenantType.PERSONAL);
    tenant.setOwnerId("owner");
    tenant.setAuthorizationVersion(7L);
    assertThat(
            em.createQuery(versionQuery, Long.class)
                .setParameter("tenantId", "tenant-a")
                .getResultList())
        .containsExactly(7L);
    assertThat(
            em.createQuery(personalQuery, Tenant.class)
                .setParameter("ownerId", "owner")
                .getResultList())
        .hasSize(1);
    tenant.setDeletedAt(NOW);
    em.flush();
    em.clear();
    assertThat(
            em.createQuery(versionQuery, Long.class)
                .setParameter("tenantId", "tenant-a")
                .getResultList())
        .isEmpty();
    assertThat(
            em.createQuery(personalQuery, Tenant.class)
                .setParameter("ownerId", "owner")
                .getResultList())
        .isEmpty();
  }

  @Test
  void authorizationCombinesOnlyEntitledPublicGrantedAndOwnerEntries() {
    platform("tenant-a", "public", null, true).setPublicAccess(true);
    platform("tenant-a", "unlicensed-public", null, false).setPublicAccess(true);
    platform("tenant-a", "private", null, true);
    platform("tenant-a", "paid", null, false);
    alias("alias", null, "paid");
    credit("tenant-a", "paid", NOW.minusSeconds(1), NOW.plusSeconds(30));
    platform("tenant-b", "foreign", null, true).setPublicAccess(true);
    evidence();

    var member = at(NOW).authorization(Set.of("alias", "unlicensed-public", "foreign"), false);
    assertThat(member.resourceCodes()).containsExactlyInAnyOrder("public", "alias", "paid");
    assertThat(member.validUntil()).isEqualTo(NOW.plusSeconds(30));
    assertThat(at(NOW).authorization(Set.of(), true).resourceCodes())
        .containsExactlyInAnyOrder("public", "private", "paid", "alias");
    var expired = at(NOW.plusSeconds(30)).authorization(Set.of("alias", "paid"), false);
    assertThat(expired.resourceCodes()).containsExactly("public");
    assertThat(expired.validUntil()).isNull();
    scalarEvidence(false);
  }

  @Test
  void authorizationDeadlineIncludesFutureStartsAndExpiringParentsAtSubMillisecondPrecision() {
    platform("tenant-a", "parent", null, false);
    platform("tenant-a", "child", "parent", true);
    platform("tenant-a", "future", null, false).setPublicAccess(true);
    credit("tenant-a", "parent", NOW.minusSeconds(1), NOW.plusSeconds(30));
    Instant start = NOW.plusNanos(123_000);
    credit("tenant-a", "future", start, NOW.plusSeconds(60));
    evidence();
    assertThat(at(NOW).authorization(Set.of("child"), false).validUntil()).isEqualTo(start);
    var started = at(start).authorization(Set.of("child"), false);
    assertThat(started.resourceCodes()).containsExactlyInAnyOrder("child", "future");
    assertThat(started.validUntil()).isEqualTo(NOW.plusSeconds(30));
    assertThat(at(NOW.plusSeconds(30)).authorization(Set.of("child"), false).resourceCodes())
        .containsExactly("future");
    scalarEvidence(false);
  }

  @Test
  void authorizationRejectsMissingOrUnpreparedDirectoryInsteadOfReturningPublicFallback() {
    assertThatThrownBy(
            () -> new TenantResourceReadQuery(em, "missing", NOW).authorization(Set.of(), true))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    em.createMutationQuery(
            "update TenantResourceSetup s set s.state = :state, s.initializedAt = null where"
                + " s.tenantId = :tenant")
        .setParameter("state", TenantResourceSetup.State.ADOPTION_REQUIRED)
        .setParameter("tenant", "tenant-a")
        .executeUpdate();
    assertThatThrownBy(() -> at(NOW).authorization(Set.of(), true))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void completeProjectionPreservesMoreThanFiveHundredRowsAndPresentationWithoutEntityLoads() {
    group("tenant-a", "root", null);
    var expected = new HashSet<String>();
    expected.add("root");
    for (int i = 0; i < 521; i++) {
      String id = "entry-" + String.format("%03d", i);
      expected.add(id);
      platform("tenant-a", id, "root", true);
      platform("tenant-b", id, null, true);
    }
    platform("tenant-a", "disabled", null, true).setEnabled(false);
    platform("tenant-a", "blocked", "disabled", true);
    platform("tenant-a", "orphan", "missing", true);
    evidence();
    var rows = catalog.activeResources("tenant-a");
    assertThat(rows)
        .extracting(TenantResource::getId)
        .containsExactlyInAnyOrderElementsOf(expected);
    var last =
        rows.stream().filter(row -> row.getId().equals("entry-520")).findFirst().orElseThrow();
    assertThat(last.getDescription()).isEqualTo("Description entry-520");
    assertThat(last.getComponent()).isEqualTo("common/system/Resource");
    assertThat(last.isLocalModified()).isTrue();
    assertThat(last.getUpdatedAt()).isEqualTo(NOW);
    assertThat(factory.getStatistics().getQueryExecutionCount()).isLessThanOrEqualTo(16);
    scalarEvidence(true);
  }

  @Test
  void selectedAuthorizationAndNavigationPreserveLargeAliasSetsWithBoundedQueries() {
    group("tenant-a", "root", null);
    var aliases = new LinkedHashSet<String>();
    var targets = new LinkedHashSet<String>();
    for (int i = 0; i < 521; i++) {
      String suffix = String.format("%03d", i);
      platform("tenant-a", "target-" + suffix, "root", true);
      alias("alias-" + suffix, "root", "target-" + suffix);
      aliases.add("alias-" + suffix);
      targets.add("target-" + suffix);
    }
    evidence();
    assertThat(catalog.grantableCodes("tenant-a", aliases))
        .containsExactlyInAnyOrderElementsOf(aliases);
    assertThat(catalog.effectivePlatformCodes("tenant-a", aliases))
        .containsExactlyInAnyOrderElementsOf(targets);
    scalarEvidence(false);
    evidence();
    var held = new LinkedHashSet<>(aliases);
    held.add("target-000");
    var expected = new HashSet<>(aliases);
    expected.add("root");
    assertThat(catalog.navigationResources("tenant-a", held))
        .extracting(TenantResource::getId)
        .containsExactlyInAnyOrderElementsOf(expected);
    assertThat(statements.stream().filter(sql -> sql.contains("simpoint_saas_tenant_resources")))
        .allMatch(sql -> sql.contains(" in "));
    scalarEvidence(true);
  }

  @Test
  void expiryAtExactBoundaryInvalidatesPlatformAliasAndNavigationWithoutSynchronization() {
    platform("tenant-a", "paid", null, false);
    alias("alias", null, "paid");
    credit("tenant-a", "paid", NOW.minusSeconds(1), NOW.plusSeconds(1));
    evidence();
    assertThat(at(NOW).authorizationCodes(Set.of("alias"), false)).containsExactly("alias");
    assertThat(at(NOW.plusSeconds(1)).authorizationCodes(Set.of("alias"), true)).isEmpty();
    assertThat(at(NOW.plusSeconds(1)).navigationResources(Set.of("alias", "paid"))).isEmpty();
    assertThat(at(NOW.plusSeconds(1)).selectedResources(Set.of("alias"))).isEmpty();
    scalarEvidence(true);
    assertThat(
            em.createQuery(
                    "select r.enabled from TenantResource r where r.tenantId='tenant-a' and"
                        + " r.id='paid'",
                    Boolean.class)
                .getSingleResult())
        .isTrue();
  }

  @Test
  void publicAccessDoesNotGrantEntitlementAndForeignBalancesOrFutureWindowsDoNotCount() {
    platform("tenant-a", "public", null, false).setPublicAccess(true);
    platform("tenant-a", "foreign", null, false);
    platform("tenant-b", "foreign", null, false);
    credit("tenant-b", "foreign", NOW.minusSeconds(1), null);
    platform("tenant-a", "future", null, false);
    credit("tenant-a", "future", NOW.plusSeconds(1), null);
    platform("tenant-a", "unlimited", null, false);
    credit("tenant-a", "unlimited", NOW, null);
    platform("tenant-a", "baseline", null, true);
    evidence();
    assertThat(
            at(NOW)
                .authorizationCodes(
                    Set.of("public", "foreign", "future", "unlimited", "baseline"), false))
        .containsExactlyInAnyOrder("unlimited", "baseline");
    scalarEvidence(false);
  }

  @Test
  void entitlementRemovalImmediatelyRejectsStillEnabledResources() {
    platform("tenant-a", "paid", null, false);
    credit("tenant-a", "paid", NOW.minusSeconds(1), null);
    evidence();
    assertThat(at(NOW).authorizationCodes(Set.of("paid"), false)).containsExactly("paid");
    em.createQuery("delete TenantResourceEntitlement e where e.tenantId='tenant-a'")
        .executeUpdate();
    evidence();
    assertThat(at(NOW).authorizationCodes(Set.of("paid"), false)).isEmpty();
    scalarEvidence(false);
  }

  @Test
  void copiedAvailabilityScopeAndLifecycleAreEnforcedOnSourcesAndAliases() {
    for (String id :
        List.of(
            "good",
            "disabled",
            "manual",
            "offline",
            "unavailable",
            "wrong-scope",
            "no-scope",
            "nongrantable")) {
      var row = platform("tenant-a", id, null, true);
      switch (id) {
        case "disabled" -> row.setEnabled(false);
        case "manual" -> row.setManualDisabled(true);
        case "offline" -> row.setFormallyOffline(true);
        case "unavailable" -> row.setSourceAvailable(false);
        case "wrong-scope" -> row.setScopeTypes(Set.of(ResourceScopeType.PLATFORM));
        case "no-scope" -> row.setScopeTypes(null);
        case "nongrantable" -> row.setGrantable(false);
        default -> {
          // The good source retains its ordinary available declaration.
        }
      }
      alias("alias-" + id, null, id);
    }
    evidence();
    assertThat(
            catalog.grantableCodes(
                "tenant-a",
                Set.of(
                    "alias-good",
                    "alias-disabled",
                    "alias-manual",
                    "alias-offline",
                    "alias-unavailable",
                    "alias-wrong-scope",
                    "alias-no-scope",
                    "alias-nongrantable")))
        .containsExactly("alias-good");
    scalarEvidence(false);
  }

  @Test
  void managedAndCustomResourcesRequireValidLocalAncestryAndKnownOwners() {
    group("tenant-a", "root", null);
    managed("page", "root", "app");
    managed("action", "page", "app").setType(ResourceType.ACTION);
    managed("report", "root", "report");
    managed("missing", "missing-platform-parent", "app");
    managed("unknown", null, "unregistered-engine");
    group("tenant-a", "disabled-parent", null).setEnabled(false);
    managed("blocked", "disabled-parent", "app");
    managed("offline", null, "app").setFormallyOffline(true);
    evidence();
    assertThat(catalog.activeManagedGrantableCodes("tenant-a"))
        .containsExactlyInAnyOrder("page", "action", "report");
    assertThat(
            catalog.managedGrantableCodes(
                "tenant-a", Set.of("page", "report", "missing", "blocked", "offline", "unknown")))
        .containsExactlyInAnyOrder("page", "report");
    assertThat(catalog.managedNavigationResources("tenant-a", Set.of("page")))
        .extracting(TenantResource::getId)
        .containsExactlyInAnyOrder("page", "root");
    scalarEvidence(true);
  }

  @Test
  void missingTargetsOtherTenantsAndCyclesFailClosedAcrossNavigationAndAuthorization() {
    group("tenant-a", "cycle-a", "cycle-b");
    group("tenant-a", "cycle-b", "cycle-a");
    platform("tenant-a", "cycle-child", "cycle-a", true);
    platform("tenant-b", "foreign-only", null, true);
    alias("foreign-alias", null, "foreign-only");
    alias("missing-alias", null, "missing");
    platform("tenant-a", "target", "cross-alias", true);
    alias("cross-alias", null, "target");
    evidence();
    var requested =
        Set.of("cycle-child", "foreign-alias", "missing-alias", "cross-alias", "' OR 1=1 --");
    assertThat(catalog.grantableCodes("tenant-a", requested)).isEmpty();
    assertThat(catalog.navigationResources("tenant-a", requested)).isEmpty();
    assertThat(catalog.selectedResources("tenant-a", requested)).isEmpty();
    scalarEvidence(true);
  }

  @Test
  void longCompleteAncestryUsesLinearValidationAndPageSizedQueries() {
    String parent = null;
    for (int i = 0; i < 521; i++) {
      String id = "chain-" + String.format("%03d", i);
      group("tenant-a", id, parent);
      parent = id;
    }
    platform("tenant-a", "leaf", parent, true);
    evidence();
    assertThat(catalog.activeResources("tenant-a")).hasSize(522);
    assertThat(factory.getStatistics().getQueryExecutionCount()).isLessThanOrEqualTo(16);
    scalarEvidence(true);
  }

  @Test
  void personalScopesComeFromTenantIdentityWithoutRequestContext() {
    tenant("personal", TenantType.PERSONAL);
    platform("personal", "organization-only", null, true);
    platform("personal", "personal-page", null, true)
        .setScopeTypes(Set.of(ResourceScopeType.PERSONAL));
    evidence();
    assertThat(catalog.grantableCodes("personal", Set.of("organization-only", "personal-page")))
        .containsExactly("personal-page");
    scalarEvidence(false);
  }

  @Test
  void unreadyDeletedAndUnknownTenantsNeverExposeTheirDirectory() {
    platform("tenant-a", "page", null, true);
    for (var state :
        List.of(
            TenantResourceSetup.State.INITIALIZATION_REQUIRED,
            TenantResourceSetup.State.ADOPTION_REQUIRED)) {
      em.find(TenantResourceSetup.class, "tenant-a").setState(state);
      em.find(TenantResourceSetup.class, "tenant-a").setInitializedAt(null);
      evidence();
      assertThat(catalog.activeResources("tenant-a")).isEmpty();
      assertThat(catalog.grantableCodes("tenant-a", Set.of("page"))).isEmpty();
    }
    em.find(TenantResourceSetup.class, "tenant-a").setState(TenantResourceSetup.State.READY);
    em.find(TenantResourceSetup.class, "tenant-a").setInitializedAt(NOW);
    em.find(Tenant.class, "tenant-a").setDeletedAt(NOW);
    evidence();
    assertThat(catalog.activeResources("tenant-a")).isEmpty();
    assertThat(catalog.activeResources("missing")).isEmpty();
    scalarEvidence(false);
  }

  @Test
  void narrowAuthorizationAvoidsWholeCatalogReadsAndEmptyRequestsDoNotQuery() {
    platform("tenant-a", "target", null, true);
    alias("alias", null, "target");
    for (int i = 0; i < 521; i++) {
      platform("tenant-a", "unrelated-" + i, null, true);
    }
    evidence();
    assertThat(catalog.grantableCodes("tenant-a", Set.of("alias"))).containsExactly("alias");
    assertThat(factory.getStatistics().getQueryExecutionCount()).isLessThanOrEqualTo(3);
    scalarEvidence(false);
    statements.clear();
    assertThat(catalog.activeResources(" ")).isEmpty();
    assertThat(catalog.grantableCodes("tenant-a", List.of())).isEmpty();
    assertThat(catalog.navigationResources("tenant-a", null)).isEmpty();
    assertThat(statements).isEmpty();
  }

  private void tenant(String id, TenantType type) {
    var row = new Tenant();
    row.setId(id);
    row.setName(id);
    row.setOwnerId("owner");
    row.setTenantType(type);
    row.setAuthorizationVersion(0L);
    em.persist(row);
    var setup = new TenantResourceSetup();
    setup.setTenantId(id);
    setup.setState(TenantResourceSetup.State.READY);
    setup.setInitializedAt(NOW);
    em.persist(setup);
  }

  private TenantResource entry(String tenant, String id, String parent) {
    var row = new TenantResource();
    row.setTenantId(tenant);
    row.setId(id);
    row.setParentId(parent);
    row.setCode(id);
    row.setName("Label " + id);
    row.setType(ResourceType.PAGE);
    row.setPath("/actual/" + id);
    row.setComponent("common/system/Resource");
    row.setDescription("Description " + id);
    row.setGrantable(true);
    row.setLocalModified(true);
    row.setUpdatedAt(NOW);
    em.persist(row);
    return row;
  }

  private TenantResource platform(String tenant, String id, String parent, boolean baseline) {
    var row = entry(tenant, id, parent);
    row.setPlatformResourceId(id);
    row.setScopeTypes(Set.of(ResourceScopeType.TENANT));
    row.setBaselineEntitled(baseline);
    return row;
  }

  private TenantResource group(String tenant, String id, String parent) {
    var row = entry(tenant, id, parent);
    row.setGrantable(false);
    row.setType(ResourceType.GROUP);
    return row;
  }

  private TenantResource alias(String id, String parent, String target) {
    var row = entry("tenant-a", id, parent);
    row.setTargetResourceId(target);
    return row;
  }

  private TenantResource managed(String id, String parent, String owner) {
    var row = entry("tenant-a", id, parent);
    row.setManagedOwner(owner);
    row.setManagedKey(id);
    return row;
  }

  private void credit(String tenant, String id, Instant start, Instant end) {
    var row = new TenantResourceEntitlement();
    row.setTenantId(tenant);
    row.setId(id);
    row.setStartsAt(start);
    row.setExpiresAt(end);
    row.setUpdatedAt(NOW);
    em.persist(row);
  }

  private TenantResourceReadQuery at(Instant time) {
    return new TenantResourceReadQuery(em, "tenant-a", time);
  }

  private void evidence() {
    em.flush();
    em.clear();
    statements.clear();
    factory.getStatistics().clear();
    clearInvocations(tenants, packages, platform);
  }

  private void scalarEvidence(boolean presentation) {
    assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
    verifyNoInteractions(tenants, packages, platform);
    var membership = Pattern.compile("(?i)\\bin\\s*\\(([^)]*)\\)");
    for (String sql : statements) {
      var matcher = membership.matcher(sql);
      while (matcher.find()) {
        assertThat(matcher.group(1).chars().filter(c -> c == '?').count()).isLessThanOrEqualTo(100);
      }
      String normalized = sql.toLowerCase(Locale.ROOT).replace("_", "");
      assertThat(normalized)
          .doesNotContain("simpointacresources", "tenantpackagerel", "permissionmetadata");
      if (!presentation) {
        assertThat(sql).doesNotContain(".name", ".description", ".icon");
      }
    }
  }
}
