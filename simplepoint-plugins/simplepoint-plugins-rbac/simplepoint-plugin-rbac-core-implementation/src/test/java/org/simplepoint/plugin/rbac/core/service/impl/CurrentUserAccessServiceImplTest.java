package org.simplepoint.plugin.rbac.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceType;
import org.simplepoint.security.service.ResourceService;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

class CurrentUserAccessServiceImplTest {
  private SessionFactory factory;
  private Session em;
  private final TenantService tenants = mock(TenantService.class);
  private final UsersService users = mock(UsersService.class);
  private final ResourceService resources = mock(ResourceService.class);
  private CurrentUserAccessServiceImpl service;

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:profile-access-" + UUID.randomUUID())
        .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .build();
    factory = new MetadataSources(registry).addAnnotatedClass(Organization.class)
        .addAnnotatedClass(Resource.class)
        .addAnnotatedClass(TenantUserRelevance.class).buildMetadata().buildSessionFactory();
    em = factory.openSession();
    em.beginTransaction();
    service = new CurrentUserAccessServiceImpl(em, tenants, users, resources);
    context("tenant-a", AuthorizationScopeType.TENANT);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (em != null) {
      if (em.getTransaction().isActive()) em.getTransaction().rollback();
      em.close();
    }
    if (factory != null) factory.close();
  }

  private void context(String tenant, AuthorizationScopeType scope) {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setUserId("user-a");
    ctx.setScopeType(scope);
    ctx.setResources(Set.of("users.view", "public.view"));
    ctx.setAttributes(tenant == null ? Map.of() : Map.of("X-Tenant-Id", tenant));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, ctx);
  }

  @Test
  void separatesEffectiveAccessFromOwnedRoleGrants() {
    Resource named = new Resource();
    named.setCode("users.view"); named.setName("Users"); named.setType(ResourceType.PAGE);
    em.persist(named); em.flush();
    when(resources.findAllByCodes(anyCollection())).thenReturn(List.of(named));
    when(tenants.getCurrentUserRoles("tenant-a")).thenReturn(List.of(
        new RoleGrantedAuthority("role-a", "Reader", "READER")));
    when(users.loadResourcesInRoleIds(List.of("role-a")))
        .thenReturn(Set.of("users.view", "disabled.edit"));

    var effective = service.resources(null, "", "ALL", PageRequest.of(0, 24));
    assertThat(effective.getContent()).extracting(row -> row.code())
        .containsExactlyInAnyOrder("users.view", "public.view");
    assertThat(effective.getContent()).allMatch(row -> row.effective());
    assertThat(effective.getContent()).filteredOn(row -> row.code().equals("public.view"))
        .allMatch(row -> !row.available());

    var role = service.resources("role-a", "", "ALL", PageRequest.of(0, 24));
    assertThat(role.getContent()).extracting(row -> row.code())
        .containsExactlyInAnyOrder("users.view", "disabled.edit");
    assertThat(role.getContent()).filteredOn(row -> row.code().equals("disabled.edit"))
        .allMatch(row -> !row.effective() && !row.available());
    assertThat(service.resources("role-a", "users", "PAGE", PageRequest.of(0, 24))
        .getContent()).extracting(row -> row.code()).containsExactly("users.view");
    assertThatThrownBy(() -> service.resources("foreign-role", "", "ALL", PageRequest.of(0, 24)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.resources(null, "", "ALL", PageRequest.of(0, 61)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void pageListAndDetailUseResourceHierarchyWithoutShowingOtherPages() {
    Resource directory = new Resource();
    directory.setCode("admin"); directory.setName("管理"); directory.setType(ResourceType.GROUP);
    em.persist(directory);
    Resource page = new Resource();
    page.setCode("users.view"); page.setName("用户管理"); page.setType(ResourceType.PAGE);
    page.setParentId(directory.getId()); em.persist(page);
    Resource action = new Resource();
    action.setCode("users.edit"); action.setName("编辑用户"); action.setType(ResourceType.ACTION);
    action.setParentId(page.getId()); em.persist(action);
    Resource sibling = new Resource();
    sibling.setCode("other.view"); sibling.setName("其他页面"); sibling.setType(ResourceType.PAGE);
    sibling.setParentId(directory.getId()); em.persist(sibling);
    em.flush();
    Map<String, Resource> visible = Map.of(directory.getCode(), directory, page.getCode(), page,
        action.getCode(), action, sibling.getCode(), sibling);
    when(resources.findAllByCodes(anyCollection())).thenAnswer(invocation ->
        ((java.util.Collection<String>) invocation.getArgument(0)).stream()
            .map(visible::get).filter(java.util.Objects::nonNull).toList());
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setUserId("user-a"); ctx.setScopeType(AuthorizationScopeType.TENANT);
    ctx.setResources(Set.of("users.view", "users.edit", "other.view"));
    ctx.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, ctx);

    var list = service.resources(null, "", "PAGE", PageRequest.of(0, 1));
    assertThat(list.getTotalElements()).isEqualTo(2);
    assertThat(list.getContent()).extracting(row -> row.code()).containsExactly("other.view");
    assertThat(list.getContent().getFirst().directory()).containsExactly("管理");
    var detail = service.pageDetail(null, "users.view");
    assertThat(detail.page().directory()).containsExactly("管理");
    assertThat(detail.operations()).extracting(row -> row.code()).containsExactly("users.edit");
    assertThat(detail.dataPermissionSupported()).isFalse();
    assertThat(detail.fieldPermissionSupported()).isFalse();
    assertThatThrownBy(() -> service.pageDetail(null, "users.edit"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void customPageAliasHidesBackingPageAndShowsItsOperations() {
    TenantResourceCatalog catalog = mock(TenantResourceCatalog.class);
    service.setTenantResourceCatalog(catalog);
    TenantResource platformPage = new TenantResource();
    platformPage.setTenantId("tenant-a"); platformPage.setId("platform-page");
    platformPage.setPlatformResourceId("platform-page");
    platformPage.setCode("users.view"); platformPage.setName("Platform users");
    platformPage.setType(ResourceType.PAGE); platformPage.setEnabled(true);
    TenantResource action = new TenantResource();
    action.setTenantId("tenant-a"); action.setId("platform-action");
    action.setPlatformResourceId("platform-action"); action.setParentId(platformPage.getId());
    action.setCode("users.edit"); action.setName("Edit users");
    action.setType(ResourceType.ACTION); action.setEnabled(true);
    TenantResource alias = new TenantResource();
    alias.setTenantId("tenant-a"); alias.setId("alias");
    alias.setTargetResourceId(platformPage.getId());
    alias.setCode("tenant.tenant-a.alias"); alias.setName("My users");
    alias.setType(ResourceType.PAGE); alias.setEnabled(true);
    when(catalog.mode("tenant-a")).thenReturn(TenantResourceMode.CUSTOM);
    when(catalog.activeResources("tenant-a")).thenReturn(List.of(platformPage, action, alias));
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setUserId("user-a"); ctx.setScopeType(AuthorizationScopeType.TENANT);
    ctx.setResources(Set.of(platformPage.getCode(), alias.getCode(), action.getCode()));
    ctx.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, ctx);

    var pages = service.resources(null, "", "PAGE", PageRequest.of(0, 10));
    assertThat(pages.getContent()).extracting(row -> row.code()).containsExactly(alias.getCode());
    assertThat(service.pageDetail(null, alias.getCode()).operations())
        .extracting(row -> row.code()).containsExactly(action.getCode());
  }

  @Test
  void resolvesOnlyActiveMembershipAndSameTenantOrganizationPath() {
    Organization root = new Organization();
    root.setTenantId("tenant-a"); root.setCode("hq"); root.setName("HQ"); root.setEnabled(true);
    em.persist(root);
    Organization child = new Organization();
    child.setTenantId("tenant-a"); child.setCode("team"); child.setName("Team");
    child.setParentId(root.getId()); child.setEnabled(true); em.persist(child);
    Organization foreign = new Organization();
    foreign.setTenantId("tenant-b"); foreign.setCode("foreign"); foreign.setName("Foreign");
    foreign.setEnabled(true); em.persist(foreign);
    TenantUserRelevance member = new TenantUserRelevance();
    member.setTenantId("tenant-a"); member.setUserId("user-a"); member.setOrgId(child.getId());
    em.persist(member);
    TenantUserRelevance other = new TenantUserRelevance();
    other.setTenantId("tenant-b"); other.setUserId("user-a"); other.setOrgId(foreign.getId());
    em.persist(other); em.flush();

    assertThat(service.organization().path()).containsExactly("HQ", "Team");
    assertThat(service.organization().id()).isEqualTo(child.getId());
    context("tenant-b", AuthorizationScopeType.TENANT);
    assertThat(service.organization().path()).containsExactly("Foreign");
    context(null, AuthorizationScopeType.PLATFORM);
    assertThat(service.organization().path()).isEmpty();
  }
}
