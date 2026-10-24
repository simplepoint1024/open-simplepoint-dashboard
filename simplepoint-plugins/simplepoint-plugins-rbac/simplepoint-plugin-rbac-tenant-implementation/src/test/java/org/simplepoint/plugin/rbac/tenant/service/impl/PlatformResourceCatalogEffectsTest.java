package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.DataScope;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.simplepoint.security.entity.RoleResourceGrant;

/**
 * Native identity-store effects; tenant definitions, grants and rollback share one real
 * transaction.
 */
class PlatformResourceCatalogEffectsTest {
  private TenantEntitlementDatabase database;
  private Session em;
  private PlatformResourceCatalogEffectsImpl service;
  private ResourceAuthorizationVersionService versions;
  private final List<String> statements = new ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    database = new TenantEntitlementDatabase();
    var factory =
        database.createWithSettings(
            java.util.Map.of(
                "hibernate.session_factory.statement_inspector",
                (org.hibernate.resource.jdbc.spi.StatementInspector)
                    sql -> {
                      statements.add(sql);
                      return sql;
                    }),
            Tenant.class,
            TenantResource.class,
            Resource.class,
            RoleResourceGrant.class,
            DataScope.class,
            org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance.class,
            org.simplepoint.security.entity.User.class,
            org.simplepoint.security.entity.Role.class,
            org.simplepoint.security.entity.UserRoleRelevance.class,
            org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance.class);
    em = factory.openSession();
    em.beginTransaction();
    var repositories = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(em);
    repositories.setRepositoryBaseClass(
        org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl.class);
    versions =
        spy(
            new ResourceAuthorizationVersionServiceImpl(
                repositories.getRepository(
                    org.simplepoint.plugin.rbac.tenant.repository.JpaTenantRepository.class)));
    service = new PlatformResourceCatalogEffectsImpl(em, versions);
    tenant("a");
    tenant("b");
  }

  @AfterEach
  void close() throws Exception {
    if (em.getTransaction().isActive()) {
      em.getTransaction().rollback();
    }
    em.close();
    database.close();
  }

  @Test
  void currentSourceControlsPropagateWithoutLosingTenantOverridesOrIssuingEntitlements() {
    final var parent = source("parent", null);
    final var child = source("child", "parent");
    mirror("a", "child");
    var local = row("a", "child");
    local.setLocalModified(true);
    local.setName("Local name");
    local.setParentId(null);
    local.setManualDisabled(true);
    local.setEnabled(false);
    child.setComponent("common/NewRuntime");
    child.setPublicAccess(true);
    child.setGrantable(false);
    parent.setDisabled(true);
    service.synchronize(Set.of("parent"));
    assertThat(local.getName()).isEqualTo("Local name");
    assertThat(local.getParentId()).isNull();
    assertThat(local.getComponent()).isEqualTo("common/NewRuntime");
    assertThat(local.isPublicAccess()).isTrue();
    assertThat(local.isGrantable()).isFalse();
    assertThat(local.isSourceAvailable()).isFalse();
    assertThat(local.isManualDisabled()).isTrue();
    assertThat(local.isBaselineEntitled()).isTrue();
    verify(versions).refreshTenant("a");
    assertThat(version("a")).isEqualTo(1L);
    assertThat(version("b")).isZero();
    assertThat(statements)
        .noneMatch(
            sql ->
                sql.toLowerCase()
                    .contains("insert into simpoint_saas_tenant_resource_entitlements"));
    parent.setDisabled(false);
    service.synchronize(Set.of("parent"));
    assertThat(local.isSourceAvailable()).isTrue();
    assertThat(local.isEnabled()).isFalse();
  }

  @Test
  void explicitRelocationFollowsInheritedPlacementButPreservesTenantNamesAndCustomPlacement() {
    source("old-directory", null);
    source("new-directory", null);
    var page = source("page", "old-directory");
    mirror("a", "page");
    mirror("b", "page");
    var inherited = row("a", "page");
    inherited.setLocalModified(true);
    inherited.setName("Tenant translation");
    var customized = row("b", "page");
    customized.setLocalModified(true);
    customized.setParentId(null);
    page.setParentId("new-directory");
    var changes =
        java.util.Map.of(
            "page",
            new org.simplepoint.plugin.rbac.tenant.api.service.PlatformResourceCatalogEffects
                .ParentChange("old-directory", "new-directory"));
    service.synchronize(Set.of("page"), changes);
    assertThat(inherited.getParentId()).isEqualTo("new-directory");
    assertThat(inherited.getName()).isEqualTo("Tenant translation");
    assertThat(inherited.isBaselineEntitled()).isTrue();
    assertThat(row("a", "new-directory")).isNotNull();
    assertThat(customized.getParentId()).isNull();
    service.synchronize(Set.of("page"));
    assertThat(inherited.getParentId()).isEqualTo("new-directory");
    assertThat(inherited.getName()).isEqualTo("Tenant translation");
    assertThat(customized.getParentId()).isNull();
    assertThat(statements)
        .noneMatch(
            sql ->
                sql.toLowerCase()
                    .contains("insert into simpoint_saas_tenant_resource_entitlements"));
  }

  @Test
  void formalRetirementRevokesDependentsAndPreservesHistoryAcrossReonline() {
    final var source = source("host", null);
    source.setGrantable(false);
    mirror("a", "host");
    var app = custom("a", "app");
    app.setManagedOwner("app");
    app.setManagedKey("page:one");
    app.setManagedHostResourceId("host");
    var action = custom("a", "action");
    action.setParentId("app");
    var alias = custom("a", "alias");
    alias.setTargetResourceId("host");
    final var unrelated = custom("b", "host");
    final var directGrant = grant("a", "host");
    final var appGrant = grant("a", "app");
    final var actionGrant = grant("a", "action");
    final var aliasGrant = grant("a", "alias");
    final var unrelatedGrant = grant("b", "host");
    final var platformGrant = grant(null, "host");
    final var previous = grant("a", "host");
    previous.setDeletedAt(Instant.parse("2025-01-01T00:00:00Z"));
    em.flush();
    em.remove(source);
    em.flush();
    service.retire(Set.of("host"), Set.of("host"));
    assertThat(row("a", "host").isFormallyOffline()).isTrue();
    assertThat(List.of(directGrant, appGrant, actionGrant, aliasGrant, platformGrant))
        .allSatisfy(grant -> assertThat(grant.getDeletedAt()).isNotNull());
    assertThat(previous.getDeletedAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
    assertThat(unrelatedGrant.getDeletedAt()).isNull();
    assertThat(unrelated.isEnabled()).isTrue();
    source("host", null).setGrantable(false);
    service.synchronize(Set.of("host"));
    assertThat(row("a", "host").isEnabled()).isTrue();
    assertThat(row("a", "host").isFormallyOffline()).isFalse();
    assertThat(appGrant.getDeletedAt()).isNotNull();
    assertThat(aliasGrant.getDeletedAt()).isNotNull();
    assertThat(
            em.createQuery("select count(g) from RoleResourceGrant g", Long.class)
                .getSingleResult())
        .isEqualTo(7L);
  }

  @Test
  void renamesKeepTenantAndPlatformPolicyNamespacesIndependentIncludingRevokedHistory() {
    final var source = source("resource", null);
    mirror("a", "resource");
    custom("b", "resource");
    final var own = grant("a", "resource");
    final var other = grant("b", "resource");
    final var platform = grant(null, "resource");
    var historical = grant("a", "resource");
    historical.setDeletedAt(Instant.parse("2025-01-01T00:00:00Z"));
    final var ownScope = scope("a", "resource");
    final var otherScope = scope("b", "resource");
    final var platformScope = scope(null, "resource");
    var template = new org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance();
    template.setApplicationCode("CORE");
    template.setResourceCode("resource");
    em.persist(template);
    source.setCode("renamed");
    service.renamePlatformCode("resource", "renamed");
    service.synchronize(Set.of("resource"));
    assertThat(row("a", "resource").getCode()).isEqualTo("renamed");
    assertThat(template.getResourceCode()).isEqualTo("renamed");
    assertThat(List.of(own, platform, historical))
        .allSatisfy(grant -> assertThat(grant.getResourceCode()).isEqualTo("renamed"));
    assertThat(ownScope.getResourceCode()).isEqualTo("renamed");
    assertThat(platformScope.getResourceCode()).isEqualTo("renamed");
    assertThat(other.getResourceCode()).isEqualTo("resource");
    assertThat(otherScope.getResourceCode()).isEqualTo("resource");
    assertThat(historical.getDeletedAt()).isNotNull();
  }

  @Test
  void definitionCollisionRollsBackSourceAndEarlierTenantEffects() {
    source("resource", null);
    mirror("a", "resource");
    mirror("b", "resource");
    custom("b", "conflict");
    em.getTransaction().commit();
    em.clear();
    em.beginTransaction();
    em.find(Resource.class, "resource").setCode("conflict");
    assertThatThrownBy(() -> service.synchronize(Set.of("resource")))
        .isInstanceOf(org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException.class);
    em.getTransaction().rollback();
    em.clear();
    em.beginTransaction();
    assertThat(em.find(Resource.class, "resource").getCode()).isEqualTo("resource");
    assertThat(row("a", "resource").getCode()).isEqualTo("resource");
    assertThat(row("b", "resource").getCode()).isEqualTo("resource");
    assertThat(version("a")).isZero();
    assertThat(version("b")).isZero();
  }

  @Test
  void retirementTraversesAllDependentsBeyondFiveHundredWithBoundedQueries() {
    source("host", null);
    mirror("a", "host");
    for (int index = 0; index < 521; index++) {
      String id = "alias-" + index;
      custom("a", id).setTargetResourceId("host");
      grant("a", id);
    }
    em.flush();
    statements.clear();
    service.retire(Set.of("host"), Set.of("host"));
    assertThat(
            em.createQuery(
                    "select count(g) from RoleResourceGrant g where g.deletedAt is not null",
                    Long.class)
                .getSingleResult())
        .isEqualTo(521L);
    var membership = java.util.regex.Pattern.compile("(?i)\\bin\\s*\\(([^)]*)\\)");
    for (String sql : statements) {
      var matcher = membership.matcher(sql);
      while (matcher.find()) {
        assertThat(matcher.group(1).chars().filter(c -> c == '?').count()).isLessThanOrEqualTo(100);
      }
    }
  }

  private long version(String tenant) {
    return em.createQuery(
            "select t.authorizationVersion from Tenant t where t.id = :tenant", Long.class)
        .setParameter("tenant", tenant)
        .getSingleResult();
  }

  private void tenant(String id) {
    var row = new Tenant();
    row.setId(id);
    row.setName(id);
    row.setOwnerId("owner");
    row.setTenantType(TenantType.ORGANIZATION);
    row.setAuthorizationVersion(0L);
    em.persist(row);
  }

  private Resource source(String id, String parent) {
    var row = new Resource();
    row.setId(id);
    row.setCode(id);
    row.setName(id);
    row.setType(ResourceType.PAGE);
    row.setGrantable(true);
    row.setDisabled(false);
    row.setScopeTypes(Set.of(ResourceScopeType.TENANT));
    row.setParentId(parent);
    row.setComponent("common/Runtime");
    em.persist(row);
    return row;
  }

  private void mirror(String tenant, String id) {
    em.flush();
    var importer = new TenantResourcePlatformImporter(em);
    importer.apply(importer.prepare(tenant, Set.of(id), Instant.now(), false, Set.of(id), false));
    em.flush();
  }

  private TenantResource row(String tenant, String id) {
    return em.find(TenantResource.class, new TenantResourceKey(tenant, id));
  }

  private TenantResource custom(String tenant, String id) {
    var row = new TenantResource();
    row.setTenantId(tenant);
    row.setId(id);
    row.setCode(id);
    row.setName(id);
    row.setType(ResourceType.PAGE);
    row.setGrantable(true);
    em.persist(row);
    return row;
  }

  private RoleResourceGrant grant(String tenant, String code) {
    var row = new RoleResourceGrant();
    row.setTenantId(tenant == null ? "__platform__" : tenant);
    row.setRoleId("role");
    row.setResourceCode(code);
    em.persist(row);
    return row;
  }

  private DataScope scope(String tenant, String code) {
    var row = new DataScope();
    row.setTenantId(tenant == null ? "__platform__" : tenant);
    row.setName(code);
    row.setResourceCode(code);
    row.setType(org.simplepoint.security.entity.DataScopeType.ALL);
    em.persist(row);
    return row;
  }
}
