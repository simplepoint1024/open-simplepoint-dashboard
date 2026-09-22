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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PermissionExpressionHttpTest {
  SessionFactory factory; Session em; MockMvc mvc; AuthorizationContext context;
  @BeforeEach void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:expression-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build())
        .addAnnotatedClass(Resource.class).buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction();
    Resource resource = new Resource(); resource.setCode("organizations.view"); resource.setName("Organizations");
    resource.setType(ResourceType.PAGE); resource.setDisabled(false);
    resource.setPermissionMetadata(new PermissionPageDescriptor(List.of(PermissionModelDescriptor.describe(Organization.class))).toJson());
    em.persist(resource); em.flush();
    context = new AuthorizationContext(); context.setUserId("u1"); context.setResources(Set.of("organizations.view", "data-scope.view"));
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var controller = new DataScopeController(mock(DataScopeService.class));
    org.springframework.test.util.ReflectionTestUtils.setField(controller, "expressions", new PermissionExpressionService(em));
    mvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new org.simplepoint.core.handler.GlobalExceptionHandler())
        .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter()).build();
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
}
