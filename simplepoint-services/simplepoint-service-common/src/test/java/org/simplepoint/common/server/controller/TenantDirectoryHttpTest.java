package org.simplepoint.common.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import org.simplepoint.plugin.rbac.core.rest.controller.TenantDirectoryController;
import org.simplepoint.plugin.rbac.core.service.impl.TenantDirectoryService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.repository.JpaTenantRepository;
import org.springframework.data.jpa.repository.Query;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.User;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Exercises actual SQL and HTTP paging rather than mocking a directory result. */
class TenantDirectoryHttpTest {

  private SessionFactory factory;
  private Session entityManager;
  private TenantDirectoryService service;
  private MockMvc mvc;
  private Tenant tenant;
  private User owner;
  private User member;
  private User foreign;
  private User disabled;
  private AuthorizationContext context;

  @BeforeEach
  void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:tenant-directory-" + UUID.randomUUID())
        .applySetting("hibernate.physical_naming_strategy",
            "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
        .applySetting("hibernate.hbm2ddl.halt_on_error", "true")
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build())
        .addAnnotatedClass(User.class).addAnnotatedClass(Role.class).addAnnotatedClass(Tenant.class)
        .addAnnotatedClass(TenantUserRelevance.class).buildMetadata().buildSessionFactory();
    entityManager = factory.openSession();
    entityManager.beginTransaction();
    tenant = new Tenant();
    tenant.setName("Current tenant");
    tenant.setTenantType(TenantType.ORGANIZATION);
    tenant.setAuthorizationVersion(0L);
    tenant.setOwnerId("pending");
    entityManager.persist(tenant);
    owner = user("Owner", true);
    tenant.setOwnerId(owner.getId());
    member = user("Member", true);
    membership(member, tenant.getId(), true);
    foreign = user("Foreign", true);
    membership(foreign, "another-tenant", true);
    disabled = user("Disabled", false);
    membership(disabled, tenant.getId(), true);
    User inactiveMembership = user("Inactive", true);
    membership(inactiveMembership, tenant.getId(), false);
    User deleted = user("Deleted", true);
    deleted.setDeletedAt(Instant.now());
    membership(deleted, tenant.getId(), true);
    role("Approver", "tenant.approver", tenant.getId());
    role("Foreign approver", "foreign.approver", "another-tenant");
    Role removed = role("Removed", "tenant.removed", tenant.getId());
    removed.setDeletedAt(Instant.now());
    entityManager.flush();
    authorize(member.getId(), Set.of("workflow.instances.start", "workflow.definitions.edit"),
        AuthorizationScopeType.TENANT);
    service = new TenantDirectoryService(entityManager);
    mvc = MockMvcBuilders.standaloneSetup(new TenantDirectoryController(service))
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (entityManager != null) {
      if (entityManager.getTransaction().isActive()) {
        entityManager.getTransaction().rollback();
      }
      entityManager.close();
    }
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void pagesActiveTenantMembersAndIncludesOwnerWithoutMembership() throws Exception {
    mvc.perform(get("/tenant-directory/users").param("page", "0").param("size", "1")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].name").value("Member"))
        .andExpect(jsonPath("$.totalElements").value(2));
    var next = service.users("", PageRequest.of(1, 1));
    assertThat(next.getContent()).extracting(item -> item.value()).containsExactly(owner.getId());
  }

  @Test
  void selectedUsersShareTenantAndActiveAccountBoundary() {
    assertThat(service.selectedUsers(List.of(owner.getId(), member.getId(), foreign.getId(), disabled.getId())))
        .extracting(item -> item.value()).containsExactlyInAnyOrder(owner.getId(), member.getId());
    assertThat(service.users("%", PageRequest.of(0, 20))).isEmpty();
  }

  @Test
  void runtimeMemberValidationUsesTheSameActiveAccountAndMembershipBoundary() throws Exception {
    String jpql = JpaTenantRepository.class.getMethod("resolveActiveUserIds", String.class, java.util.Collection.class)
        .getAnnotation(Query.class).value();
    var ids = entityManager.createQuery(jpql, String.class).setParameter("tenantId", tenant.getId())
        .setParameter("userIds", List.of(owner.getId(), member.getId(), foreign.getId(), disabled.getId()))
        .getResultList();
    assertThat(ids).containsExactlyInAnyOrder(owner.getId(), member.getId());
    String roleJpql = JpaTenantRepository.class.getMethod("resolveActiveRoleAuthorities", String.class, java.util.Collection.class)
        .getAnnotation(Query.class).value();
    var roles = entityManager.createQuery(roleJpql, String.class).setParameter("tenantId", tenant.getId())
        .setParameter("authorities", List.of("tenant.approver", "foreign.approver", "tenant.removed"))
        .getResultList();
    assertThat(roles).containsExactly("tenant.approver");
  }

  @Test
  void roleSelectionUsesAuthoritiesAndNeverLeaksAnotherTenant() throws Exception {
    mvc.perform(get("/tenant-directory/roles").param("keyword", "approver")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].value").value("tenant.approver"));
    assertThat(service.selectedRoles(List.of("tenant.approver", "foreign.approver", "tenant.removed")))
        .extracting(item -> item.value()).containsExactly("tenant.approver");
  }

  @Test
  void deniesForeignActorAndMissingOperationCapability() {
    authorize(foreign.getId(), Set.of("workflow.definitions.edit"), AuthorizationScopeType.TENANT);
    assertThatThrownBy(() -> service.users("", PageRequest.of(0, 20)))
        .isInstanceOf(AccessDeniedException.class);
    authorize(member.getId(), Set.of("workflow.tasks.view"), AuthorizationScopeType.TENANT);
    assertThatThrownBy(() -> service.roles("", PageRequest.of(0, 20)))
        .isInstanceOf(AccessDeniedException.class);
    authorize(member.getId(), Set.of("workflow.instances.start"), AuthorizationScopeType.PERSONAL);
    assertThatThrownBy(() -> service.selectedUsers(List.of(member.getId())))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void rejectsUnboundedPagingSortsAndHydration() {
    assertThatThrownBy(() -> service.users("", PageRequest.of(0, 51)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.roles("", PageRequest.of(0, 20, Sort.by("id"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.selectedUsers(java.util.Collections.nCopies(51, "id")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private User user(final String name, final boolean enabled) {
    User user = new User();
    user.setNickname(name);
    user.setName(name);
    user.setEmail(name.toLowerCase(java.util.Locale.ROOT) + "@directory.test");
    user.setEnabled(enabled);
    entityManager.persist(user);
    return user;
  }

  private void authorize(final String userId, final Set<String> resources, final AuthorizationScopeType scope) {
    context = new AuthorizationContext();
    context.setScopeType(scope);
    context.setUserId(userId);
    context.setIsAdministrator(false);
    context.setResources(resources);
    context.setAttributes(Map.of("X-Tenant-Id", tenant.getId()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private void membership(final User user, final String tenantId, final boolean enabled) {
    TenantUserRelevance relation = new TenantUserRelevance();
    relation.setUserId(user.getId());
    relation.setTenantId(tenantId);
    relation.setEnabled(enabled);
    entityManager.persist(relation);
  }

  private Role role(final String name, final String authority, final String tenantId) {
    Role role = new Role();
    role.setRoleName(name);
    role.setAuthority(authority);
    role.setTenantId(tenantId);
    entityManager.persist(role);
    return role;
  }
}
