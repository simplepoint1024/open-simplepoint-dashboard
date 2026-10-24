package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
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
  TenantEntitlementDatabase database;
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
  void setup() throws Exception {
    database = new TenantEntitlementDatabase();
    factory =
        database.createWithSettings(
            Map.of("hibernate.generate_statistics", true),
            Tenant.class,
            TenantResource.class,
            org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup.class,
            org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceEntitlement.class,
            org.simplepoint.plugin.rbac.tenant.api.entity.Application.class,
            Resource.class,
            TenantPackageRelevance.class,
            PackageApplicationRelevance.class,
            ApplicationResourceRelevance.class,
            RoleResourceGrant.class);
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
    initialLicense.setApplicationCode("simplepoint-core");
    initialLicense.setResourceCode(page.getCode());
    em.persist(initialLicense);
    for (String code :
        org.simplepoint.plugin.rbac.tenant.api.constants.BuiltInTenantCodes.BASELINE_APPLICATIONS) {
      var base = new org.simplepoint.plugin.rbac.tenant.api.entity.Application();
      base.setCode(code);
      base.setName(code);
      base.setEnabled(true);
      em.persist(base);
    }
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
    var provisioning =
        new TenantResourceProvisioningServiceImpl(
            em,
            mock(
                org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService
                    .class));
    catalog = new TenantResourceCatalogImpl(em, tenants, provisioning);
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("owner");
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", tenant.getId()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    provisioning.initialize(tenant.getId());
  }

  @Test
  void localizedNamesPersistAtomicallyAndPlatformRefreshPreservesTenantOverrides() {
    var value = new org.simplepoint.api.locale.LocalizedText("客户", "zh_CN", Map.of("zh-CN", "客户", "en-US", "Customers", "ja-JP", "顧客"));
    catalog.update(page.getId(), localizedMutation(value));
    em.flush();
    em.clear();
    var row = catalog.get(page.getId());
    assertEquals("zh-CN", row.getNameLocalization().defaultLocale());
    assertEquals("Customers", row.getNameLocalization().translations().get("en-US"));
    assertEquals("app.page", em.find(Resource.class, page.getId()).getName());
    catalog.synchronize(false);
    assertEquals("客户", catalog.get(page.getId()).getName());
    catalog.synchronize(true);
    row = catalog.get(page.getId());
    assertEquals("app.page", row.getName());
    assertEquals("Customers", row.getNameLocalization().translations().get("en-US"));
    assertEquals("客户", row.getNameLocalization().translations().get("zh-CN"));
    var legacy = new TenantResourceCatalog.Mutation(group.getId(), row.getName(), ResourceType.PAGE, null, null, null, 2, "说明", true);
    catalog.update(page.getId(), legacy);
    assertEquals("顧客", catalog.get(page.getId()).getNameLocalization().translations().get("ja-JP"));
  }

  @Test
  void localizedWritesRespectFieldPoliciesAndRejectInvalidTranslationsBeforeMutation() {
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    var value = new org.simplepoint.api.locale.LocalizedText("客户", "zh-CN", Map.of("en-US", "Customers"));
    context.setFieldPermissions(Map.of(TenantResource.class.getName()+"#name", "VISIBLE"));
    assertThrows(AccessDeniedException.class, () -> catalog.update(page.getId(), localizedMutation(value)));
    assertNull(catalog.get(page.getId()).getNameLocalization());
    // An unchanged legacy value supplied by a read-only editor is not a write.
    catalog.update(page.getId(), localizedMutation(new org.simplepoint.api.locale.LocalizedText(page.getName(), "zh-CN", Map.of())));
    assertNull(catalog.get(page.getId()).getNameLocalization());
    context = fieldContext(Map.of());
    var invalid = new org.simplepoint.api.locale.LocalizedText("客户", "zh-CN", Map.of("en", " "));
    assertThrows(org.simplepoint.plugin.rbac.tenant.api.error.LocalizedResourceNameException.class,
        () -> catalog.update(page.getId(), localizedMutation(invalid)));
    assertNull(catalog.get(page.getId()).getNameLocalization());
    catalog.update(page.getId(), localizedMutation(value));
    context = fieldContext(Map.of(TenantResource.class.getName()+"#name", "VISIBLE"));
    catalog.update(page.getId(), localizedMutation(value));
    var renamed = new org.simplepoint.api.locale.LocalizedText("客户", "zh-CN", Map.of("en-US", "Modified"));
    assertThrows(AccessDeniedException.class, () -> catalog.update(page.getId(), localizedMutation(renamed)));
  }

  private AuthorizationContext fieldContext(Map<String, String> policy) {
    var context = new AuthorizationContext();
    context.setUserId("owner");
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", tenant.getId()));
    context.setFieldPermissions(policy);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    return context;
  }

  private TenantResourceCatalog.Mutation localizedMutation(org.simplepoint.api.locale.LocalizedText value) {
    return new TenantResourceCatalog.Mutation(group.getId(), value.defaultValue(), ResourceType.PAGE,
        null, null, null, 1, null, true, value);
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
    row.setScopeTypes(resource.getScopeTypes());
    row.setRouteKind(resource.getRouteKind());
    row.setBaselineEntitled(Boolean.TRUE.equals(resource.getPublicAccess()));
    row.setSourceAvailable(
        !Boolean.TRUE.equals(resource.getDisabled()) && resource.getDeletedAt() == null);
    row.setSort(0);
    em.persist(row);
    return row;
  }

  @Test
  void ownerSchemaAndRowActionsPreserveSourceManagementBoundaries() throws Exception {
    catalog.synchronize(false);
    var platformRow = catalog.get(page.getId());
    assertEquals(List.of("edit"), platformRow.getAllowedActions());
    var local =
        catalog.create(
            new TenantResourceCatalog.Mutation(
                null, "Local", ResourceType.GROUP, null, null, null, null, null, true));
    assertEquals(List.of("edit", "delete"), local.getAllowedActions());
    var managed = new TenantResource();
    managed.setTenantId(tenant.getId());
    managed.setId("managed");
    managed.setManagedOwner("app");
    managed.setManagedKey("managed");
    managed.setCode("app.managed");
    managed.setName("Managed");
    managed.setType(ResourceType.PAGE);
    em.persist(managed);
    em.flush();
    assertEquals(List.of(), catalog.get("managed").getAllowedActions());
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> catalog.delete("managed"));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () ->
            catalog.update(
                "managed",
                new TenantResourceCatalog.Mutation(
                    null, "Changed", ResourceType.PAGE, null, null, null, null, null, true)));
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var schema = mapper.valueToTree(catalog.querySchema());
    var keys = new java.util.ArrayList<String>();
    schema.path("buttons").forEach(button -> keys.add(button.path("key").asText()));
    assertEquals(List.of("sync", "overwrite", "add", "edit", "delete"), keys);
    var output =
        java.nio.file.Path.of("build/generated-test-resources/tenant-resource-schema.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(output, mapper.writeValueAsString(schema));
  }

  @Test
  void aliasesFollowRetiredPagesWithoutLosingTheirNamePathOrAuthorizationCodes() {
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
    final var before = catalog.effectivePlatformCodes(tenant.getId(), Set.of(alias.getCode()));
    page.setComponent("common/personal/Todos");
    page.setRouteKind("hidden");
    catalog.synchronize(false);
    var projected =
        catalog.navigationResources(tenant.getId(), Set.of(alias.getCode())).stream()
            .filter(row -> row.getId().equals(alias.getId()))
            .findFirst()
            .orElseThrow();
    assertEquals("common/personal/Todos", projected.getComponent());
    assertEquals("hidden", projected.getRouteKind());
    assertEquals("My custom workflow name", alias.getName());
    assertEquals("/workspace/custom-tasks", alias.getPath());
    assertTrue(alias.isEnabled());
    assertEquals(before, catalog.effectivePlatformCodes(tenant.getId(), Set.of(alias.getCode())));
    catalog.synchronize(false);
    assertEquals(
        "hidden",
        catalog.navigationResources(tenant.getId(), Set.of(alias.getCode())).stream()
            .filter(row -> row.getId().equals(alias.getId()))
            .findFirst()
            .orElseThrow()
            .getRouteKind());
  }

  @Test
  void pickerUsesLiveSqlEligibilityBeforePaginationAndSelectedLookup() {
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
        2,
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
    assertEquals(2, factory.getStatistics().getEntityLoadCount());
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
    em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), page.getId()))
        .setBaselineEntitled(false);
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
    var feature = pickerEntry(resource("feature", ResourceType.FEATURE, null), "feature");
    feature.setBaselineEntitled(true);
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
  void close() throws Exception {
    if (em != null) {
      if (em.getTransaction().isActive()) {
        em.getTransaction().rollback();
      }
      em.close();
    }
    if (database != null) {
      database.close();
    }
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void ordinaryTenantMemberCannotManageCatalog() {
    AuthorizationContext member = new AuthorizationContext();
    member.setUserId("member");
    member.setScopeType(AuthorizationScopeType.TENANT);
    member.setAttributes(Map.of("X-Tenant-Id", tenant.getId()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, member);
    assertThrows(
        AccessDeniedException.class, () -> catalog.list(null, null, true, PageRequest.of(0, 20)));
    assertThrows(AccessDeniedException.class, catalog::querySchema);
  }

  private void managementContext(String user, AuthorizationScopeType scope, boolean administrator) {
    var context = new AuthorizationContext();
    context.setUserId(user);
    context.setScopeType(scope);
    context.setIsAdministrator(administrator);
    context.setAttributes(Map.of("X-Tenant-Id", tenant.getId()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @Test
  void administratorFlagCannotReplaceCurrentOwnershipAndTransfersTakeEffectImmediately() {
    managementContext("administrator", AuthorizationScopeType.TENANT, true);
    assertThrows(AccessDeniedException.class, catalog::querySchema);
    managementContext("owner", AuthorizationScopeType.TENANT, true);
    assertNotNull(catalog.querySchema());
    // Keep the already-loaded tenant stale; the management boundary must query the current owner.
    em.createQuery("update Tenant t set t.ownerId='new-owner' where t.id=:id")
        .setParameter("id", tenant.getId())
        .executeUpdate();
    assertThrows(AccessDeniedException.class, catalog::querySchema);
    managementContext("new-owner", AuthorizationScopeType.TENANT, true);
    assertNotNull(catalog.querySchema());
  }

  @Test
  void personalOwnerUsesTheSameIndependentCatalogAndWrongScopeIsRejected() {
    tenant.setTenantType(TenantType.PERSONAL);
    em.flush();
    assertThrows(AccessDeniedException.class, catalog::querySchema);
    managementContext("owner", AuthorizationScopeType.PERSONAL, false);
    assertNotNull(catalog.querySchema());
    assertEquals(2, catalog.list(null, null, false, PageRequest.of(0, 20)).getTotalElements());
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
    schema
        .at("/schema/properties/origin/oneOf")
        .forEach(choice -> origins.add(choice.path("const").asText()));
    assertEquals(Set.of("PLATFORM", "APP", "REPORT", "TENANT"), origins);
    assertTrue(schema.at("/schema/properties/availability/readOnly").asBoolean());
    assertTrue(schema.at("/schema/x-query/fields/availability").isMissingNode());
    assertThrows(
        org.simplepoint.core.query.InvalidQueryException.class,
        () ->
            catalog.query(
                Map.of("availability", "eq:AVAILABLE"), null, false, PageRequest.of(0, 20)));
    assertThrows(
        org.simplepoint.core.query.InvalidQueryException.class,
        () ->
            catalog.query(
                Map.of(),
                null,
                false,
                PageRequest.of(
                    0, 20, org.springframework.data.domain.Sort.by("entitlementExpiresAt"))));
    assertTrue(schema.at("/schema/properties/tenantId").isMissingNode());
  }

  @Test
  void sourceFiltersMatchSerializedOriginsAndKeepTenantIsolation() {
    for (String tenantId : List.of(tenant.getId(), "another-tenant")) {
      for (String owner : List.of("app", "report")) {
        var row = new TenantResource();
        row.setTenantId(tenantId);
        row.setId(owner);
        row.setManagedOwner(owner);
        row.setManagedKey("page:example");
        row.setCode(owner + ".example");
        row.setName(owner);
        row.setType(ResourceType.PAGE);
        em.persist(row);
      }
    }
    em.flush();
    em.clear();
    for (String source : List.of("APP", "REPORT")) {
      var result =
          catalog.query(Map.of("origin", "equals:" + source), null, true, PageRequest.of(0, 1));
      assertEquals(1, result.getTotalElements());
      assertEquals(source, result.getContent().getFirst().getOrigin().name());
      assertEquals(tenant.getId(), result.getContent().getFirst().getTenantId());
    }
    assertEquals(
        0,
        catalog
            .query(Map.of("origin", "equals:TENANT"), null, false, PageRequest.of(0, 20))
            .getTotalElements());
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
    for (String field :
        List.of("availability", "entitlementKind", "entitlementStartsAt", "entitlementExpiresAt")) {
      assertTrue(schema.at("/schema/properties/" + field).isMissingNode());
    }
    assertThrows(
        org.simplepoint.core.query.InvalidQueryException.class,
        () ->
            catalog.query(Map.of("origin", "equals:PLATFORM"), null, false, PageRequest.of(0, 20)));
  }

  @Test
  void initializationImportsOnlyBaselineAndStructuralParentWithExactIds() {
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
  void bothOrdinaryUpdateAndRestoreDefaultsPreserveManualDisablement() {
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
    assertFalse(copy.isEnabled());
    assertTrue(copy.isManualDisabled());
    assertEquals(page.getName(), copy.getName());
  }

  @Test
  void tenantAvailabilityRevocationStopsRoleGrantBeforeAnyRefresh() {
    assertEquals(
        Set.of(page.getCode()), catalog.grantableCodes(tenant.getId(), Set.of(page.getCode())));
    em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), page.getId()))
        .setSourceAvailable(false);
    em.flush();
    assertTrue(catalog.grantableCodes(tenant.getId(), Set.of(page.getCode())).isEmpty());
    assertTrue(catalog.effectivePlatformCodes(tenant.getId(), Set.of(page.getCode())).isEmpty());
    assertTrue(
        em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), page.getId()))
            .isEnabled());
  }

  @Test
  void incrementalRefreshImportsNewGrantAndDeactivatesRevokedPageAlias() {
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
    initialLicense.setDeletedAt(java.time.Instant.now());
    var newBaseline = new ApplicationResourceRelevance();
    newBaseline.setApplicationCode("simplepoint-core");
    newBaseline.setResourceCode(unlicensed.getCode());
    em.persist(newBaseline);
    var preview = catalog.previewSynchronization(false);
    assertEquals(1, preview.added());
    assertEquals(unlicensed.getId(), preview.additions().getFirst().id());
    assertEquals(0, preview.deactivated());
    var result = catalog.synchronize(false);
    assertEquals(1, result.added());
    assertTrue(
        em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), page.getId()))
            .isEnabled());
    assertTrue(alias.isEnabled());
    assertFalse(
        em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), page.getId()))
            .isBaselineEntitled());
    assertTrue(catalog.effectivePlatformCodes(tenant.getId(), Set.of(alias.getCode())).isEmpty());
    assertEquals(
        unlicensed.getId(),
        em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), unlicensed.getId()))
            .getPlatformResourceId());
  }
}
