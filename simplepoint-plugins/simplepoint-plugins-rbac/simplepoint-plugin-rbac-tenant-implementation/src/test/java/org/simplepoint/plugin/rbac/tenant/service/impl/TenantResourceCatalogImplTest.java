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
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.data.domain.PageRequest;

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

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:tenant-catalog-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
    factory = new MetadataSources(registry).addAnnotatedClass(Tenant.class)
        .addAnnotatedClass(TenantResource.class).addAnnotatedClass(Resource.class)
        .addAnnotatedClass(RoleResourceGrant.class)
        .buildMetadata().buildSessionFactory();
    em = factory.openSession();
    em.beginTransaction();
    tenant = new Tenant();
    tenant.setName("Workspace"); tenant.setOwnerId("owner");
    tenant.setTenantType(TenantType.ORGANIZATION); tenant.setAuthorizationVersion(0L);
    em.persist(tenant);
    group = resource("app.group", ResourceType.GROUP, null);
    page = resource("app.page", ResourceType.PAGE, group.getId());
    unlicensed = resource("app.secret", ResourceType.PAGE, group.getId());
    em.flush();
    tenants = mock(TenantRepository.class);
    packages = mock(TenantPackageRelevanceRepository.class);
    platform = mock(ResourceRepository.class);
    when(tenants.findById(tenant.getId())).thenReturn(Optional.of(tenant));
    when(platform.loadAll()).thenAnswer(call -> List.of(group, page, unlicensed));
    when(platform.findAllByCodes(anyCollection())).thenAnswer(call -> {
      Set<String> codes = Set.copyOf(call.getArgument(0));
      return List.of(group, page, unlicensed).stream()
          .filter(resource -> codes.contains(resource.getCode())).toList();
    });
    when(packages.findResourceCodesByTenantId(tenant.getId())).thenReturn(Set.of(page.getCode()));
    catalog = new TenantResourceCatalogImpl(em, tenants, packages, platform);
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("owner"); context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", tenant.getId()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private Resource resource(String code, ResourceType type, String parentId) {
    Resource result = new Resource();
    result.setCode(code); result.setName(code); result.setType(type);
    result.setParentId(parentId); result.setPath(type == ResourceType.PAGE ? "/" + code : null);
    result.setComponent(type == ResourceType.PAGE ? "common/Page" : null);
    result.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
    result.setGrantable(type == ResourceType.PAGE); result.setPublicAccess(false);
    result.setDisabled(false);
    em.persist(result);
    return result;
  }

  @AfterEach
  void close() {
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();}
    if (factory != null) factory.close();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void ordinaryTenantMemberCannotChangeModeOrManageCatalog() {
    AuthorizationContext member = new AuthorizationContext();
    member.setUserId("member"); member.setScopeType(AuthorizationScopeType.TENANT);
    member.setAttributes(Map.of("X-Tenant-Id", tenant.getId()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, member);
    assertThrows(AccessDeniedException.class, () -> catalog.selectMode(TenantResourceMode.CUSTOM));
    assertThrows(AccessDeniedException.class,
        () -> catalog.list(null, null, true, PageRequest.of(0, 20)));
  }

  @Test
  void firstEnableImportsOnlyEntitledResourceAndItsStructuralParentWithExactIds() {
    assertEquals(TenantResourceMode.CUSTOM, catalog.selectMode(TenantResourceMode.CUSTOM));
    em.flush();
    var imported = catalog.activeResources(tenant.getId());
    assertEquals(2, imported.size());
    assertNull(em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), unlicensed.getId())));
    TenantResource copy = em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), page.getId()));
    assertEquals(page.getId(), copy.getPlatformResourceId());
    assertEquals(page.getCode(), copy.getCode());
    assertEquals(group.getId(), copy.getParentId());
    assertEquals(Set.of(page.getCode()), catalog.grantableCodes(tenant.getId(), Set.of(page.getCode(), group.getCode())));
  }

  @Test
  void deletingTenantPageRetiresItsRoleGrants() {
    catalog.selectMode(TenantResourceMode.CUSTOM);
    TenantResource alias = catalog.create(new TenantResourceCatalog.Mutation(group.getId(),
        "Alias", ResourceType.PAGE, "/workspace/alias", page.getId(), null, 0, null, true));
    RoleResourceGrant grant = new RoleResourceGrant();
    grant.setTenantId(tenant.getId()); grant.setRoleId("role-1");
    grant.setResourceCode(alias.getCode());
    em.persist(grant); em.flush();

    catalog.delete(alias.getId());

    assertNull(em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), alias.getId())));
    em.refresh(grant);
    assertNotNull(grant.getDeletedAt());
  }

  @Test
  void regularUpdatePreservesLocalDisableWhileOverwriteResetsIt() {
    catalog.selectMode(TenantResourceMode.CUSTOM);
    catalog.update(page.getId(), new TenantResourceCatalog.Mutation(group.getId(),
        "Custom page label", ResourceType.PAGE, null, null, null, 1, null, false));
    TenantResource copy = em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), page.getId()));
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
    when(packages.findResourceCodesByTenantId(tenant.getId())).thenReturn(Set.of());
    assertTrue(catalog.grantableCodes(tenant.getId(), Set.of(page.getCode())).isEmpty());
    assertTrue(catalog.effectivePlatformCodes(tenant.getId(), Set.of(page.getCode())).isEmpty());
    assertTrue(em.find(TenantResource.class,
        new TenantResourceKey(tenant.getId(), page.getId())).isEnabled());
  }

  @Test
  void incrementalRefreshImportsNewGrantAndDeactivatesRevokedPageAlias() {
    catalog.selectMode(TenantResourceMode.CUSTOM);
    TenantResource alias = catalog.create(new TenantResourceCatalog.Mutation(group.getId(),
        "Alias", ResourceType.PAGE, "/workspace/alias", page.getId(), null, 0, null, true));
    assertTrue(alias.isEnabled());
    when(packages.findResourceCodesByTenantId(tenant.getId())).thenReturn(Set.of(unlicensed.getCode()));
    var preview = catalog.previewSynchronization(false);
    assertEquals(1, preview.added());
    assertEquals(unlicensed.getId(), preview.additions().getFirst().id());
    assertEquals(2, preview.deactivated());
    var result = catalog.synchronize(false);
    assertEquals(1, result.added());
    assertFalse(em.find(TenantResource.class, new TenantResourceKey(tenant.getId(), page.getId())).isEnabled());
    assertFalse(alias.isEnabled());
    assertTrue(catalog.effectivePlatformCodes(tenant.getId(), Set.of(alias.getCode())).isEmpty());
    assertEquals(unlicensed.getId(), em.find(TenantResource.class,
        new TenantResourceKey(tenant.getId(), unlicensed.getId())).getPlatformResourceId());
  }
}
