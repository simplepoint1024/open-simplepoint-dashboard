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
  org.springframework.web.context.support.AnnotationConfigWebApplicationContext web;
  TenantMemberServiceImpl service;
  ResourceAuthorizationVersionService versions;
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
        .addAnnotatedClass(org.simplepoint.security.entity.UserRoleRelevance.class)
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
    versions = mock(ResourceAuthorizationVersionService.class);
    service = new TenantMemberServiceImpl(em, versions);
    web = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
    web.setServletContext(new org.springframework.mock.web.MockServletContext());
    web.register(PluginWebConfiguration.class);
    web.addBeanFactoryPostProcessor(beans -> {
      beans.registerSingleton("members", service);
      beans.registerSingleton("details", details);
    });
    web.refresh();
    mvc = MockMvcBuilders.webAppContextSetup(web).build();
  }

  AuthorizationContext context() { return context(Set.of("users.view", "organizations.view", "organizations.members.remove")); }

  AuthorizationContext context(Set<String> resources) {
    var result = new AuthorizationContext(); result.setScopeType(AuthorizationScopeType.TENANT);
    result.setUserId(owner.getId()); result.setActorRole(AuthorizationActorRole.TENANT_OWNER);
    result.setIsAdministrator(false); result.setResources(resources);
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
    if (web != null) web.close();
    ResourcePolicyContext.clear();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();}
    if (factory != null) factory.close();
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @org.springframework.web.servlet.config.annotation.EnableWebMvc
  static class PluginWebConfiguration implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
    @org.springframework.context.annotation.Bean
    TenantMemberController memberController(TenantMemberServiceImpl members, DetailsProviderService details) {
      return new TenantMemberController(members, details);
    }
    @org.springframework.context.annotation.Bean
    GlobalExceptionHandler errors() { return new GlobalExceptionHandler(); }
    @org.springframework.context.annotation.Bean
    org.simplepoint.plugin.webmvc.handler.ServletMappingPluginInstanceHandler pluginMapping(
        org.springframework.context.ApplicationContext context) {
      var mapping = new org.simplepoint.plugin.webmvc.handler.ServletMappingPluginInstanceHandler(
          new org.simplepoint.plugin.spring.handle.SpringBeanPluginInstanceHandler(context));
      mapping.setOrder(-1);
      return mapping;
    }
    @org.springframework.context.annotation.Bean
    org.springframework.web.servlet.handler.MappedInterceptor resourcePermissions() throws Exception {
      var catalog = new org.simplepoint.security.oauth2.resourceserver.scope.ClasspathResourceScopeRegistry(new ObjectMapper());
      return new org.simplepoint.security.oauth2.resourceserver.config.ResourceServerAutoConfiguration()
          .resourceScopeInterceptor(catalog);
    }
    @Override public void addArgumentResolvers(List<org.springframework.web.method.support.HandlerMethodArgumentResolver> resolvers) {
      resolvers.add(new PageableHandlerMethodArgumentResolver());
    }
    @Override public void configureMessageConverters(List<org.springframework.http.converter.HttpMessageConverter<?>> converters) {
      converters.add(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter());
    }
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

    mvc.perform(get("/tenant-members").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("Alpha"));
  }

  @Test
  void memberExpressionUsesTheSameProjectionForRowsAndCounts() throws Exception {
    context.setResourceDataScopes(Map.of("users.view", new ResourceDataScope("DENY", Set.of(), false,
        List.of("name CONTAINS \"Alpha\" AND orgId IS NOT NULL"))));

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
  void memberEditRetainsReadonlyOrganizationAndRejectsCapturedStaleRevision() throws Exception {
    context.setResourceFieldPermissions(Map.of("users.view", Map.of(
        TenantUserRelevance.class.getName() + "#orgId", "VISIBLE")));
    var relation = membership(owner.getId());
    Long capturedRevision = relation.getRevision();
    var command = new org.simplepoint.plugin.rbac.core.api.pojo.command.TenantMemberCommand(
        null, organization.getId(), null, capturedRevision, "EXTERNAL");
    mvc.perform(get("/tenant-members/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.schema.properties.orgId.readOnly").value(true));
    mvc.perform(put("/tenant-members/" + owner.getId()).contentType("application/json")
        .content(new ObjectMapper().writeValueAsString(command))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk());
    em.flush();
    assertEquals(organization.getId(), relation.getOrgId());
    assertEquals("EXTERNAL", relation.getUserType());
    mvc.perform(put("/tenant-members/" + owner.getId()).contentType("application/json")
        .content(new ObjectMapper().writeValueAsString(command))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBER_CHANGED"));
    var clearing = assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(
        put("/tenant-members/" + owner.getId()).contentType("application/json")
          .content(new ObjectMapper().writeValueAsString(new org.simplepoint.plugin.rbac.core.api.pojo.command.TenantMemberCommand(
              null, null, null, relation.getRevision(), null)))
          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)));
    assertInstanceOf(AccessDeniedException.class, clearing.getCause());
    assertEquals(organization.getId(), relation.getOrgId());
  }

  @Test
  void memberRoleCapabilityRequiresBothTenantManagementAndActualAssignmentAuthority() throws Exception {
    mvc.perform(get("/tenant-members/capabilities").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.manage").value(true))
        .andExpect(jsonPath("$.configureRoles").value(false));
    context = context(Set.of("users.view", "users.config.role"));
    mvc.perform(get("/tenant-members/capabilities").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.configureRoles").value(true));
    var viewer = new AuthorizationContext(); viewer.setScopeType(AuthorizationScopeType.TENANT);
    viewer.setUserId("member-viewer"); viewer.setActorRole(AuthorizationActorRole.TENANT_MEMBER);
    viewer.setIsAdministrator(false); viewer.setResources(Set.of("users.view", "users.config.role"));
    viewer.setAttributes(Map.of("X-Tenant-Id", tenant.getId()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, viewer);
    mvc.perform(get("/tenant-members/capabilities").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, viewer))
        .andExpect(status().isOk()).andExpect(jsonPath("$.manage").value(false))
        .andExpect(jsonPath("$.configureRoles").value(false));
  }

  @Test
  void tenantMemberRemovalDeletesOnlyCurrentMembershipAndTenantRolesNotGlobalAccountOrOtherTenants() throws Exception {
    var user = member("SharedMember", tenant.getId(), organization.getId(), true, false, false);
    var otherMember = new TenantUserRelevance(); otherMember.setTenantId("outside"); otherMember.setUserId(user.getId());
    otherMember.setEnabled(true); em.persist(otherMember);
    var currentRole = new org.simplepoint.security.entity.UserRoleRelevance(); currentRole.setTenantId(tenant.getId());
    currentRole.setUserId(user.getId()); currentRole.setRoleId("current-role"); em.persist(currentRole);
    var otherRole = new org.simplepoint.security.entity.UserRoleRelevance(); otherRole.setTenantId("outside");
    otherRole.setUserId(user.getId()); otherRole.setRoleId("other-role"); em.persist(otherRole);
    var globalRole = new org.simplepoint.security.entity.UserRoleRelevance(); globalRole.setTenantId("__platform__"); globalRole.setUserId(user.getId());
    globalRole.setRoleId("global-role"); em.persist(globalRole); em.flush();
    String memberId = membership(user.getId()).getId();
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/tenant-members/" + user.getId())
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk());
    em.flush(); em.clear();
    assertNull(em.find(TenantUserRelevance.class, memberId));
    assertNull(em.find(org.simplepoint.security.entity.UserRoleRelevance.class, currentRole.getId()));
    assertEquals(user.getId(), em.find(User.class, user.getId()).getId());
    assertEquals(user.getId(), em.find(TenantUserRelevance.class, otherMember.getId()).getUserId());
    assertEquals("other-role", em.find(org.simplepoint.security.entity.UserRoleRelevance.class, otherRole.getId()).getRoleId());
    assertEquals("global-role", em.find(org.simplepoint.security.entity.UserRoleRelevance.class, globalRole.getId()).getRoleId());
    org.mockito.Mockito.verify(versions).refreshTenant(tenant.getId());
    var ownerRemoval = assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/tenant-members/" + owner.getId())
          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)));
    assertInstanceOf(AccessDeniedException.class, ownerRemoval.getCause());
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
  private TenantUserRelevance membership(String userId) {
    return em.createQuery("select m from TenantUserRelevance m where m.userId=:user and m.tenantId=:tenant", TenantUserRelevance.class)
        .setParameter("user", userId).setParameter("tenant", tenant.getId()).getSingleResult();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder detach(String userId, String orgId, Long revision) throws Exception {
    return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/tenant-members/by-organization/" + userId)
        .contentType("application/json").content(new ObjectMapper().writeValueAsString(
            new org.simplepoint.plugin.rbac.core.api.pojo.command.OrganizationMemberRemoval(orgId, revision)))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @Test
  void removalFromParentDetachesChildMemberAndRetainsTenantAccountAndRoles() throws Exception {
    var child = new Organization(); child.setCode("child"); child.setName("Child");
    child.setTenantId(tenant.getId()); child.setParentId(organization.getId()); child.setEnabled(true); em.persist(child);
    var user = member("Removable", tenant.getId(), child.getId(), true, false, false);
    var role = new org.simplepoint.security.entity.UserRoleRelevance();
    role.setTenantId(tenant.getId()); role.setUserId(user.getId()); role.setRoleId("retained-role"); em.persist(role);
    em.flush();
    context.setResourceDataScopes(Map.of("organizations.view", new ResourceDataScope("DEPT_AND_BELOW",
        Set.of(organization.getId(), child.getId()), false)));
    var relation = membership(user.getId());
    mvc.perform(detach(user.getId(), organization.getId(), relation.getRevision())).andExpect(status().isOk());
    em.flush(); em.clear();
    assertNull(membership(user.getId()).getOrgId());
    assertEquals(true, membership(user.getId()).getEnabled());
    assertEquals("INTERNAL", membership(user.getId()).getUserType());
    assertEquals(user.getId(), em.find(User.class, user.getId()).getId());
    assertEquals("retained-role", em.find(org.simplepoint.security.entity.UserRoleRelevance.class, role.getId()).getRoleId());
    org.mockito.Mockito.verify(versions).refreshTenant(tenant.getId());
    assertNull(ResourcePolicyContext.get());
    mvc.perform(get("/tenant-members/by-organization").param("orgId", organization.getId())
        .param("keyword", "Removable").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get("/tenant-members").param("userId", "equals:" + user.getId())
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void removalRejectsStaleRevisionForeignMembershipAndOutOfScopeSource() throws Exception {
    var relation = membership(owner.getId());
    mvc.perform(detach(owner.getId(), organization.getId(), relation.getRevision() + 1)).andExpect(status().isConflict());
    var foreign = em.createQuery("select u from User u where u.nickname='Foreign'", User.class).getSingleResult();
    var crossTenant = assertThrows(jakarta.servlet.ServletException.class,
        () -> mvc.perform(detach(foreign.getId(), organization.getId(), 0L)));
    assertInstanceOf(IllegalArgumentException.class, crossTenant.getCause());
    var sibling = new Organization(); sibling.setCode("sibling"); sibling.setName("Sibling");
    sibling.setTenantId(tenant.getId()); sibling.setEnabled(true); em.persist(sibling); em.flush();
    var wrongParent = assertThrows(jakarta.servlet.ServletException.class,
        () -> mvc.perform(detach(owner.getId(), sibling.getId(), relation.getRevision())));
    assertInstanceOf(AccessDeniedException.class, wrongParent.getCause());
    context.setResourceDataScopes(Map.of("organizations.view", new ResourceDataScope("CUSTOM", Set.of(sibling.getId()), false)));
    var denied = assertThrows(jakarta.servlet.ServletException.class,
        () -> mvc.perform(detach(owner.getId(), organization.getId(), relation.getRevision())));
    assertInstanceOf(AccessDeniedException.class, denied.getCause());
    assertEquals(organization.getId(), relation.getOrgId());
    org.mockito.Mockito.verifyNoInteractions(versions);
  }

  @Test
  void organizationFieldPolicyControlsSchemaProjectionUpdatesAndRemovalWithoutContextBleed() throws Exception {
    context.setResourceFieldPermissions(Map.of("organizations.view", Map.of(
        TenantUserRelevance.class.getName() + "#orgId", "VISIBLE",
        TenantUserRelevance.class.getName() + "#userType", "HIDDEN",
        User.class.getName() + "#email", "MASKED")));
    mvc.perform(get("/tenant-members/by-organization/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.schema.properties.orgId.readOnly").value(true))
        .andExpect(jsonPath("$.schema.properties.userType").doesNotExist());
    mvc.perform(get("/tenant-members/by-organization").param("orgId", organization.getId())
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].email").value("***"))
        .andExpect(jsonPath("$.content[0].userType").doesNotExist());
    var relation = membership(owner.getId());
    var denied = assertThrows(jakarta.servlet.ServletException.class,
        () -> mvc.perform(detach(owner.getId(), organization.getId(), relation.getRevision())));
    assertInstanceOf(AccessDeniedException.class, denied.getCause());
    var write = assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(
        put("/tenant-members/by-organization/" + owner.getId()).contentType("application/json")
          .content(new ObjectMapper().writeValueAsString(new org.simplepoint.plugin.rbac.core.api.pojo.command.TenantMemberCommand(
              null, organization.getId(), null, relation.getRevision(), "EXTERNAL")))
          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)));
    assertInstanceOf(AccessDeniedException.class, write.getCause());
    assertNull(ResourcePolicyContext.get());
    mvc.perform(get("/tenant-members/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.schema.properties.userType").exists());
    mvc.perform(get("/tenant-members").param("userId", "equals:" + owner.getId())
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].email").value("Alpha@example.test"));
    assertEquals(organization.getId(), relation.getOrgId());
    org.mockito.Mockito.verifyNoInteractions(versions);
  }

  @Test
  void managerStillNeedsExplicitRemovalAuthority() throws Exception {
    context = context(Set.of("users.view", "organizations.view"));
    var relation = membership(owner.getId());
    var denied = assertThrows(jakarta.servlet.ServletException.class,
        () -> mvc.perform(detach(owner.getId(), organization.getId(), relation.getRevision())));
    assertInstanceOf(AccessDeniedException.class, denied.getCause());
    assertEquals(organization.getId(), relation.getOrgId());
  }

}
