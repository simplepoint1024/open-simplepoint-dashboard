package org.simplepoint.ai.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Transient;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.core.jackson.FieldScopeJackson3Module;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.properties.AiProperties;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.runtime.rest.controller.AiRuntimeTableSchemaController;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;

/** Actual runtime controllers/services/SQL, not a mock that silently ignores query parameters. */
class RuntimeTableQueryHttpTest {
  private SessionFactory factory;
  private Session session;
  private MockMvc mvc;
  private AuthorizationContext context;
  private String path;
  private final ObjectMapper mapper = new ObjectMapper();

  record Endpoint(String kind, String label) {
    Class<?> type(String middle) throws Exception {
      return Class.forName("org.simplepoint.plugin.ai.runtime." + middle);
    }

    String name() {
      return "AiRuntime" + kind;
    }

    Class<?> entity() throws Exception {
      return type("api.entity." + name());
    }

    boolean node() {
      return kind.equals("Node");
    }
  }

  static Stream<Endpoint> endpoints() {
    return Stream.of(
        new Endpoint("Node", "displayName"),
        new Endpoint("Secret", "name"),
        new Endpoint("Pool", "name"),
        new Endpoint("Workload", "executionId"));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  void setup(Endpoint endpoint, int count) throws Exception {
    String database = System.getenv("RUNTIME_QUERY_DB_URL");
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting(
                "hibernate.connection.url",
                database == null ? "jdbc:h2:mem:runtime-" + UUID.randomUUID() : database)
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .build();
    factory =
        new MetadataSources(registry)
            .addAnnotatedClass(endpoint.entity())
            .buildMetadata()
            .buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    for (int i = 0; i < count; i++) seed(endpoint, i, "tenant-a", false);
    seed(endpoint, count, "tenant-a", true);
    if (!endpoint.node()) {
      seed(endpoint, count + 1, "tenant-b", false);
      seed(endpoint, count + 2, null, false);
    }
    session.flush();
    session.clear();
    context = new AuthorizationContext();
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    context.setUserId("runtime-reader");
    context.setDataScopeType("ALL");
    context.setIsAdministrator(false);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var repository =
        mock(
            endpoint.type("api.repository." + endpoint.name() + "Repository"),
            withSettings()
                .defaultAnswer(delegatesTo(new BaseRepositoryImpl(endpoint.entity(), session))));
    var proxy = new org.springframework.aop.framework.ProxyFactory(repository);
    proxy.addAdvice(
        new org.springframework.dao.support.PersistenceExceptionTranslationInterceptor(
            new org.springframework.orm.jpa.vendor.HibernateJpaDialect()));
    Object service =
        construct(
            endpoint.type("service.impl." + endpoint.name() + "ServiceImpl"), proxy.getProxy());
    Class<?> controllerType = endpoint.type("rest.controller." + endpoint.name() + "Controller");
    Object controller = construct(controllerType, service);
    path = controllerType.getAnnotation(RequestMapping.class).value()[0];
    mvc =
        MockMvcBuilders.standaloneSetup(
                controller,
                new AiRuntimeTableSchemaController(
                    schemaProvider(), new AiScopeAccessPolicy(new AiProperties())))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(
                    tools.jackson.databind.json.JsonMapper.builder()
                        .addModule(new FieldScopeJackson3Module())
                        .build()))
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
  }

  private Object construct(Class<?> type, Object dependency) throws Exception {
    var constructor = type.getConstructors()[0];
    var types = constructor.getParameterTypes();
    Object[] args = new Object[types.length];
    for (int i = 0; i < types.length; i++)
      args[i] =
          types[i].isInstance(dependency)
              ? dependency
              : types[i] == AiScopeAccessPolicy.class
                  ? new AiScopeAccessPolicy(new AiProperties())
                  : types[i] == ObjectMapper.class ? mapper : mock(types[i]);
    return constructor.newInstance(args);
  }

  private DetailsProviderService schemaProvider() {
    var provider = mock(DetailsProviderService.class);
    var config = new JsonSchemaAutoConfiguration(mapper);
    var generator =
        config.schemaGenerator(
            Set.of(config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()));
    when(provider.getDialect(JsonSchemaGenerator.class))
        .thenReturn(config.defaultJsonSchemaGenerator(generator));
    when(provider.getDialect(JsonSchemaDetailsService.class))
        .thenReturn(mock(JsonSchemaDetailsService.class));
    return provider;
  }

  private void seed(Endpoint endpoint, int index, String tenant, boolean deleted) throws Exception {
    Object entity = endpoint.entity().getConstructor().newInstance();
    var bean = new BeanWrapperImpl(entity);
    for (var field : endpoint.entity().getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Transient.class))
        continue;
      Class<?> type = field.getType();
      Object value =
          type == String.class
              ? "f" + index
              : type == Instant.class
                  ? Instant.parse("2026-09-19T00:00:00Z")
                  : type == Integer.class
                      ? index
                      : type == Long.class
                          ? (long) index
                          : type == Boolean.class
                              ? true
                              : type.isEnum() ? type.getEnumConstants()[0] : null;
      if (value != null) bean.setPropertyValue(field.getName(), value);
    }
    bean.setPropertyValue(endpoint.label(), "Item%03d".formatted(index));
    if (endpoint.node()) {
      bean.setPropertyValue("labelsJson", "{}");
      bean.setPropertyValue("cachedImageDigestsJson", "[]");
    } else {
      bean.setPropertyValue("tenantId", tenant);
      bean.setPropertyValue(
          "scopeType", tenant == null ? AiResourceScope.SYSTEM : AiResourceScope.TENANT);
    }
    if (deleted) bean.setPropertyValue("deletedAt", Instant.now());
    session.persist(entity);
  }

  private MockHttpServletRequestBuilder request() {
    return get(path).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private String condition(String field, String op, Object value) throws Exception {
    return mapper.writeValueAsString(
        Map.of("version", 1, "where", Map.of("field", field, "op", op, "value", value)));
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
  void filtersAndSortApplyBeforePaginationBeyondTheOld500Limit(Endpoint endpoint) throws Exception {
    setup(endpoint, 520);
    mvc.perform(
            request()
                .param("page", "25")
                .param("size", "20")
                .param("sort", endpoint.label() + ",asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(520))
        .andExpect(jsonPath("$.content[19]." + endpoint.label()).value("Item519"));
    String where =
        "{\"version\":1,\"where\":{\"op\":\"or\",\"children\":["
            + "{\"field\":\""
            + endpoint.label()
            + "\",\"op\":\"equals\",\"value\":\"Item519\"},"
            + "{\"field\":\""
            + endpoint.label()
            + "\",\"op\":\"like\",\"value\":\"Item52\"}]}}";
    mvc.perform(
            request()
                .param("$query", where)
                .param(endpoint.label(), condition(endpoint.label(), "like", "Item5")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0]." + endpoint.label()).value("Item519"));
    mvc.perform(request().param("size", "101")).andExpect(status().isBadRequest());
    if (!endpoint.node()) {
      context = new AuthorizationContext();
      context.setScopeType(AuthorizationScopeType.PLATFORM);
      context.setIsAdministrator(true);
      mvc.perform(request())
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.totalElements").value(1))
          .andExpect(jsonPath("$.content[0]." + endpoint.label()).value("Item522"));
    }
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void everyDeclaredFieldAndActualSchemaMatchesTheDatabaseCompiler(Endpoint endpoint)
      throws Exception {
    setup(endpoint, 2);
    var response =
        mvc.perform(
                get(path + "/schema")
                    .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.schema['x-query'].maxPageSize").value(100))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var schema = mapper.readTree(response);
    var file =
        Path.of("../../simplepoint-react/scripts/runtime-schemas/" + endpoint.name() + ".json");
    if (Boolean.getBoolean("simplepoint.exportSchema")) {
      Files.createDirectories(file.getParent());
      Files.writeString(file, schema.toString());
    } else assertEquals(mapper.readTree(Files.readString(file)), schema);
    for (var declaration : QueryCapabilities.declaration(endpoint.entity()).value()) {
      String field = declaration.value();
      mvc.perform(
              request()
                  .param(
                      "$query",
                      "{\"version\":1,\"where\":{\"field\":\""
                          + field
                          + "\",\"op\":\"is:not:null\"}}")
                  .param("sort", field + ",desc"))
          .andExpect(status().isOk());
    }
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void restrictedAndInternalFieldsCannotBeFilteredOrSorted(Endpoint endpoint) throws Exception {
    setup(endpoint, 2);
    context.setFieldPermissions(
        Map.of(endpoint.entity().getName() + "#" + endpoint.label(), "MASKED"));
    mvc.perform(request())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0]." + endpoint.label()).value("***"));
    mvc.perform(
            get(path + "/schema")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema['x-query'].fields." + endpoint.label()).doesNotExist());
    for (String field :
        new String[] {
          endpoint.label(),
          "value",
          "valueCiphertext",
          "runtimeSpecJson",
          "secretReferencesJson",
          "deletedAt",
          "tenantId",
          "scopeType"
        }) {
      mvc.perform(request().param("$query", condition(field, "equals", "probe")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
      mvc.perform(request().param("sort", field + ",asc")).andExpect(status().isBadRequest());
    }
    if (endpoint.kind().equals("Secret"))
      mvc.perform(request())
          .andExpect(jsonPath("$.content[0].value").doesNotExist())
          .andExpect(jsonPath("$.content[0].valueCiphertext").doesNotExist());
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void hiddenIdentityDeniesRowsSchemaAndTotals(Endpoint endpoint) throws Exception {
    setup(endpoint, 2);
    context.setFieldPermissions(Map.of(endpoint.entity().getName() + "#id", "HIDDEN"));
    for (String suffix : new String[] {"", "/schema"}) {
      var error =
          assertThrows(
              jakarta.servlet.ServletException.class,
              () ->
                  mvc.perform(
                      get(path + suffix)
                          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)));
      assertInstanceOf(
          org.springframework.security.access.AccessDeniedException.class, error.getCause());
    }
  }
}
