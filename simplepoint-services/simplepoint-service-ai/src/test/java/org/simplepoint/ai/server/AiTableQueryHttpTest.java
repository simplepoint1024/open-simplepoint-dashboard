package org.simplepoint.ai.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Transient;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;

/** Scoped AI list adapters using their real controllers, policies, services and Hibernate queries. */
class AiTableQueryHttpTest {
  private SessionFactory factory;
  private Session session;
  private MockMvc mvc;
  private String path;
  private AuthorizationContext context;

  record Endpoint(String module, String name, String service, String controller, String field) {
    Class<?> type(String suffix) throws ClassNotFoundException {
      return Class.forName("org.simplepoint.plugin.ai." + module + "." + suffix);
    }
    Class<?> entity() throws ClassNotFoundException {return type("api.entity." + name);}
  }

  static Stream<Endpoint> endpoints() {
    return Stream.of(
        new Endpoint("core", "AiProviderDefinition", "AiProviderDefinition", "AiProviderDefinition", "name"),
        new Endpoint("core", "AiModelDefinition", "AiModelDefinition", "AiModelDefinition", "modelId"),
        new Endpoint("core", "AiApiKey", "AiApiKey", "AiApiKey", "name"),
        new Endpoint("core", "AiInvocationRecord", "AiInvocationQuery", "AiInvocation", "modelId"),
        new Endpoint("knowledge", "AiKnowledgeBase", "AiKnowledgeBase", "AiKnowledgeBase", "name"),
        new Endpoint("mcp", "AiMcpServerDefinition", "AiMcpServerDefinition", "AiMcpServerDefinition", "name"),
        new Endpoint("mcp", "AiMcpPublication", "AiMcpPublication", "AiMcpPublication", "name"));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  void setup(Endpoint endpoint) throws Exception {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:ai-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
    factory = new MetadataSources(registry).addAnnotatedClass(endpoint.entity())
        .buildMetadata().buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    seed(endpoint, "match-a", 1, "tenant-a", false);
    seed(endpoint, "match-b", 2, "tenant-a", false);
    seed(endpoint, "match-foreign", 3, "tenant-b", false);
    seed(endpoint, "match-system", 4, null, false);
    seed(endpoint, "match-deleted", 5, "tenant-a", true);
    session.flush();
    session.clear();
    context = new AuthorizationContext();
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    context.setUserId("user-a");
    context.setDataScopeType("ALL");
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var repository = mock(endpoint.type("api.repository." + endpoint.name() + "Repository"),
        withSettings().defaultAnswer(delegatesTo(new BaseRepositoryImpl(endpoint.entity(), session))));
    Object service = construct(endpoint.type("service.impl." + endpoint.service() + "ServiceImpl"), repository);
    Class<?> controllerClass = endpoint.type("rest.controller." + endpoint.controller() + "Controller");
    Object controller = construct(controllerClass, service);
    path = controllerClass.getAnnotation(RequestMapping.class).value()[0];
    mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
  }

  private Object construct(Class<?> type, Object dependency) throws Exception {
    var constructor = type.getConstructors()[0];
    Class<?>[] types = constructor.getParameterTypes();
    Object[] args = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      args[i] = types[i].isInstance(dependency) ? dependency
          : types[i] == AiScopeAccessPolicy.class ? new AiScopeAccessPolicy(new AiProperties())
          : types[i] == DetailsProviderService.class ? schemaProvider()
          : types[i] == ObjectMapper.class ? new ObjectMapper() : mock(types[i]);
    }
    return constructor.newInstance(args);
  }

  private DetailsProviderService schemaProvider() {
    var provider = mock(DetailsProviderService.class);
    var config = new JsonSchemaAutoConfiguration(new ObjectMapper());
    var generator = config.schemaGenerator(Set.of(config.openApiModule(), config.jacksonModule(),
        config.javaxValidationModule()));
    when(provider.getDialect(JsonSchemaGenerator.class)).thenReturn(config.defaultJsonSchemaGenerator(generator));
    when(provider.getDialect(JsonSchemaDetailsService.class)).thenReturn(mock(JsonSchemaDetailsService.class));
    return provider;
  }

  private void seed(Endpoint endpoint, String label, int index, String tenant, boolean deleted) throws Exception {
    Object entity = endpoint.entity().getConstructor().newInstance();
    var bean = new BeanWrapperImpl(entity);
    for (var field : endpoint.entity().getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Transient.class)) continue;
      Class<?> type = field.getType();
      Object value = type == String.class ? (field.getName().equals("billingCurrency") ? "USD" : "f" + index)
          : type == Instant.class ? Instant.parse("2026-09-19T00:00:00Z")
          : type == Integer.class ? index : type == Long.class ? (long) index
          : type == Double.class ? 0.5D : type == BigDecimal.class ? BigDecimal.valueOf(index)
          : type == Boolean.class ? Boolean.TRUE : type.isEnum() ? type.getEnumConstants()[0] : null;
      if (value != null) bean.setPropertyValue(field.getName(), value);
    }
    bean.setPropertyValue(endpoint.field(), label);
    bean.setPropertyValue("tenantId", tenant);
    bean.setPropertyValue("scopeType", tenant == null ? AiResourceScope.SYSTEM : AiResourceScope.TENANT);
    if (deleted) bean.setPropertyValue("deletedAt", Instant.now());
    session.persist(entity);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) {
      if (session.getTransaction().isActive()) session.getTransaction().rollback();
      session.close();
    }
    if (factory != null) factory.close();
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void advancedSearchCannotEscapeTenantOrSoftDeleteAndCountMatches(Endpoint endpoint) throws Exception {
    setup(endpoint);
    String document = """
        {"version":1,"where":{"op":"or","children":[
          {"field":"%s","op":"like","value":"match"},
          {"field":"%s","op":"equals","value":"match-foreign"}]}}
        """.formatted(endpoint.field(), endpoint.field());
    for (int page = 0; page < 2; page++) {
      mvc.perform(get(path).param("$query", document).param("page", String.valueOf(page)).param("size", "1")
              .param("sort", endpoint.field() + ",desc")
              .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
          .andExpect(jsonPath("$.content.length()").value(1))
          .andExpect(jsonPath("$.content[0]." + endpoint.field()).value(page == 0 ? "match-b" : "match-a"));
    }
    mvc.perform(get(path).param("$query", "{\"version\":1,\"where\":{\"field\":\"" + endpoint.field()
            + "\",\"op\":\"equals\",\"value\":\"match-foreign\"}}")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get(path).param("$query", document)
            .param(endpoint.field(), "{\"version\":1,\"where\":{\"field\":\"" + endpoint.field()
                + "\",\"op\":\"equals\",\"value\":\"match-a\"}}")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0]." + endpoint.field()).value("match-a"));
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void sensitiveFieldsAreNotQueryableAndSchemasHaveAnEnforceableContract(Endpoint endpoint) throws Exception {
    setup(endpoint);
    var schema = new ObjectMapper().createObjectNode();
    QueryCapabilities.applySchema(endpoint.entity(), schema);
    assertTrue(schema.path("x-query").path("fields").has(endpoint.field()));
    mvc.perform(get(path + "/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema['x-query'].fields." + endpoint.field() + ".sortable").value(true))
        .andExpect(jsonPath("$.schema['x-query'].fields.tenantId").doesNotExist());
    int maxPageSize = QueryCapabilities.declaration(endpoint.entity()).maxPageSize();
    mvc.perform(get(path).param("size", String.valueOf(maxPageSize))
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
    mvc.perform(get(path).param("size", String.valueOf(maxPageSize + 1))
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    for (String field : new String[] {"tenantId", "deletedAt", "secretHash", "credentialCiphertext", "unknownField"}) {
      assertFalse(schema.path("x-query").path("fields").has(field));
      mvc.perform(get(path).param("$query",
              "{\"version\":1,\"where\":{\"field\":\"" + field + "\",\"op\":\"is:null\"}}")
              .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void maskedColumnsCannotBeInferredButHiddenTenantScopeStillApplies(Endpoint endpoint) throws Exception {
    setup(endpoint);
    context.setFieldPermissions(Map.of(endpoint.entity().getName() + "#tenantId", "HIDDEN",
        endpoint.entity().getName() + "#" + endpoint.field(), "MASKED"));
    mvc.perform(get(path).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
    mvc.perform(get(path + "/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema['x-query'].fields." + endpoint.field()).doesNotExist());
    mvc.perform(get(path).param(endpoint.field(), "equals:match-a")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    mvc.perform(get(path).param("sort", endpoint.field() + ",asc")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
  }
}
