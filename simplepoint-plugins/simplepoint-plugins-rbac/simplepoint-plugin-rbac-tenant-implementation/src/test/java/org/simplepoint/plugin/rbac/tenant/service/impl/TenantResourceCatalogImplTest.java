package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

class TenantResourceCatalogImplTest {
  SessionFactory factory;
  Session em;
  Tenant tenant;
  TenantRepository tenants;
  TenantPackageRelevanceRepository packages;
  ResourceRepository platform;
  TenantResourceCatalogImpl catalog;
  Resource group;
  Resource page;
  Resource unlicensed;
  ApplicationResourceRelevance initialLicense;

  @BeforeEach
  void setup() {
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting(
                "hibernate.connection.url", "jdbc:h2:mem:tenant-catalog-" + UUID.randomUUID())
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .applySetting("hibernate.generate_statistics", true)
            .build();
    factory =
        new MetadataSources(registry)
            .addAnnotatedClass(Tenant.class)
            .addAnnotatedClass(TenantResource.class)
            .addAnnotatedClass(Resource.class)
            .addAnnotatedClass(TenantPackageRelevance.class)
            .addAnnotatedClass(PackageApplicationRelevance.class)
            .addAnnotatedClass(ApplicationResourceRelevance.class)
            .addAnnotatedClass(RoleResourceGrant.class)
            .buildMetadata()
            .buildSessionFactory();
    em = factory.openSession();
    em.beginTransaction();
    tenant = new Tenant();
    tenant.setName("Workspace");
    tenant.setOwnerId("owner");
    tenant.setTenantType(TenantType.ORGANIZATION);
    tenant.setAuthorizationVersion(0L);
    em.persist(tenant);
    group = resource("app.group", ResourceType.GROUP, null);
    page = resource("app.page", ResourceType.PAGE, group.getId());
    unlicensed = resource("app.secret", ResourceType.PAGE, group.getId());
    var subscription = new TenantPackageRelevance();
    subscription.setTenantId(tenant.getId());
    subscription.setPackageCode("catalog-package");
    em.persist(subscription);
    var application = new PackageApplicationRelevance();
    application.setPackageCode(subscription.getPackageCode());
    application.setApplicationCode("catalog-app");
    em.persist(application);
    initialLicense = new ApplicationResourceRelevance();
    initialLicense.setApplicationCode(application.getApplicationCode());
    initialLicense.setResourceCode(page.getCode());
    em.persist(initialLicense);
    em.flush();
    tenants = mock(TenantRepository.class);
    packages = mock(TenantPackageRelevanceRepository.class);
    platform = mock(ResourceRepository.class);
    when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));
    when(platform.loadAll()).thenAnswer(call -> List.of(group, page, unlicensed));
    when(platform.findAllByCodes(anyCollection()))
        .thenAnswer(
            call -> {
              Set<String> codes = Set.copyOf(call.getArgument(0));
              return List.of(group, page, unlicensed).stream()
                  .filter(resource -> codes.contains(resource.getCode()))
                  .toList();
            });
    when(packages.findResourceCodesByTenantId(tenant.getId())).thenReturn(Set.of(page.getCode()));
    catalog = new TenantResourceCatalogImpl(em, tenants, packages, platform);
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("owner");
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", tenant.getId()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private Resource resource(String code, ResourceType type, String parentId) {
    Resource result = new Resource();
    result.setCode(code);
    result.setName(code);
    result.setType(type);
    result.setParentId(parentId);
    result.setPath(type == ResourceType.PAGE ? "/" + code : null);
    result.setComponent(type == ResourceType.PAGE ? "common/Page" : null);
    result.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
    result.setGrantable(type == ResourceType.PAGE);
    result.setPublicAccess(false);
    result.setDisabled(false);
    em.persist(result);
    return result;
  }

  private TenantResource pickerEntry(Resource resource, String name) {
    return pickerEntry(resource, name, tenant.getId());
  }

  private TenantResource pickerEntry(Resource resource, String name, String tenantScope) {
    var row = new TenantResource();
    row.setTenantId(tenantScope);
    row.setId(resource.getId());
    row.setPlatformResourceId(resource.getId());
    row.setCode(resource.getCode());
    row.setName(name);
    row.setType(resource.getType());
    row.setGrantable(resource.getGrantable());
    row.setSort(0);
    em.persist(row);
    return row;
  }

  @Test
  void aliasesFollowRetiredPagesWithoutLosingTheirNamePathOrAuthorizationCodes() {
    catalog.selectMode(TenantResourceMode.CUSTOM);
    TenantResource alias =
        catalog.create(
            new TenantResourceCatalog.Mutation(
                group.getId(),
                "My custom workflow name",
                ResourceType.PAGE,
                "/workspace/custom-tasks",
                page.getId(),
                null,
                0,
                null,
                true));
    var before = catalog.effectivePlatformCodes(tenant.getId(), Set.of(alias.getCode()));
    page.setComponent("common/personal/Todos");
    page.setRouteKind("hidden");
    catalog.synchronize(false);
    assertEquals("common/personal/Todos", alias.getComponent());
    assertEquals("hidden", alias.getRouteKind());
    assertEquals("My custom workflow name", alias.getName());
    assertEquals("/workspace/custom-tasks", alias.getPath());
    assertTrue(alias.isEnabled());
    assertEquals(before, catalog.effectivePlatformCodes(tenant.getId(), Set.of(alias.getCode())));
    catalog.synchronize(false);
    assertEquals("hidden", alias.getRouteKind());
  }

  @Test
  void pickerUsesLiveSqlEligibilityBeforePaginationAndSelectedLookup() {
    catalog.selectMode(TenantResourceMode.CUSTOM);
    var licensed = initialLicense;
    TenantResource last = null;
    for (int i = 0; i < 521; i++) {
      var live = resource("picker." + i, ResourceType.PAGE, null);
      live.setPublicAccess(true);
      last = pickerEntry(live, String.format("Choice%03d", i));
    }
    var hidden = resource("picker.hidden", ResourceType.PAGE, null);
    hidden.setPublicAccess(true);
    hidden.setRouteKind("hidden");
    pickerEntry(hidden, "Choice hidden");
    var disabled = resource("picker.disabled", ResourceType.PAGE, null);
    disabled.setPublicAccess(true);
    disabled.setDisabled(true);
    pickerEntry(disabled, "Choice disabled");
    var deleted = resource("picker.deleted", ResourceType.PAGE, null);
    deleted.setPublicAccess(true);
    deleted.setDeletedAt(java.time.Instant.now());
    pickerEntry(deleted, "Choice deleted");
    var platformOnly = resource("picker.platform", ResourceType.PAGE, null);
    platformOnly.setPublicAccess(true);
    platformOnly.setScopeTypes(EnumSet.of(ResourceScopeType.PLATFORM));
    pickerEntry(platformOnly, "Choice platform");
    var foreign = resource("picker.foreign", ResourceType.PAGE, null);
    foreign.setPublicAccess(true);
    pickerEntry(foreign, "Choice foreign", "another");
    var notGranted = resource("picker.notGranted", ResourceType.PAGE, null);
    pickerEntry(notGranted, "Choice not granted");
    em.flush();
    em.clear();
    factory.getStatistics().clear();
    clearInvocations(packages, platform);
    var result =
        catalog.picker(
            TenantResourceCatalog.PickerKind.PAGE_TARGET,
            null,
            null,
            "Choice",
            null,
            PageRequest.of(26, 20));
    assertEquals(521, result.getTotalElements());
    assertEquals(1, result.getContent().size());
    assertEquals(last.getId(), result.getContent().getFirst().getId());
    assertEquals(
        1,
        factory.getStatistics().getEntityLoadCount(),
        "the live eligibility query does not hydrate the platform catalog");
    verifyNoInteractions(packages, platform);
    em.clear();
    factory.getStatistics().clear();
    var selected =
        catalog.picker(
            TenantResourceCatalog.PickerKind.PAGE_TARGET,
            null,
            null,
            "",
            last.getId(),
            PageRequest.of(0, 1));
    assertEquals(last.getId(), selected.getContent().getFirst().getId());
    assertEquals(1, factory.getStatistics().getEntityLoadCount());
    for (String id :
        List.of(
            hidden.getId(),
            disabled.getId(),
            deleted.getId(),
            platformOnly.getId(),
            foreign.getId(),
            notGranted.getId(),
            "' OR 1=1 --")) {
      assertEquals(
          0,
          catalog
              .picker(
                  TenantResourceCatalog.PickerKind.PAGE_TARGET,
                  null,
                  null,
                  "",
                  id,
                  PageRequest.of(0, 1))
              .getTotalElements());
    }
    assertEquals(
        1,
        catalog
            .picker(
                TenantResourceCatalog.PickerKind.PAGE_TARGET,
                null,
                null,
                "",
                page.getId(),
                PageRequest.of(0, 1))
            .getTotalElements());
    licensed = em.find(ApplicationResourceRelevance.class, licensed.getId());
    licensed.setDeletedAt(java.time.Instant.now());
    em.flush();
    assertEquals(
        0,
        catalog
            .picker(
                TenantResourceCatalog.PickerKind.PAGE_TARGET,
                null,
                null,
                "",
                page.getId(),
                PageRequest.of(0, 1))
            .getTotalElements(),
        "revoked entitlement is removed before pagination without synchronizing snapshots");
  }

  @Test
  void parentPickerHonorsChildTypeExclusionAndBounds() {
    catalog.selectMode(TenantResourceMode.CUSTOM);
    var feature = pickerEntry(resource("feature", ResourceType.FEATURE, null), "feature");
    pickerEntry(resource("action", ResourceType.ACTION, null), "action");
    pickerEntry(resource("api", ResourceType.API, null), "api");
    em.flush();
    assertEquals(
        0,
        catalog
            .picker(
                TenantResourceCatalog.PickerKind.PARENT,
                ResourceType.PAGE,
                null,
                "",
                feature.getId(),
                PageRequest.of(0, 1))
            .getTotalElements());
    assertEquals(
        1,
        catalog
            .picker(
                TenantResourceCatalog.PickerKind.PARENT,
                ResourceType.ACTION,
                null,
                "",
                feature.getId(),
                PageRequest.of(0, 1))
            .getTotalElements());
    assertEquals(
        0,
        catalog
            .picker(
                TenantResourceCatalog.PickerKind.PARENT,
                ResourceType.ACTION,
                feature.getId(),
                "",
                feature.getId(),
                PageRequest.of(0, 1))
            .getTotalElements());
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () ->
            catalog.picker(
                TenantResourceCatalog.PickerKind.PARENT,
                null,
                null,
                "",
                null,
                PageRequest.of(0, 20)));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () ->
            catalog.picker(
                TenantResourceCatalog.PickerKind.PAGE_TARGET,
                null,
                null,
                "",
                null,
                PageRequest.of(0, 51)));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () ->
            catalog.picker(
                TenantResourceCatalog.PickerKind.PAGE_TARGET,
                null,
                null,
                "",
                "x".repeat(37),
                PageRequest.of(0, 1)));
  }

  @Test
  void resourcePickerNeverAllowsAnOrdinaryMemberToBrowseManagerChoices() {
    var member = new AuthorizationContext();
    member.setUserId("ordinary-member");
    member.setScopeType(AuthorizationScopeType.TENANT);
    member.setAttributes(Map.of("X-Tenant-Id", tenant.getId()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, member);
    assertThrows(
        AccessDeniedException.class,
        () ->
            catalog.picker(
                TenantResourceCatalog.PickerKind.PARENT,
                ResourceType.PAGE,
                null,
                "",
                null,
                PageRequest.of(0, 20)));
  }

  @AfterEach
  void close() {
    if (em != null) {
      if (em.getTransaction().isActive()) {
        em.getTransaction().rollback();
      }
      em.close();
    }
    if (factory != null) {
      factory.close();
    }
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void ordinaryTenantMemberCannotChangeModeOrManageCatalog() {
    AuthorizationContext member = new AuthorizationContext();
    member.setUserId("member");
    member.setScopeType(AuthorizationScopeType.TENANT);
    member.setAttributes(Map.of("X-Tenant-Id", tenant.getId()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, member);
    assertThrows(AccessDeniedException.class, () -> catalog.selectMode(TenantResourceMode.CUSTOM));
    assertThrows(
        AccessDeniedException.class, () -> catalog.list(null, null, true, PageRequest.of(0, 20)));
    assertThrows(AccessDeniedException.class, catalog::querySchema);
  }

  @Test
  void resourceHeadersFilterAndSortBeforePagingWithinTenantAndParent() {
    for (int index = 0; index < 30; index++) {
      var row = new TenantResource();
      row.setTenantId(index == 28 ? "other" : tenant.getId());
      row.setId("header-" + index);
      row.setCode("header." + index);
      row.setName("Node %02d".formatted(index));
      row.setType(ResourceType.PAGE);
      row.setSort(index / 2);
      row.setEnabled(true);
      row.setParentId(index == 29 ? null : "parent");
      row.setPlatformResourceId(index % 2 == 0 ? "source-" + index : null);
      em.persist(row);
    }
    em.flush();
    em.clear();
    var filters = Map.of("name", "like:NODE", "origin", "equals:PLATFORM", "type", "equals:PAGE");
    var result =
        catalog.query(
            filters,
            "parent",
            false,
            PageRequest.of(
                1,
                5,
                org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.DESC, "name")));
    assertEquals(14L, result.getTotalElements());
    assertEquals(
        List.of("Node 16", "Node 14", "Node 12", "Node 10", "Node 08"),
        result.getContent().stream().map(TenantResource::getName).toList());
    var root = catalog.query(Map.of("name", "like:Node"), null, true, PageRequest.of(0, 10));
    assertEquals(1L, root.getTotalElements());
    assertEquals("Node 29", root.getContent().getFirst().getName());
    String numeric =
        "{\"version\":1,\"where\":{\"field\":\"sort\",\"op\":\"than:greater\",\"value\":10}}";
    assertEquals(
        6L,
        catalog
            .query(Map.of("$query", numeric), "parent", false, PageRequest.of(0, 20))
            .getTotalElements());
    var schema =
        new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(catalog.querySchema());
    var origins = new java.util.HashSet<String>();
    schema.at("/schema/properties/origin/oneOf").forEach(choice -> origins.add(choice.path("const").asText()));
    assertEquals(Set.of("PLATFORM", "MANAGED", "TENANT"), origins);
    assertTrue(schema.at("/schema/properties/tenantId").isMissingNode());
  }

  @Test
  void resourceQueryNeverAcceptsInternalFieldsOrHiddenDerivedOrigin() {
    for (String field :
        List.of("tenantId", "targetResourceId", "platformResourceId", "localModified")) {
      assertThrows(
          org.simplepoint.core.query.InvalidQueryException.class,
          () -> catalog.query(Map.of(field, "is:null"), null, false, PageRequest.of(0, 20)));
    }
    org.simplepoint.core.AuthorizationContextHolder.getContext()
        .setFieldPermissions(
            Map.of(TenantResource.class.getName() + "#platformResourceId", "HIDDEN"));
    var schema =
        new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(catalog.querySchema());
    assertTrue(schema.at("/schema/properties/origin").isMissingNode());
    assertThrows(
        org.simplepoint.core.query.InvalidQueryException.class,
        () ->
            catalog.query(Map.of("origin", "equals:PLATFORM"), null, false, PageRequest.of(0, 20)));
  }

  @Test
  void firstEnableImportsOnlyEntitledResourceAndItsStructuralParentWithExactIds() {
    assertEquals(TenantResourceMode.CUSTOM, catalog.selectMode(TenantResourceMode.CUSTOM));
    em.flush();
    var imported = catalog.activeResources(tenant.getId());
    assertEquals(2, imported.size());
    assertNull(
        em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), unlicensed.getId())));
    TenantResource copy =
        em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), page.getId()));
    assertEquals(page.getId(), copy.getPlatformResourceId());
    assertEquals(page.getCode(), copy.getCode());
    assertEquals(group.getId(), copy.getParentId());
    assertEquals(
        Set.of(page.getCode()),
        catalog.grantableCodes(tenant.getId(), Set.of(page.getCode(), group.getCode())));
  }

  @Test
  void deletingTenantPageRetiresItsRoleGrants() {
    catalog.selectMode(TenantResourceMode.CUSTOM);
    TenantResource alias =
        catalog.create(
            new TenantResourceCatalog.Mutation(
                group.getId(),
                "Alias",
                ResourceType.PAGE,
                "/workspace/alias",
                page.getId(),
                null,
                0,
                null,
                true));
    RoleResourceGrant grant = new RoleResourceGrant();
    grant.setTenantId(tenant.getId());
    grant.setRoleId("role-1");
    grant.setResourceCode(alias.getCode());
    em.persist(grant);
    em.flush();

    catalog.delete(alias.getId());

    assertNull(em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), alias.getId())));
    em.refresh(grant);
    assertNotNull(grant.getDeletedAt());
  }

  @Test
  void regularUpdatePreservesLocalDisableWhileOverwriteResetsIt() {
    catalog.selectMode(TenantResourceMode.CUSTOM);
    catalog.update(
        page.getId(),
        new TenantResourceCatalog.Mutation(
            group.getId(),
            "Custom page label",
            ResourceType.PAGE,
            null,
            null,
            null,
            1,
            null,
            false));
    TenantResource copy =
        em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), page.getId()));
    assertFalse(copy.isEnabled());
    assertTrue(copy.isManualDisabled());
    catalog.synchronize(false);
    assertFalse(copy.isEnabled());
    assertEquals("Custom page label", copy.getName());
    catalog.synchronize(true);
    assertTrue(copy.isEnabled());
    assertEquals(page.getName(), copy.getName());
  }

  @Test
  void platformRevocationStopsRoleGrantBeforeCatalogRefresh() {
    catalog.selectMode(TenantResourceMode.CUSTOM);
    assertEquals(
        Set.of(page.getCode()), catalog.grantableCodes(tenant.getId(), Set.of(page.getCode())));
    when(packages.findResourceCodesByTenantId(tenant.getId())).thenReturn(Set.of());
    initialLicense.setDeletedAt(java.time.Instant.now());
    em.flush();
    assertTrue(catalog.grantableCodes(tenant.getId(), Set.of(page.getCode())).isEmpty());
    assertTrue(catalog.effectivePlatformCodes(tenant.getId(), Set.of(page.getCode())).isEmpty());
    assertTrue(
        em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), page.getId()))
            .isEnabled());
  }

  @Test
  void incrementalRefreshImportsNewGrantAndDeactivatesRevokedPageAlias() {
    catalog.selectMode(TenantResourceMode.CUSTOM);
    TenantResource alias =
        catalog.create(
            new TenantResourceCatalog.Mutation(
                group.getId(),
                "Alias",
                ResourceType.PAGE,
                "/workspace/alias",
                page.getId(),
                null,
                0,
                null,
                true));
    assertTrue(alias.isEnabled());
    when(packages.findResourceCodesByTenantId(tenant.getId()))
        .thenReturn(Set.of(unlicensed.getCode()));
    var preview = catalog.previewSynchronization(false);
    assertEquals(1, preview.added());
    assertEquals(unlicensed.getId(), preview.additions().getFirst().id());
    assertEquals(2, preview.deactivated());
    var result = catalog.synchronize(false);
    assertEquals(1, result.added());
    assertFalse(
        em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), page.getId()))
            .isEnabled());
    assertFalse(alias.isEnabled());
    assertTrue(catalog.effectivePlatformCodes(tenant.getId(), Set.of(alias.getCode())).isEmpty());
    assertEquals(
        unlicensed.getId(),
        em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), unlicensed.getId()))
            .getPlatformResourceId());
  }
}
