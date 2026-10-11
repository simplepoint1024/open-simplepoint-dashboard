package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.*;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.plugin.auditing.logging.api.service.ResourceGrantLogRemoteService;
import org.simplepoint.plugin.rbac.core.api.repository.*;
import org.simplepoint.plugin.rbac.core.base.repository.JpaUserRoleRelevanceRepository;
import org.simplepoint.plugin.rbac.core.rest.controller.UsersController;
import org.simplepoint.plugin.rbac.core.service.impl.*;
import org.simplepoint.plugin.rbac.tenant.api.entity.*;
import org.simplepoint.plugin.rbac.tenant.api.repository.*;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Production converter, real assignment SQL and mutations, isolated from business data. */
class UserRoleAssignmentHttpTest {
  SessionFactory factory;
  Session em;
  MockMvc mvc;
  Tenant tenant;
  User owner;
  User member;
  User outsider;
  List<Role> roles = new ArrayList<>();
  AuthorizationContext context;

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:role-assignment-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.hbm2ddl.halt_on_error", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
        .build();
    factory = new MetadataSources(registry).addAnnotatedClass(User.class).addAnnotatedClass(Role.class)
        .addAnnotatedClass(Tenant.class).addAnnotatedClass(TenantUserRelevance.class).addAnnotatedClass(UserRoleRelevance.class)
        .buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction();
    owner = user("Owner"); member = user("Member"); outsider = user("Outside");
    tenant = new Tenant(); tenant.setName("current"); tenant.setOwnerId(owner.getId());
    tenant.setTenantType(TenantType.ORGANIZATION); tenant.setAuthorizationVersion(0L); em.persist(tenant);
    var membership = new TenantUserRelevance(); membership.setTenantId(tenant.getId()); membership.setUserId(member.getId());
    membership.setEnabled(true); em.persist(membership);
    for (int i = 0; i < 244; i++) {
      var role = new Role(); role.setRoleName("Candidate" + String.format("%03d", i));
      role.setAuthority("ROLE_" + i); role.setDescription("Description " + i);
      role.setTenantId(i == 243 ? "outside" : tenant.getId());
      if (i == 242) role.setDeletedAt(Instant.now());
      em.persist(role); roles.add(role);
      if (i < 120 || i == 240 || i == 241 || i == 242) {
        assign(i, i == 241 ? "outside" : tenant.getId(), member.getId(), i == 240);
      }
    }
    em.flush();
    var tenants = mock(TenantRepository.class);
    when(tenants.findById(anyString())).thenAnswer(call -> Optional.ofNullable(em.find(Tenant.class, call.getArgument(0))));
    var roleRepository = mock(RoleRepository.class);
    when(roleRepository.findAllByIds(any())).thenAnswer(call -> ((Collection<String>) call.getArgument(0)).stream()
        .map(id -> em.find(Role.class, id)).filter(Objects::nonNull).toList());
    var details = mock(DetailsProviderService.class);
    var config = new JsonSchemaAutoConfiguration(new ObjectMapper());
    var generator = config.schemaGenerator(Set.of(config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()));
    when(details.getDialect(JsonSchemaGenerator.class)).thenReturn(config.defaultJsonSchemaGenerator(generator));
    when(details.getDialect(JsonSchemaDetailsService.class)).thenReturn(mock(JsonSchemaDetailsService.class));
    var queryProxy = new org.springframework.aop.framework.ProxyFactory(new UserRoleCandidateQuery(em));
    queryProxy.setProxyTargetClass(true);
    queryProxy.addAdvice(new org.springframework.dao.support.PersistenceExceptionTranslationInterceptor(
        new org.springframework.orm.jpa.vendor.HibernateJpaDialect()));
    var service = new UsersServiceImpl(null, mock(UserRepository.class), details,
        new JpaRepositoryFactory(em).getRepository(JpaUserRoleRelevanceRepository.class), tenants,
        mock(TenantUserRelevanceRepository.class), roleRepository, mock(ResourceAuthorizationVersionService.class),
        mock(ResourceGrantLogRemoteService.class), mock(ObjectProvider.class), (UserRoleCandidateQuery) queryProxy.getProxy(),
        new org.simplepoint.plugin.rbac.core.service.impl.UserPickerQuery(em));
    mvc = MockMvcBuilders.standaloneSetup(new UsersController(service)).setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter())
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
    authorize(owner.getId(), AuthorizationScopeType.TENANT, AuthorizationActorRole.TENANT_OWNER);
  }

  void authorize(String userId, AuthorizationScopeType scope, AuthorizationActorRole actor) {
    context = new AuthorizationContext(); context.setUserId(userId); context.setIsAdministrator(false);
    context.setScopeType(scope); context.setActorRole(actor);
    context.setAttributes(Map.of("X-Tenant-Id", tenant.getId()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  User user(String name) { var user = new User(); user.setName(name); em.persist(user); return user; }

  void assign(int index, String tenantId, String userId, boolean deleted) {
    var relation = new UserRoleRelevance(); relation.setTenantId(tenantId); relation.setUserId(userId);
    relation.setRoleId(roles.get(index).getId()); if (deleted) relation.setDeletedAt(Instant.now()); em.persist(relation);
  }

  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder items(boolean selected) {
    return get("/users/role-candidates").param("userId", member.getId()).param("selected", String.valueOf(selected))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();}
    if (factory != null) factory.close();
  }

  @Test
  void selectedAndAvailablePagesAreCountedInSqlBeyondOneHundred() throws Exception {
    mvc.perform(items(true).param("page", "5").param("size", "20")).andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(120)).andExpect(jsonPath("$.content[0].name").value("Candidate100"));
    mvc.perform(items(false)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(122))
        .andExpect(jsonPath("$.content[0].name").value("Candidate120"));
    mvc.perform(items(false).param("sort", "name,desc", "authority,asc")).andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Candidate241"));
  }

  @Test
  void advancedHeadersAndKeywordIntersectWithoutEscapingTheAssignmentSide() throws Exception {
    String ast = """
        {"version":1,"where":{"op":"and","children":[
          {"op":"or","children":[{"field":"name","op":"equals","value":"Candidate119"},{"field":"name","op":"equals","value":"Candidate239"}]},
          {"op":"not","children":[{"field":"authority","op":"equals","value":"ROLE_NONE"}]}]}}
        """;
    mvc.perform(items(false).param("$query", ast).param("description", "like:239").param("keyword", "Candidate239"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(roles.get(239).getId()));
    mvc.perform(items(true).param("$query", ast)).andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Candidate119")).andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void deletedAssignmentsAndForeignAssignmentsDoNotHideAvailableRoles() throws Exception {
    for (int i : List.of(240, 241)) mvc.perform(items(false).param("id", "equals:" + roles.get(i).getId()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    for (int i : List.of(242, 243)) for (boolean side : List.of(true, false))
      mvc.perform(items(side).param("id", "equals:" + roles.get(i).getId())).andExpect(status().isOk())
          .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void schemaAndRowsRejectOutsidersDeletedMembersAndPersonalContexts() {
    for (String id : List.of(outsider.getId(), "missing")) assertDenied(id);
    member.setDeletedAt(Instant.now()); em.flush(); assertDenied(member.getId());
    member.setDeletedAt(null); em.flush();
    authorize(owner.getId(), AuthorizationScopeType.PERSONAL, AuthorizationActorRole.PERSONAL_OWNER);
    assertDenied(member.getId());
  }

  void assertDenied(String id) {
    for (String path : List.of("/users/role-candidates", "/users/role-candidates/schema")) {
      var error = assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(get(path).param("userId", id)
          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)));
      assertInstanceOf(AccessDeniedException.class, error.getCause());
    }
  }

  @Test
  void ownerWithoutMembershipCanBeQueriedButNonManagerCannotReadTheCatalog() throws Exception {
    mvc.perform(get("/users/role-candidates").param("userId", owner.getId()).param("selected", "true")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    authorize(outsider.getId(), AuthorizationScopeType.TENANT, AuthorizationActorRole.TENANT_MEMBER);
    assertDenied(member.getId());
  }

  @Test
  void invalidAndUnauthorizedFieldsAndUnboundedPagesAreRejected() throws Exception {
    for (var param : Map.of("tenantId", "outside", "deletedAt", "is:null", "size", "100000").entrySet())
      mvc.perform(items(false).param(param.getKey(), param.getValue())).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    context.setFieldPermissions(Map.of(Role.class.getName() + "#description", "HIDDEN",
        Role.class.getName() + "#authority", "MASKED"));
    mvc.perform(items(false)).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].description").doesNotExist())
        .andExpect(jsonPath("$.content[0].authority").value("***"));
    mvc.perform(items(false).param("description", "like:239")).andExpect(status().isBadRequest());
    mvc.perform(items(false).param("sort", "authority,asc")).andExpect(status().isBadRequest());
    mvc.perform(get("/users/role-candidates/schema").param("userId", member.getId())
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.schema.properties.description").doesNotExist())
        .andExpect(jsonPath("$.schema['x-query'].fields.authority").doesNotExist());
    authorize(owner.getId(), AuthorizationScopeType.TENANT, AuthorizationActorRole.TENANT_OWNER);
    context.setFieldPermissions(Map.of(Role.class.getName() + "#id", "MASKED"));
    var error = assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(items(false)));
    assertInstanceOf(AccessDeniedException.class, error.getCause());
  }

  @Test
  void assignmentRestoresDeletedRowsAndDuplicateSubmissionDoesNotDuplicateGrants() throws Exception {
    String id = roles.get(240).getId();
    for (int i = 0; i < 2; i++) mutate("authorize", member.getId(), List.of(id)).andExpect(status().isOk());
    em.flush();
    assertEquals(1L, em.createQuery("select count(r) from UserRoleRelevance r where r.tenantId=:t and r.userId=:u and r.roleId=:r and r.deletedAt is null", Long.class)
        .setParameter("t", tenant.getId()).setParameter("u", member.getId()).setParameter("r", id).getSingleResult());
    mvc.perform(items(true).param("id", "equals:" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    assign(240, "outside", member.getId(), false); em.flush();
    mutate("unauthorized", member.getId(), List.of(id)).andExpect(status().isOk());
    mvc.perform(items(false).param("id", "equals:" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    assertEquals(1L, em.createQuery("select count(r) from UserRoleRelevance r where r.tenantId='outside' and r.roleId=:r", Long.class)
        .setParameter("r", id).getSingleResult());
  }

  @Test
  void mutationsRejectForeignDeletedRolesOversizedBatchesAndForeignMembers() throws Exception {
    for (String action : List.of("authorize", "unauthorized")) {
      for (int i : List.of(242, 243)) mutate(action, member.getId(), List.of(roles.get(i).getId())).andExpect(status().isBadRequest());
      mutate(action, member.getId(), roles.subList(120, 221).stream().map(Role::getId).toList()).andExpect(status().isBadRequest());
      var error = assertThrows(jakarta.servlet.ServletException.class, () -> mutate(action, outsider.getId(), List.of(roles.getFirst().getId())));
      assertInstanceOf(AccessDeniedException.class, error.getCause());
    }
  }

  org.springframework.test.web.servlet.ResultActions mutate(String action, String userId, List<String> ids) throws Exception {
    return mvc.perform(post("/users/" + action).contentType("application/json")
        .content(new ObjectMapper().writeValueAsString(Map.of("userId", userId, "roleIds", ids)))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context));
  }

  @Test
  void actualSchemaIsExportedForBrowserContractTests() throws Exception {
    var response = mvc.perform(get("/users/role-candidates/schema").param("userId", member.getId())
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk()).andReturn();
    var mapper = new ObjectMapper();
    try (var fixture = getClass().getResourceAsStream("/query-contracts/role-assignment-schema.fixture.json")) {
      assertNotNull(fixture);
      assertEquals(mapper.readTree(fixture), mapper.readTree(response.getResponse().getContentAsString(
          java.nio.charset.StandardCharsets.UTF_8)), "Browser query contract must match the real HTTP response");
    }
    var output = java.nio.file.Path.of("build/generated-test-resources/role-assignment-schema.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(output, response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }
}
