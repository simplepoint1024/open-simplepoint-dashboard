package org.simplepoint.dna.server;

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
import java.time.Instant;
import java.util.List;
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
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;

/** Real DNA list endpoints, metadata contracts and database permission boundaries. */
class DnaTableQueryHttpTest {
  private SessionFactory factory;
  private Session session;
  private MockMvc mvc;
  private String path;
  private AuthorizationContext context;
  private Object dataSourceService;

  record Endpoint(String module, String name, String field) {
    Class<?> type(String suffix) throws ClassNotFoundException {
      return Class.forName("org.simplepoint.plugin.dna." + module + "." + suffix);
    }
    Class<?> entity() throws ClassNotFoundException {return type("api.entity." + name);}
    boolean catalog() {return name.equals("FederationCatalog");}
  }

  static Stream<Endpoint> endpoints() {
    return Stream.of(new Endpoint("core", "JdbcDataSourceDefinition", "name"),
        new Endpoint("core", "JdbcDriverDefinition", "name"),
        new Endpoint("federation", "FederationCatalog", "name"),
        new Endpoint("federation", "FederationSchema", "name"),
        new Endpoint("federation", "FederationView", "name"),
        new Endpoint("federation", "FederationQueryPolicy", "name"),
        new Endpoint("federation", "FederationQueryTemplate", "name"),
        new Endpoint("federation", "FederationQueryAudit", "catalogCode"),
        new Endpoint("federation", "FederationJdbcConnectionUser", "catalogName"),
        new Endpoint("federation", "DataAsset", "name"),
        new Endpoint("federation", "DataQualityRule", "name"));
  }

  void setup(Endpoint endpoint) throws Exception {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:dna-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
    var metadata = new MetadataSources(registry).addAnnotatedClass(endpoint.entity());
    if (endpoint.catalog()) metadata.addAnnotatedClass(JdbcDataSourceDefinition.class);
    factory = metadata.buildMetadata().buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    seed(endpoint, "match-a", 1, "user-a", false);
    seed(endpoint, "match-b", 2, "user-b", false);
    seed(endpoint, "other", 3, "user-a", false);
    seed(endpoint, "match-deleted", 4, "user-a", true);
    if (endpoint.catalog()) {
      var source = new Endpoint("core", "JdbcDataSourceDefinition", "name");
      seed(source, "match-source", 5, "user-a", false);
      seed(source, "match-hidden", 6, "user-b", false);
      seed(source, "match-source-deleted", 7, "user-a", true);
      dataSourceService = service(source);
    }
    session.flush();
    session.clear();
    authorize("ALL");
    Class<?> controllerClass = endpoint.type("rest.controller." + endpoint.name() + "Controller");
    Object controller = construct(controllerClass, service(endpoint));
    path = controllerClass.getAnnotation(RequestMapping.class).value()[0];
    mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private Object service(Endpoint endpoint) throws Exception {
    var repository = mock(endpoint.type("api.repository." + endpoint.name() + "Repository"),
        withSettings().defaultAnswer(delegatesTo(new BaseRepositoryImpl(endpoint.entity(), session))));
    return construct(endpoint.type("service.impl." + endpoint.name() + "ServiceImpl"), repository);
  }

  private Object construct(Class<?> type, Object dependency) throws Exception {
    var constructor = type.getConstructors()[0];
    Class<?>[] types = constructor.getParameterTypes();
    Object[] args = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      args[i] = types[i].isInstance(dependency) ? dependency
          : types[i] == JdbcDataSourceDefinitionService.class && dataSourceService != null ? dataSourceService
          : types[i] == DetailsProviderService.class ? schemaProvider()
          : types[i] == ObjectMapper.class ? new ObjectMapper()
          : types[i] == List.class ? List.of() : mock(types[i]);
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

  private void seed(Endpoint endpoint, String label, int index, String owner, boolean deleted) throws Exception {
    Object entity = endpoint.entity().getConstructor().newInstance();
    var bean = new BeanWrapperImpl(entity);
    for (var field : endpoint.entity().getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Transient.class)) continue;
      Class<?> type = field.getType();
      Object value = type == String.class ? "f" + index
          : type == Instant.class ? Instant.parse("2026-09-19T00:00:00Z")
          : type == Integer.class ? index : type == Long.class ? (long) index
          : type == Boolean.class ? Boolean.TRUE : type == Set.class ? Set.of("QUERY") : null;
      if (value != null) bean.setPropertyValue(field.getName(), value);
    }
    bean.setPropertyValue(endpoint.field(), label);
    if (endpoint.name().equals("JdbcDataSourceDefinition")) bean.setPropertyValue("code", label);
    if (endpoint.catalog()) bean.setPropertyValue("catalogType", "VIRTUAL");
    bean.setPropertyValue("createdBy", owner);
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

  private void authorize(String scope) {
    // AuthorizationContext is immutable after each attribute's first assignment.
    context = new AuthorizationContext();
    context.setUserId("user-a");
    context.setDataScopeType(scope);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void advancedAndHeaderConditionsSortAndCountBeforePaging(Endpoint endpoint) throws Exception {
    setup(endpoint);
    String document = """
        {"version":1,"where":{"op":"and","children":[
          {"field":"%s","op":"like","value":"match"},
          {"op":"not","children":[{"field":"%s","op":"equals","value":"other"}]}]}}
        """.formatted(endpoint.field(), endpoint.field());
    String[] expected = endpoint.catalog() ? new String[] {"match-source", "match-hidden", "match-b", "match-a"}
        : new String[] {"match-b", "match-a"};
    for (int page = 0; page < expected.length; page++) {
      mvc.perform(get(path).param("$query", document).param("page", String.valueOf(page)).param("size", "1")
              .param("sort", endpoint.field() + ",desc")
              .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(expected.length))
          .andExpect(jsonPath("$.content.length()").value(1))
          .andExpect(jsonPath("$.content[0]." + endpoint.field()).value(expected[page]));
    }
    mvc.perform(get(path).param("$query", document)
            .param(endpoint.field(), "{\"version\":1,\"where\":{\"field\":\"" + endpoint.field()
                + "\",\"op\":\"equals\",\"value\":\"match-a\"}}")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void schemasAndPermissionsDoNotExposeSecretsOrDeletedRows(Endpoint endpoint) throws Exception {
    setup(endpoint);
    mvc.perform(get(path + "/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema['x-query'].fields." + endpoint.field() + ".sortable").value(true));
    for (String field : new String[] {"password", "connectionProperties", "deletedAt", "unknownField"}) {
      mvc.perform(get(path).param("$query", "{\"version\":1,\"where\":{\"field\":\"" + field
              + "\",\"op\":\"is:null\"}}")
              .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }
    authorize("NONE");
    mvc.perform(get(path).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements")
            .value(endpoint.name().equals("JdbcDriverDefinition") ? 3 : 0));
    authorize("SELF");
    mvc.perform(get(path).param("$query", "{\"version\":1,\"where\":{\"field\":\"" + endpoint.field()
            + "\",\"op\":\"like\",\"value\":\"match\"}}")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements")
            .value(endpoint.catalog() || endpoint.name().equals("JdbcDriverDefinition") ? 2 : 1));
  }
}
