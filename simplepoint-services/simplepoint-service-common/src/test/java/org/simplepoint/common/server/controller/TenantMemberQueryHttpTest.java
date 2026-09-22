package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.AuthorizationActorRole;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourceDataScope;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.plugin.rbac.core.rest.controller.TenantMemberController;
import org.simplepoint.plugin.rbac.core.service.impl.TenantMemberServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantUserRelevance;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.User;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real projection SQL, count, field policy and HTTP schema contract, with no business database. */
class TenantMemberQueryHttpTest {
  SessionFactory factory;
  Session em;
  MockMvc mvc;
  AuthorizationContext context;
  Tenant tenant;
  Organization organization;
  User owner;

  @BeforeEach
  void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:member-query-" + UUID.randomUUID())
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
        .applySetting("hibernate.hbm2ddl.halt_on_error", "true")
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build())
        .addAnnotatedClass(User.class).addAnnotatedClass(Tenant.class)
        .addAnnotatedClass(TenantUserRelevance.class).addAnnotatedClass(Organization.class)
        .buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction();
    tenant = new Tenant(); tenant.setName("current"); tenant.setTenantType(TenantType.ORGANIZATION);
    tenant.setAuthorizationVersion(0L); tenant.setOwnerId("pending-owner"); em.persist(tenant);
    organization = new Organization(); organization.setCode("engineering"); organization.setName("Engineering");
    organization.setTenantId(tenant.getId()); organization.setEnabled(true); em.persist(organization);
    var foreign = new Organization(); foreign.setCode("foreign"); foreign.setName("Forbidden");
    foreign.setTenantId("outside"); foreign.setEnabled(true); em.persist(foreign);
    owner = member("Alpha", tenant.getId(), organization.getId(), true, false, false);
    member("Beta", tenant.getId(), organization.getId(), false, false, false);
    member("Gamma", tenant.getId(), foreign.getId(), true, false, false);
    member("Foreign", "outside", foreign.getId(), true, false, false);
    member("DeletedAccount", tenant.getId(), organization.getId(), true, true, false);
    member("DeletedMember", tenant.getId(), organization.getId(), true, false, true);
    tenant.setOwnerId(owner.getId()); em.flush();
    context = context();
    var details = mock(DetailsProviderService.class);
    var config = new JsonSchemaAutoConfiguration(new ObjectMapper());
    var generator = config.schemaGenerator(Set.of(config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()));
    when(details.getDialect(JsonSchemaGenerator.class)).thenReturn(config.defaultJsonSchemaGenerator(generator));
    when(details.getDialect(JsonSchemaDetailsService.class)).thenReturn(mock(JsonSchemaDetailsService.class));
    mvc = MockMvcBuilders.standaloneSetup(new TenantMemberController(
        new TenantMemberServiceImpl(em, mock(ResourceAuthorizationVersionService.class)), details))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter())
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
  }

  AuthorizationContext context() {
    var result = new AuthorizationContext(); result.setScopeType(AuthorizationScopeType.TENANT);
    result.setUserId(owner.getId()); result.setActorRole(AuthorizationActorRole.TENANT_OWNER);
    result.setIsAdministrator(false); result.setResources(Set.of("users.view"));
    result.setAttributes(Map.of("X-Tenant-Id", tenant.getId()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, result);
    return result;
  }

  User member(String name, String tenantId, String orgId, boolean enabled, boolean deletedUser, boolean deletedMember) {
    var user = new User(); user.setName("Legal " + name); user.setNickname(name); user.setEmail(name + "@example.test");
    if (deletedUser) user.setDeletedAt(Instant.now()); em.persist(user);
    var member = new TenantUserRelevance(); member.setUserId(user.getId()); member.setTenantId(tenantId);
    member.setOrgId(orgId); member.setEnabled(enabled);
    if (deletedMember) member.setDeletedAt(Instant.now()); em.persist(member);
    return user;
  }

  @AfterEach
  void cleanup() {
    ResourcePolicyContext.clear();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();}
    if (factory != null) factory.close();
  }

  @Test
  void organizationPageRuleFiltersMemberPageCountAndCandidateRowsThroughCriteria() throws Exception {
    var child = new Organization(); child.setCode("child"); child.setName("Child");
    child.setTenantId(tenant.getId()); child.setParentId(organization.getId());
    child.setEnabled(true); em.persist(child);
    var sibling = new Organization(); sibling.setCode("sibling"); sibling.setName("Sibling");
    sibling.setTenantId(tenant.getId()); sibling.setEnabled(true); em.persist(sibling);
    member("ChildScoped", tenant.getId(), child.getId(), true, false, false);
    member("SiblingScoped", tenant.getId(), sibling.getId(), true, false, false);
    em.flush(); em.clear();
    context.setResources(Set.of("users.view", "organizations.view"));
    context.setResourceDataScopes(Map.of("organizations.view",
        new ResourceDataScope("CUSTOM", Set.of(organization.getId()), false)));
    ResourcePolicyContext.set("organizations.view");

    mvc.perform(get("/tenant-members/by-organization").param("orgId", organization.getId())
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].name").value("Alpha"));
    mvc.perform(get("/tenant-members/organization-candidates").param("orgId", organization.getId())
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    var error = assertThrows(jakarta.servlet.ServletException.class,
        () -> mvc.perform(get("/tenant-members/by-organization").param("orgId", sibling.getId())
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)));
    assertInstanceOf(AccessDeniedException.class, error.getCause());
  }

  @Test
  void explicitMemberPolicyFiltersRowsAndCountEvenForAdministrator() throws Exception {
    context.setIsAdministrator(true);
    context.setResourceDataScopes(Map.of("users.view", new ResourceDataScope("SELF", Set.of(), false)));
    ResourcePolicyContext.set("users.view");
    mvc.perform(get("/tenant-members").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("Alpha"));
  }

  @Test
  void memberExpressionUsesTheSameProjectionForRowsAndCounts() throws Exception {
    context.setResourceDataScopes(Map.of("users.view", new ResourceDataScope("DENY", Set.of(), false,
        List.of("name CONTAINS \"Alpha\" AND orgId IS NOT NULL"))));
    ResourcePolicyContext.set("users.view");
    mvc.perform(get("/tenant-members").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].userId").value(owner.getId()));
  }

  @Test
  void generatedSchemaIsSerializableByTheProductionJsonConverter() throws Exception {
    var response = mvc.perform(get("/tenant-members/schema")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.schema['x-query'].fields.name.sortable").value(true))
        .andExpect(jsonPath("$.schema['x-query'].fields.orgName.javaType").value("String"))
        .andExpect(jsonPath("$.schema['x-query'].fields.revision").doesNotExist()).andReturn();
    var output = java.nio.file.Path.of("build/generated-test-resources/member-schema.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(output, response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    try (var fixture = getClass().getResourceAsStream("/query-contracts/member-schema.fixture.json")) {
      var mapper = new ObjectMapper();
      assertEquals(mapper.readTree(fixture), mapper.readTree(response.getResponse().getContentAsString(
          java.nio.charset.StandardCharsets.UTF_8)), "Browser fixture must match the actual HTTP schema");
    }
  }

  @Test
  void organizationQueriesPageDescendantsAndExcludeOtherTenants() throws Exception {
    var child = new Organization(); child.setCode("engineering-child"); child.setName("Engineering Child");
    child.setTenantId(tenant.getId()); child.setParentId(organization.getId()); child.setEnabled(true); em.persist(child);
    var sibling = new Organization(); sibling.setCode("sales"); sibling.setName("Sales");
    sibling.setTenantId(tenant.getId()); sibling.setEnabled(true); em.persist(sibling);
    member("Child", tenant.getId(), child.getId(), true, false, false);
    member("Sibling", tenant.getId(), sibling.getId(), true, false, false);
    em.flush(); em.clear();
    mvc.perform(get("/tenant-members/by-organization").param("orgId", organization.getId())
            .param("page", "0").param("size", "2")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.content.length()").value(2));
    mvc.perform(get("/tenant-members/by-organization").param("orgId", organization.getId())
            .param("keyword", "child").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("Child"))
        .andExpect(jsonPath("$.content[0].userType").value("INTERNAL"));
    mvc.perform(get("/tenant-members/organization-candidates").param("orgId", organization.getId())
            .param("keyword", "sibling").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].orgName").value("Sales"));
    var failure = assertThrows(jakarta.servlet.ServletException.class,
        () -> mvc.perform(get("/tenant-members/by-organization").param("orgId", "outside")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)));
    assertInstanceOf(AccessDeniedException.class, failure.getCause());
  }

  @Test
  void userTypeUpdateIsValidatedAndVisibleInOrganizationPage() throws Exception {
    var membership = em.createQuery("select m from TenantUserRelevance m where m.userId=:user", TenantUserRelevance.class)
        .setParameter("user", owner.getId()).getSingleResult();
    mvc.perform(put("/tenant-members/" + owner.getId())
            .contentType("application/json")
            .content("{\"orgId\":\"" + organization.getId() + "\",\"revision\":"
                + membership.getRevision() + ",\"userType\":\"EXTERNAL\"}")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk());
    em.flush();
    mvc.perform(get("/tenant-members/by-organization").param("orgId", organization.getId())
            .param("keyword", "Alpha").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].userType").value("EXTERNAL"));
    var invalidType = assertThrows(jakarta.servlet.ServletException.class,
        () -> mvc.perform(put("/tenant-members/" + owner.getId())
            .contentType("application/json")
            .content("{\"orgId\":\"" + organization.getId() + "\",\"revision\":"
                + membership.getRevision() + ",\"userType\":\"UNKNOWN\"}")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)));
    assertInstanceOf(IllegalArgumentException.class, invalidType.getCause());
  }

  @Test
  void countedJoinExcludesDeletedAccountsMembershipsAndOtherTenants() throws Exception {
    mvc.perform(get("/tenant-members").param("sort", "name,asc").param("size", "1").param("page", "2")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.content[0].name").value("Gamma"))
        .andExpect(jsonPath("$.content[0].orgName").isEmpty());
    mvc.perform(get("/tenant-members").param("orgName", "equals:Forbidden")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void genericUserIdentifiersAreQueryableThroughTheStringProjection() throws Exception {
    mvc.perform(get("/tenant-members").param("userId", "equals:" + owner.getId())
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andDo(result -> assertNull(result.getResolvedException()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("Alpha"));
  }

  @Test
  void expressionFieldsSupportHeaderAndBooleanGroupsBeforePaging() throws Exception {
    String query = """
        {"version":1,"where":{"op":"and","children":[
          {"op":"or","children":[{"field":"name","op":"equals","value":"Alpha"},{"field":"name","op":"equals","value":"Gamma"}]},
          {"op":"not","children":[{"field":"enabled","op":"equals","value":false}]}]}}
        """;
    mvc.perform(get("/tenant-members").param("$query", query).param("email", "like:example.test")
            .param("sort", "enabled,desc", "name,desc").param("size", "1")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andDo(result -> assertNull(result.getResolvedException()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].name").value("Gamma"));
    mvc.perform(get("/tenant-members").param("orgName", "{\"version\":1,\"where\":{\"field\":\"orgName\",\"op\":\"equals\",\"value\":\"Engineering\"}}")
            .param("enabled", "equals:false").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("Beta"));
  }

  @Test
  void pagesBeyondOneHundredRemainQueryableWithoutLoadingAllRows() throws Exception {
    for (int i = 0; i < 125; i++) member("Bulk" + String.format("%03d", i), tenant.getId(), null, true, false, false);
    em.flush(); em.clear();
    mvc.perform(get("/tenant-members").param("name", "like:Bulk").param("sort", "name,asc")
            .param("size", "50").param("page", "2").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andDo(result -> assertNull(result.getResolvedException()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(125))
        .andExpect(jsonPath("$.content.length()").value(25))
        .andExpect(jsonPath("$.content[0].name").value("Bulk100"));
  }

  @Test
  void schemaFiltersAndProjectionInheritSourceFieldPermissions() throws Exception {
    context.setFieldPermissions(Map.of(User.class.getName() + "#email", "MASKED",
        Organization.class.getName() + "#name", "HIDDEN"));
    mvc.perform(get("/tenant-members/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.schema['x-query'].fields.enabled.sortable").value(true))
        .andExpect(jsonPath("$.schema['x-query'].fields.email").doesNotExist())
        .andExpect(jsonPath("$.schema['x-query'].fields.name").doesNotExist())
        .andExpect(jsonPath("$.schema.properties.orgName").doesNotExist());
    mvc.perform(get("/tenant-members").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].name").value("***"))
        .andExpect(jsonPath("$.content[0].email").value("***"))
        .andExpect(jsonPath("$.content[0].orgName").doesNotExist());
    var searchFailure = assertThrows(jakarta.servlet.ServletException.class,
        () -> mvc.perform(get("/tenant-members/by-organization").param("orgId", organization.getId())
            .param("keyword", "Alpha").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)));
    assertInstanceOf(AccessDeniedException.class, searchFailure.getCause());
    for (String field : List.of("email", "name", "orgName", "password", "tenantId", "revision")) {
      mvc.perform(get("/tenant-members").param(field, "is:null")
              .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }
    mvc.perform(get("/tenant-members").param("size", "201").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest());
  }

  @Test
  void readAuthorityRemainsMandatoryForRowsAndSchema() {
    // Context setters are write-once; use a fresh platform context, never mutate a tenant context.
    context = new AuthorizationContext(); context.setScopeType(AuthorizationScopeType.PLATFORM);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    for (String path : List.of("/tenant-members", "/tenant-members/schema")) {
      var failure = assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(get(path)
          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)));
      assertInstanceOf(AccessDeniedException.class, failure.getCause());
    }
  }
}
