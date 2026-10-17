package org.simplepoint.common.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.rest.controller.CurrentDirectoryController;
import org.simplepoint.plugin.rbac.core.service.impl.CurrentDirectoryService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real HQL/SQL paging and HTTP metadata; no catalog-sized mocked query results. */
class CurrentDirectoryHttpTest {
  private SessionFactory factory;
  private Session em;
  private CurrentDirectoryService service;
  private MockMvc mvc;
  private final List<Tenant> tenants = new ArrayList<>();

  @BeforeEach
  void setup() {
    factory =
        new MetadataSources(
                new StandardServiceRegistryBuilder()
                    .applySetting(
                        "hibernate.connection.url",
                        "jdbc:h2:mem:current-directory-" + UUID.randomUUID())
                    .applySetting(
                        "hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
                    .applySetting("hibernate.hbm2ddl.halt_on_error", "true")
                    .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                    .build())
            .addAnnotatedClass(Tenant.class)
            .addAnnotatedClass(TenantUserRelevance.class)
            .addAnnotatedClass(Role.class)
            .addAnnotatedClass(UserRoleRelevance.class)
            .buildMetadata()
            .buildSessionFactory();
    em = factory.openSession();
    em.beginTransaction();
    for (int i = 0; i < 123; i++) {
      tenants.add(tenant("Workspace " + String.format("%03d", i), "actor"));
    }
    service = new CurrentDirectoryService(em);
    mvc =
        MockMvcBuilders.standaloneSetup(new CurrentDirectoryController(service))
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    login(false);
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    if (em != null) {
      if (em.getTransaction().isActive()) {
        em.getTransaction().rollback();
      }
      em.close();
    }
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void pagesBeyondFirstHundredAndHydratesOnlyRequestedIdentities() throws Exception {
    mvc.perform(get("/current-directory/tenants").param("page", "6").param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(3))
        .andExpect(jsonPath("$.content[0].name").value("Workspace 120"))
        .andExpect(jsonPath("$.totalElements").value(123));
    assertThat(
            service.selectedTenants(List.of(tenants.get(122).getId(), "foreign", "__platform__")))
        .extracting(CurrentDirectoryService.TenantOption::id)
        .containsExactly(tenants.get(122).getId());
  }

  @Test
  void virtualPlatformPaginationHasNoSkippedOrDuplicatedDatabaseRows() {
    login(true);
    List<String> seen = new ArrayList<>();
    for (int page = 0; page < 7; page++) {
      seen.addAll(
          service.tenants("", PageRequest.of(page, 20)).getContent().stream()
              .map(CurrentDirectoryService.TenantOption::id)
              .toList());
    }
    assertThat(seen).hasSize(124).doesNotHaveDuplicates();
    assertThat(seen.getFirst()).isEqualTo("__platform__");
    assertThat(seen.subList(1, seen.size()))
        .containsExactlyElementsOf(tenants.stream().map(Tenant::getId).toList());
    assertThat(service.tenants("Workspace", PageRequest.of(0, 20)).getTotalElements())
        .isEqualTo(123);
    assertThat(service.tenants("platform", PageRequest.of(0, 20)).getContent())
        .extracting(CurrentDirectoryService.TenantOption::id)
        .containsExactly("__platform__");
    assertThat(service.tenants("", PageRequest.of(0, 1)).getContent()).hasSize(1);
    assertThat(service.tenants("", PageRequest.of(1, 1)).getContent())
        .extracting(CurrentDirectoryService.TenantOption::id)
        .containsExactly(tenants.getFirst().getId());
  }

  @Test
  void literalSearchAndStableOrderDoNotTreatWildcardsAsCatalogRequests() {
    Tenant literal = tenant("Literal %_! value", "actor");
    assertThat(service.tenants("%_!", PageRequest.of(0, 20)).getContent())
        .extracting(CurrentDirectoryService.TenantOption::id)
        .containsExactly(literal.getId());
    assertThat(service.tenants("WORKSPACE 12", PageRequest.of(0, 20)).getTotalElements())
        .isEqualTo(3);
  }

  @Test
  void localizedPersonalAliasUsesBoundedSearchWithoutRenamingStoredIdentities() throws Exception {
    Tenant mine = tenant("long-owner-id personal workspace", "actor");
    mine.setTenantType(TenantType.PERSONAL);
    Tenant other = tenant("someone-else-id personal workspace", "other");
    other.setTenantType(TenantType.PERSONAL);
    for (String alias : List.of("我的空间", "我的", "My Workspace", "my work")) {
      mvc.perform(get("/current-directory/tenants").param("keyword", alias).param("size", "1"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.totalElements").value(1))
          .andExpect(jsonPath("$.content[0].id").value(mine.getId()))
          .andExpect(jsonPath("$.content[0].name").value(mine.getName()));
    }
    assertThat(service.tenants("long-owner-id", PageRequest.of(0, 1)).getContent())
        .extracting(CurrentDirectoryService.TenantOption::id)
        .containsExactly(mine.getId());
    assertThat(service.tenants("我的空间", PageRequest.of(1, 1)).getContent()).isEmpty();
  }

  @Test
  void ownerAndActiveMembershipAreRequiredEvenForAdministrators() {
    final Tenant foreign = tenant("Foreign", "other");
    Tenant member = tenant("Member", "other");
    Tenant inactive = tenant("Inactive", "other");
    membership(member, true);
    membership(inactive, false);
    Tenant removed = tenant("Removed", "actor");
    removed.setDeletedAt(Instant.now());
    login(true);
    assertThat(
            service.selectedTenants(
                List.of(foreign.getId(), member.getId(), inactive.getId(), removed.getId())))
        .extracting(CurrentDirectoryService.TenantOption::id)
        .containsExactly(member.getId());
  }

  @Test
  void tenantPresentationIsIdenticalInSearchAndHydrationWithoutExposingOtherFields()
      throws Exception {
    Tenant tenant = tenants.get(122);
    tenant.setLogo("/common/object-storage/images/workspace-logo");
    tenant.setDescription("Private administrative details");
    for (var request :
        List.of(
            get("/current-directory/tenants").param("keyword", "Workspace 122").param("size", "1"),
            get("/current-directory/tenants/selected").param("values", tenant.getId()))) {
      // Assert the actual serialized projection, not the full JPA entity.
      var response = mvc.perform(request).andExpect(status().isOk()).andReturn();
      var root =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(response.getResponse().getContentAsString());
      var row = root.isArray() ? root.get(0) : root.path("content").get(0);
      assertThat(row.properties())
          .extracting(java.util.Map.Entry::getKey)
          .containsExactlyInAnyOrder("id", "name", "type", "logo");
      assertThat(row.path("type").asText()).isEqualTo("ORGANIZATION");
      assertThat(row.path("logo").asText()).isEqualTo(tenant.getLogo());
    }
  }

  @Test
  void rolesArePagedDeduplicatedAssignedIdsAndNeverForeignAuthorities() throws Exception {
    String tenant = tenants.getFirst().getId();
    List<Role> roles = new ArrayList<>();
    for (int i = 0; i < 123; i++) {
      roles.add(role(tenant, "Role " + String.format("%03d", i), "actor"));
    }
    role(tenant, "Not assigned to me", "other");
    role(tenants.get(1).getId(), "Foreign role", "actor");
    Role removed = role(tenant, "Removed", "actor");
    removed.setDeletedAt(Instant.now());
    assignment(roles.getFirst(), "actor");
    mvc.perform(
            get("/current-directory/roles")
                .param("tenantId", tenant)
                .param("page", "6")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(3))
        .andExpect(jsonPath("$.totalElements").value(123));
    assertThat(
            service.selectedRoles(
                tenant,
                List.of(roles.get(122).getId(), removed.getId(), roles.get(122).getAuthority())))
        .extracting(CurrentDirectoryService.Option::id)
        .containsExactly(roles.get(122).getId());
  }

  @Test
  void roleReadsRejectForeignOrMissingWorkspacesAndUnauthorizedPlatform() {
    String foreign = tenant("Foreign", "other").getId();
    for (String id : List.of(foreign, "", "__platform__", "missing")) {
      assertThatThrownBy(() -> service.roles(id, "", PageRequest.of(0, 20)))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> service.selectedRoles(id, List.of("role")))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void platformContextCannotSelectRolesBelongingToAnOrganization() {
    Role scoped = role(tenants.getFirst().getId(), "Scoped", "actor");
    role(tenants.getFirst().getId(), "Other actor", "other");
    assertThat(service.roles(tenants.getFirst().getId(), "", PageRequest.of(0, 20)).getContent())
        .extracting(CurrentDirectoryService.Option::id)
        .containsExactly(scoped.getId());
    login(true);
    assertThat(service.selectedRoles("__platform__", List.of(scoped.getId()))).isEmpty();
    assertThat(service.roles("__platform__", "", PageRequest.of(0, 20))).isEmpty();
  }

  @Test
  void invalidBudgetsAndAnonymousQueriesFailClosed() {
    for (var page :
        List.of(
            PageRequest.of(0, 51),
            PageRequest.of(Integer.MAX_VALUE, 20),
            PageRequest.of(0, 20, Sort.by("name")))) {
      assertThatThrownBy(() -> service.tenants("", page))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> service.tenants("x".repeat(129), PageRequest.of(0, 20)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.selectedTenants(java.util.Collections.nCopies(51, "id")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.selectedTenants(List.of("")))
        .isInstanceOf(IllegalArgumentException.class);
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> service.tenants("", PageRequest.of(0, 20)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void actualHttpErrorsUseSafeLocalizedClientStatuses() throws Exception {
    for (var request :
        List.of(
            get("/current-directory/tenants").param("size", "51"),
            get("/current-directory/tenants").param("keyword", "private-".repeat(20)),
            get("/current-directory/tenants").param("sort", "name"),
            get("/current-directory/tenants/selected").param("values", "private,".repeat(51)),
            get("/current-directory/roles")
                .param("tenantId", tenants.getFirst().getId())
                .param("size", "51"))) {
      mvc.perform(request)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_DIRECTORY_QUERY"))
          .andExpect(jsonPath("$.messageKey").value("error.badRequest"))
          .andExpect(jsonPath("$.message").doesNotExist());
    }
    for (String id : List.of("private-missing-workspace", "__platform__")) {
      mvc.perform(get("/current-directory/roles").param("tenantId", id))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.messageKey").value("error.forbidden"));
      mvc.perform(
              get("/current-directory/roles/selected")
                  .param("tenantId", id)
                  .param("values", "private-role"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.messageKey").value("error.forbidden"));
    }
  }

  private void login(boolean platform) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "actor",
                "unused",
                platform ? List.of(new SimpleGrantedAuthority("platform.access")) : List.of()));
  }

  private Tenant tenant(String name, String owner) {
    Tenant value = new Tenant();
    value.setName(name);
    value.setOwnerId(owner);
    value.setTenantType(TenantType.ORGANIZATION);
    value.setAuthorizationVersion(0L);
    em.persist(value);
    return value;
  }

  private void membership(Tenant tenant, boolean enabled) {
    TenantUserRelevance value = new TenantUserRelevance();
    value.setTenantId(tenant.getId());
    value.setUserId("actor");
    value.setEnabled(enabled);
    em.persist(value);
  }

  private Role role(String tenant, String name, String user) {
    Role value = new Role();
    value.setTenantId(tenant);
    value.setRoleName(name);
    value.setAuthority("authority." + UUID.randomUUID());
    em.persist(value);
    assignment(value, user);
    return value;
  }

  private void assignment(Role role, String user) {
    UserRoleRelevance value = new UserRoleRelevance();
    value.setTenantId(role.getTenantId());
    value.setRoleId(role.getId());
    value.setUserId(user);
    em.persist(value);
  }
}
