package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
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
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.*;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.tenant.api.entity.*;
import org.simplepoint.plugin.rbac.tenant.api.repository.*;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.repository.JpaTenantUserRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.rest.controller.TenantController;
import org.simplepoint.plugin.rbac.tenant.service.impl.TenantServiceImpl;
import org.simplepoint.plugin.rbac.tenant.service.impl.TenantUserCandidateQuery;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.entity.UserRoleRelevance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Assignment HTTP contract and real SQL repository, with an isolated database and no business writes. */
class TenantUserAssignmentHttpTest {
  SessionFactory factory;
  Session em;
  MockMvc mvc;
  Tenant tenant;
  List<User> users;
  AuthorizationContext context;
  JpaTenantUserRelevanceRepository memberships;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:user-assignment-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.hbm2ddl.halt_on_error", "true")
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
        .build()).addAnnotatedClass(User.class).addAnnotatedClass(Tenant.class)
        .addAnnotatedClass(TenantUserRelevance.class).addAnnotatedClass(UserRoleRelevance.class)
        .buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction();
    users = new ArrayList<>();
    for (int i = 0; i < 243; i++) {
      var user = new User(); user.setNickname("Candidate" + String.format("%03d", i));
      user.setName("Legal " + i); user.setEmail("candidate" + i + "@example.test"); user.setPhoneNumber("138" + String.format("%08d", i));
      if (i == 242) user.setDeletedAt(Instant.now());
      em.persist(user); users.add(user);
    }
    tenant = new Tenant(); tenant.setName("current"); tenant.setTenantType(TenantType.ORGANIZATION);
    tenant.setAuthorizationVersion(0L); tenant.setOwnerId(users.getFirst().getId()); em.persist(tenant);
    for (int i = 1; i < 120; i++) member(i, tenant.getId(), false);
    member(240, tenant.getId(), true); member(241, "outside", false); member(242, tenant.getId(), false);
    em.flush();
    memberships = new JpaRepositoryFactory(em).getRepository(JpaTenantUserRelevanceRepository.class);
    var tenants = mock(TenantRepository.class);
    when(tenants.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(em.find(Tenant.class, invocation.getArgument(0))));
    var details = mock(DetailsProviderService.class);
    var config = new JsonSchemaAutoConfiguration(new ObjectMapper());
    var generator = config.schemaGenerator(Set.of(config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()));
    when(details.getDialect(JsonSchemaGenerator.class)).thenReturn(config.defaultJsonSchemaGenerator(generator));
    when(details.getDialect(JsonSchemaDetailsService.class)).thenReturn(mock(JsonSchemaDetailsService.class));
    var queryProxy = new org.springframework.aop.framework.ProxyFactory(new TenantUserCandidateQuery(em));
    queryProxy.setProxyTargetClass(true);
    queryProxy.addAdvice(new org.springframework.dao.support.PersistenceExceptionTranslationInterceptor(
        new org.springframework.orm.jpa.vendor.HibernateJpaDialect()));
    var service = new TenantServiceImpl(tenants, details, mock(TenantPackageRelevanceRepository.class), memberships,
        mock(ResourceAuthorizationVersionService.class), mock(UsersService.class), mock(ObjectProvider.class),
        (TenantUserCandidateQuery) queryProxy.getProxy());
    mvc = MockMvcBuilders.standaloneSetup(new TenantController(service)).setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter())
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
    authorize(true, tenant.getId());
  }

  void member(int index, String tenantId, boolean deleted) {
    var relation = new TenantUserRelevance(); relation.setTenantId(tenantId); relation.setUserId(users.get(index).getId());
    if (deleted) relation.setDeletedAt(Instant.now()); em.persist(relation);
  }

  void authorize(boolean admin, String tenantId) {
    context = new AuthorizationContext(); context.setIsAdministrator(admin); context.setUserId(users.getFirst().getId());
    context.setScopeType(admin ? AuthorizationScopeType.PLATFORM : AuthorizationScopeType.TENANT);
    context.setActorRole(admin ? AuthorizationActorRole.PLATFORM_ADMIN : AuthorizationActorRole.TENANT_ADMIN);
    context.setAttributes(Map.of("X-Tenant-Id", tenantId));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(context.getUserId(), "unused",
        admin ? List.of(new SimpleGrantedAuthority("ROLE_Administrator")) : List.of(new SimpleGrantedAuthority("ROLE_TenantAdmin"))));
  }

  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder items(boolean selected) {
    return get("/platform/tenants/users/items").param("tenantId", tenant.getId()).param("selected", String.valueOf(selected))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY); SecurityContextHolder.clearContext();
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();}
    if (factory != null) factory.close();
  }

  @Test
  void bothSidesAreSqlPagedBeyondOneHundredAndOwnerIsImplicitlyAssigned() throws Exception {
    mvc.perform(items(true).param("sort", "name,asc").param("page", "5").param("size", "20"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(120))
        .andExpect(jsonPath("$.content[0].name").value("Candidate100"));
    mvc.perform(items(false).param("sort", "name,asc"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(122))
        .andExpect(jsonPath("$.content[0].name").value("Candidate120"));
    mvc.perform(items(true).param("id", "equals:" + users.getFirst().getId()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(items(false).param("name", "like:Candidate000"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void advancedConditionsHeadersAndLegacyKeywordIntersectTheSelectedSide() throws Exception {
    var ast = """
        {"version":1,"where":{"op":"and","children":[
          {"op":"or","children":[{"field":"name","op":"equals","value":"Candidate119"},{"field":"name","op":"equals","value":"Candidate239"}]},
          {"op":"not","children":[{"field":"email","op":"equals","value":"nobody@example.test"}]}]}}
        """;
    mvc.perform(items(false).param("$query", ast).param("email", "like:example.test").param("keyword", "Candidate239")
            .param("sort", "name,desc", "email,asc"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("Candidate239"));
    mvc.perform(items(true).param("$query", ast)).andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].name").value("Candidate119"));
  }

  @Test
  void deletedMembersAndOtherTenantAssignmentsDoNotHideAvailableUsers() throws Exception {
    for (int i : List.of(240, 241)) mvc.perform(items(false).param("id", "equals:" + users.get(i).getId()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    for (boolean side : List.of(true, false)) mvc.perform(items(side).param("id", "equals:" + users.get(242).getId()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void assignmentRestoresDeletedRelationAndRemovalCannotRemoveOwner() throws Exception {
    String id = users.get(240).getId();
    mvc.perform(post("/platform/tenants/users/authorize").contentType("application/json")
            .content(new ObjectMapper().writeValueAsString(Map.of("tenantId", tenant.getId(), "userIds", List.of(id))))
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk());
    em.flush();
    assertEquals(1, memberships.findAllByTenantIdAndUserIdIn(tenant.getId(), List.of(id)).size());
    assertNull(memberships.findAllByTenantIdAndUserIdIn(tenant.getId(), List.of(id)).getFirst().getDeletedAt());
    mvc.perform(items(true).param("id", "equals:" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(post("/platform/tenants/users/unauthorized").contentType("application/json")
            .content(new ObjectMapper().writeValueAsString(Map.of("tenantId", tenant.getId(), "userIds", List.of(users.getFirst().getId()))))
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isBadRequest());
  }

  @Test
  void assigningAndRemovingFreshUserOnlyChangesTheTargetTenantAndItsRoles() throws Exception {
    String id = users.get(239).getId();
    String body = new ObjectMapper().writeValueAsString(Map.of("tenantId", tenant.getId(), "userIds", List.of(id)));
    mvc.perform(post("/platform/tenants/users/authorize").contentType("application/json").content(body)
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk());
    for (String scope : List.of(tenant.getId(), "outside")) {
      var role = new UserRoleRelevance(); role.setTenantId(scope); role.setUserId(id); role.setRoleId("assigned-role"); em.persist(role);
    }
    em.flush();
    mvc.perform(items(true).param("id", "equals:" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(post("/platform/tenants/users/unauthorized").contentType("application/json").content(body)
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk());
    mvc.perform(items(false).param("id", "equals:" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    assertEquals(List.of("outside"), em.createQuery("select r.tenantId from UserRoleRelevance r where r.userId = :user", String.class)
        .setParameter("user", id).getResultList());
    assertNotNull(em.find(User.class, id), "Removing membership must never delete the global account");
  }

  @Test
  void fieldPermissionsApplyToSchemaQueryAndExplicitJsonProjection() throws Exception {
    context.setFieldPermissions(Map.of(User.class.getName() + "#email", "MASKED", User.class.getName() + "#phoneNumber", "HIDDEN"));
    mvc.perform(get("/platform/tenants/users/items/schema").param("tenantId", tenant.getId())
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk())
        .andExpect(jsonPath("$.schema['x-query'].fields.name").doesNotExist())
        .andExpect(jsonPath("$.schema.properties.phoneNumber").doesNotExist());
    mvc.perform(items(true)).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].email").value("***"))
        .andExpect(jsonPath("$.content[0].phoneNumber").doesNotExist()).andExpect(jsonPath("$.content[0].name").doesNotExist());
    for (String field : List.of("name", "email", "phoneNumber", "password", "deletedAt")) {
      mvc.perform(items(true).param(field, "is:null")).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }
    mvc.perform(items(false).param("size", "100000")).andExpect(status().isBadRequest());
  }

  @Test
  void tenantAdminCannotQuerySchemaOrRowsForAnotherContext() {
    authorize(false, "another-tenant");
    for (var request : List.of(items(true), get("/platform/tenants/users/items/schema").param("tenantId", tenant.getId())
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))) {
      var error = assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(request));
      assertInstanceOf(AccessDeniedException.class, error.getCause());
    }
  }

  @Test
  void protectedIdentifiersAndOversizedMutationBatchesAreRejected() throws Exception {
    for (String endpoint : List.of("authorize", "unauthorized")) {
      mvc.perform(post("/platform/tenants/users/" + endpoint).contentType("application/json")
              .content(new ObjectMapper().writeValueAsString(Map.of("tenantId", tenant.getId(), "userIds",
                  users.subList(120, 221).stream().map(User::getId).toList())))
              .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isBadRequest());
    }
    context.setFieldPermissions(Map.of(User.class.getName() + "#id", "MASKED"));
    var error = assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(items(false)));
    assertInstanceOf(AccessDeniedException.class, error.getCause());
  }

  @Test
  void actualSchemaIsExportedForBrowserContractTests() throws Exception {
    var response = mvc.perform(get("/platform/tenants/users/items/schema").param("tenantId", tenant.getId())
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk())
        .andExpect(jsonPath("$.schema['x-query'].fields.id.javaType").value("String")).andReturn();
    try (var fixture = getClass().getResourceAsStream("/query-contracts/user-assignment-schema.fixture.json")) {
      var mapper = new ObjectMapper();
      assertEquals(mapper.readTree(fixture), mapper.readTree(response.getResponse().getContentAsString(
          java.nio.charset.StandardCharsets.UTF_8)), "Browser query contract must match the real HTTP response");
    }
    var output = java.nio.file.Path.of("build/generated-test-resources/user-assignment-schema.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(output, response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }
}
