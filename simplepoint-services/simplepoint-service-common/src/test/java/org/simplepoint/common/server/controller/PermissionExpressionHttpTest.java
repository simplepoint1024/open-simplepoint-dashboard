package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.core.*;
import org.simplepoint.core.permission.*;
import org.simplepoint.plugin.rbac.core.api.service.DataScopeService;
import org.simplepoint.plugin.rbac.core.rest.controller.DataScopeController;
import org.simplepoint.plugin.rbac.core.service.impl.PermissionExpressionService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.security.entity.*;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceEntitlement;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PermissionExpressionHttpTest {
  SessionFactory factory; Session em; MockMvc mvc; AuthorizationContext context;
  final List<String> statements = new ArrayList<>();
  @BeforeEach void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:expression-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.session_factory.statement_inspector", (org.hibernate.resource.jdbc.spi.StatementInspector) sql -> {statements.add(sql); return sql;}).build())
        .addAnnotatedClass(org.simplepoint.plugin.rbac.tenant.api.entity.Tenant.class).addAnnotatedClass(TenantResource.class).addAnnotatedClass(TenantResourceSetup.class).addAnnotatedClass(TenantResourceEntitlement.class).buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction(); TenantPermissionFixture.prepare(em, "tenant-a");
    TenantResource resource = TenantPermissionFixture.resource("tenant-a", UUID.randomUUID().toString()); resource.setCode("organizations.view"); resource.setName("Organizations");
    resource.setType(ResourceType.PAGE); resource.setEnabled(true);
    resource.setPermissionMetadata(new PermissionPageDescriptor(List.of(PermissionModelDescriptor.describe(Organization.class))).toJson());
    em.persist(resource); em.flush();
    context = new AuthorizationContext(); context.setScopeType(org.simplepoint.core.AuthorizationScopeType.TENANT); context.setAttributes(Map.of("X-Tenant-Id", "tenant-a")); context.setUserId("u1"); context.setResources(Set.of("organizations.view", "data-scope.view"));
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var controller = new DataScopeController(mock(DataScopeService.class));
    org.springframework.test.util.ReflectionTestUtils.setField(controller, "expressions", TenantPermissionFixture.attach(em, new PermissionExpressionService(em)));
    mvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new org.simplepoint.core.handler.GlobalExceptionHandler())
        .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter()).build();
    statements.clear(); factory.getStatistics().clear();
  }
  @AfterEach void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();}
    if (factory != null) factory.close();
  }
  @Test void textAndVisualRoundTripThroughProductionJsonConverter() throws Exception {
    mvc.perform(get("/data-scopes/expression/catalog").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$['organizations.view'].models[0].properties.name.type").value("java.lang.String"));
    var result = mvc.perform(post("/data-scopes/expression/validate").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)
        .contentType("application/json").content("""
          {"resourceCode":"organizations.view","expression":"name CONTAINS \\\"研发\\\" AND enabled = true"}
          """)).andExpect(status().isOk()).andExpect(jsonPath("$.ast.children[0].value").value("研发"))
        .andExpect(jsonPath("$.ast.children[1].value").value(true)).andReturn();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var parsed = mapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    String body = mapper.writeValueAsString(Map.of("resourceCode", "organizations.view", "ast", parsed.get("ast")));
    mvc.perform(post("/data-scopes/expression/validate").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)
        .contentType("application/json").content(body)).andExpect(status().isOk())
        .andExpect(jsonPath("$.expression").value(parsed.get("expression").asText()));
  }
  @Test void unknownPageAndInjectedSyntaxAreRejectedBeforeSql() throws Exception {
    for (var body : List.of(
        "{\"resourceCode\":\"foreign.view\",\"expression\":\"name = \\\"x\\\"\"}",
        "{\"resourceCode\":\"organizations.view\",\"expression\":\"name = \\\"x\\\"; select * from users\"}")) {
      mvc.perform(post("/data-scopes/expression/validate").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)
          .contentType("application/json").content(body)).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PERMISSION_POLICY"));
    }
  }
  @Test void targetedMetadataHttpUsesTheExactGrantedPageWithoutEnumeratingItsNeighbors() throws Exception {
    var unrelated = TenantPermissionFixture.resource("tenant-a", UUID.randomUUID().toString()); unrelated.setId("other"); unrelated.setCode("other.view"); unrelated.setName("Other page");
    unrelated.setType(ResourceType.PAGE); unrelated.setEnabled(true); unrelated.setPermissionMetadata("unparseable granted neighbor");
    em.persist(unrelated); em.flush(); em.clear();
    var next = new AuthorizationContext(); next.setScopeType(org.simplepoint.core.AuthorizationScopeType.TENANT); next.setAttributes(Map.of("X-Tenant-Id", "tenant-a")); next.setUserId("u1"); next.setResources(Set.of("organizations.view", "other.view", "data-scope.view"));
    context = next; RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    statements.clear(); factory.getStatistics().clear();
    mvc.perform(get("/data-scopes/expression/catalog").param("resourceCode", "organizations.view")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$['organizations.view'].models[0].properties.name.type").value("java.lang.String"))
        .andExpect(jsonPath("$['other.view']").doesNotExist());
    assertEquals(1, Arrays.stream(factory.getStatistics().getQueries()).filter(query -> query.contains("permissionMetadata")).mapToLong(query -> factory.getStatistics().getQueryStatistics(query).getExecutionCount()).sum());
    assertTrue(statements.stream().filter(sql -> sql.startsWith("select") && sql.contains("permissionMetadata")).allMatch(sql -> sql.contains("code in") && sql.contains("fetch first ? rows only")));
  }
  @Test void unknownOrEmptyTargetHttpCannotFallBackToTheWholeCatalogOrImpersonateAGrant() throws Exception {
    for (String code : List.of("foreign.view", "", " ", "organizations.view ")) {
      mvc.perform(get("/data-scopes/expression/catalog").param("resourceCode", code).param("userId", "admin").param("tenantId", "foreign")
          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk()).andExpect(content().json("{}"));
    }
    assertEquals(0, factory.getStatistics().getQueryExecutionCount()); assertTrue(statements.isEmpty());
    for (String body : List.of("{}", "{\"resourceCode\":\"\"}", "{\"resourceCode\":\"foreign.view\"}")) {
      mvc.perform(post("/data-scopes/expression/validate").contentType("application/json").content(body)
          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PERMISSION_POLICY"));
    }
    assertEquals(0, factory.getStatistics().getQueryExecutionCount()); assertTrue(statements.isEmpty());
  }
  @Test void sameAuthorizationContextStopsDiscoveringAndValidatingAfterEntitlementExpires() throws Exception {
    var page = em.createQuery("from TenantResource r where r.tenantId='tenant-a' and r.code='organizations.view'", TenantResource.class).getSingleResult();
    page.setBaselineEntitled(false);
    var balance = new TenantResourceEntitlement(); balance.setTenantId("tenant-a"); balance.setId(page.getId());
    balance.setStartsAt(java.time.Instant.now().minusSeconds(120)); balance.setExpiresAt(java.time.Instant.now().plusSeconds(120)); balance.setUpdatedAt(java.time.Instant.now()); em.persist(balance); em.flush();
    mvc.perform(get("/data-scopes/expression/catalog").param("resourceCode", "organizations.view")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk())
        .andExpect(jsonPath("$['organizations.view']").exists());
    balance.setExpiresAt(java.time.Instant.now().minusSeconds(1)); page.setPermissionMetadata("unparseable expired definition"); em.flush(); em.clear();
    mvc.perform(get("/data-scopes/expression/catalog").param("resourceCode", "organizations.view")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk()).andExpect(content().json("{}"));
    mvc.perform(post("/data-scopes/expression/validate").contentType("application/json")
        .content("{\"resourceCode\":\"organizations.view\",\"expression\":\"enabled = true\"}")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PERMISSION_POLICY"));
  }

}
