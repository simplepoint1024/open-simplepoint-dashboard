package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceAncestorRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.resource.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.PackageApplicationRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantPackageRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceAncestor;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.beans.factory.ObjectProvider;

class ManagedTenantResourceRoutesTest {

  private SessionFactory factory;
  private Session session;

  @BeforeEach
  void setUp() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:managed-routes-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .build())
        .addAnnotatedClass(Resource.class)
        .addAnnotatedClass(ResourceAncestor.class)
        .addAnnotatedClass(TenantPackageRelevance.class)
        .addAnnotatedClass(PackageApplicationRelevance.class)
        .addAnnotatedClass(ApplicationResourceRelevance.class)
        .buildMetadata()
        .buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session.getTransaction().isActive()) session.getTransaction().rollback();
    session.close();
    factory.close();
  }

  @Test
  void platformModeMergesHeldManagedPageUnderItsPlatformAncestor() throws Exception {
    Resource module = new Resource();
    module.setId("app-module");
    module.setCode("app.platform.view");
    module.setName("Applications");
    module.setType(ResourceType.MODULE);
    module.setPath("/app");
    module.setRouteKind("submenu");
    module.setGrantable(false);
    module.setPublicAccess(false);
    module.setDisabled(false);
    module.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
    session.persist(module);
    session.flush();

    TenantResource page = new TenantResource();
    page.setTenantId("tenant-a");
    page.setId("managed-page");
    page.setManagedOwner("app");
    page.setManagedKey("page:one");
    page.setParentId(module.getId());
    page.setCode("app.runtime.page.one");
    page.setName("Inspection form");
    page.setType(ResourceType.PAGE);
    page.setPath("/app/runtime/application-1/page-1");
    page.setComponent("app/FormRuntime");
    page.setRouteKind("item");
    page.setEnabled(true);
    page.setGrantable(true);

    TenantResourceCatalog catalog = mock(TenantResourceCatalog.class);
    when(catalog.mode("tenant-a")).thenReturn(TenantResourceMode.PLATFORM);
    when(catalog.managedGrantableCodes(eq("tenant-a"), anyCollection()))
        .thenReturn(Set.of(page.getCode()));
    when(catalog.managedNavigationResources(eq("tenant-a"), anyCollection()))
        .thenReturn(List.of(page));
    ResourceServiceImpl service = service();
    service.setTenantResourceCatalog(catalog);
    Field entityManager = ResourceServiceImpl.class.getDeclaredField("permissionEntityManager");
    entityManager.setAccessible(true);
    entityManager.set(service, session);
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("user");
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    context.setResources(Set.of(page.getCode()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);

    var result = service.routes();

    assertThat(result.routes()).hasSize(1);
    var root = result.routes().iterator().next();
    assertThat(root.getCode()).isEqualTo(module.getCode());
    assertThat(root.getChildren()).singleElement().satisfies(child -> {
      assertThat(child.getCode()).isEqualTo(page.getCode());
      assertThat(child.getPath()).isEqualTo(page.getPath());
      assertThat(child.getComponent()).isEqualTo(page.getComponent());
    });
  }

  private static ResourceServiceImpl service() {
    return new ResourceServiceImpl(
        mock(ResourceRepository.class),
        mock(DetailsProviderService.class),
        mock(ResourceAncestorRepository.class),
        mock(MicroAppService.class),
        emptyProvider(),
        emptyProvider(),
        provider(mock(RoleResourceGrantRepository.class)),
        provider(mock(ResourceAuthorizationVersionService.class)),
        emptyProvider(),
        emptyProvider()
    );
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(final T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> emptyProvider() {
    return mock(ObjectProvider.class);
  }
}
