package org.simplepoint.common.server.controller;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Modifier;
import java.time.Instant;
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
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;

/** Actual i18n controllers, schema generator, services and database query compilation. */
class I18nTableQueryHttpTest {
  private SessionFactory factory;
  private Session session;
  private MockMvc mvc;
  private String path;
  private AuthorizationContext context;

  record Endpoint(String name, String controller, String field, String orderField) {
    Class<?> entity() throws ClassNotFoundException {
      return Class.forName(name.equals("Message") ? "org.simplepoint.core.entity.Message"
          : "org.simplepoint.plugin.i18n.api.entity." + name);
    }
  }

  static Stream<Endpoint> endpoints() {
    return Stream.of(new Endpoint("Countries", "Countries", "defaultTimezone", "isoCode2"),
        new Endpoint("Language", "Languages", "nameEnglish", "code"),
        new Endpoint("Region", "Regions", "nameEnglish", "code"),
        new Endpoint("TimeZone", "TimeZones", "nameEnglish", "code"),
        new Endpoint("Namespace", "Namespace", "name", "code"),
        new Endpoint("Message", "Messages", "message", "code"));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  void setup(Endpoint endpoint) throws Exception {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:i18n-query-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
    factory = new MetadataSources(registry).addAnnotatedClass(endpoint.entity()).buildMetadata()
        .buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    seed(endpoint, "match", 1, false);
    seed(endpoint, "match", 2, false);
    seed(endpoint, "other", 3, false);
    seed(endpoint, "match", 4, true);
    session.flush();
    session.clear();
    // These are global reference lists: record ownership is intentionally not the list boundary.
    context = new AuthorizationContext();
    context.setUserId("reference-reader");
    context.setDataScopeType("NONE");
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    Class<?> repositoryType = Class.forName("org.simplepoint.plugin.i18n.api.repository.I18n"
        + endpoint.name() + "Repository");
    Object repository = mock(repositoryType,
        withSettings().defaultAnswer(delegatesTo(new BaseRepositoryImpl(endpoint.entity(), session))));
    Class<?> serviceType = Class.forName("org.simplepoint.plugin.i18n.service.impl.I18n"
        + endpoint.name() + "ServiceImpl");
    var constructor = serviceType.getConstructors()[0];
    var types = constructor.getParameterTypes();
    Object[] args = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      args[i] = types[i] == repositoryType ? repository
          : types[i] == DetailsProviderService.class ? schemaProvider() : mock(types[i]);
    }
    Object service = constructor.newInstance(args);
    Class<?> controllerType = Class.forName("org.simplepoint.plugin.i18n.rest.controller.I18n"
        + endpoint.controller() + "Controller");
    path = controllerType.getAnnotation(RequestMapping.class).value()[0];
    mvc = MockMvcBuilders.standaloneSetup(controllerType.getConstructors()[0].newInstance(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
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

  private void seed(Endpoint endpoint, String label, int index, boolean deleted) throws Exception {
    Object entity = endpoint.entity().getConstructor().newInstance();
    var bean = new BeanWrapperImpl(entity);
    for (var field : endpoint.entity().getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())) continue;
      Class<?> type = field.getType();
      Object value = type == String.class ? "v" + index : type == Integer.class ? index
          : type == Boolean.class ? Boolean.TRUE : null;
      if (value != null) bean.setPropertyValue(field.getName(), value);
    }
    bean.setPropertyValue(endpoint.field(), label);
    bean.setPropertyValue("createdBy", "other-owner");
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
  void advancedHeaderAndMultipleSortsShareTheDatabaseCount(Endpoint endpoint) throws Exception {
    setup(endpoint);
    String document = """
        {"version":1,"where":{"op":"and","children":[
          {"op":"or","children":[{"field":"%s","op":"like","value":"mat"},
            {"field":"%s","op":"equals","value":"other"}]},
          {"op":"not","children":[{"field":"%s","op":"equals","value":"other"}]}]}}
        """.formatted(endpoint.field(), endpoint.field(), endpoint.field());
    for (int page = 0; page < 2; page++) {
      mvc.perform(get(path).param("$query", document).param("page", String.valueOf(page)).param("size", "1")
              .param("sort", endpoint.field() + ",desc", endpoint.orderField() + ",asc")
              .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
          .andExpect(jsonPath("$.content.length()").value(1))
          .andExpect(jsonPath("$.content[0]." + endpoint.orderField()).value("v" + (page + 1)));
    }
    mvc.perform(get(path).param("$query", document)
            .param(endpoint.orderField(), "{\"version\":1,\"where\":{\"field\":\"" + endpoint.orderField()
                + "\",\"op\":\"equals\",\"value\":\"v2\"}}")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0]." + endpoint.orderField()).value("v2"));
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void schemaAndLimitsDescribeOnlySupportedQueryCapabilities(Endpoint endpoint) throws Exception {
    setup(endpoint);
    mvc.perform(get(path + "/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema['x-query'].fields." + endpoint.field() + ".sortable").value(true))
        .andExpect(jsonPath("$.schema['x-query'].fields." + endpoint.orderField() + ".sortable").value(true))
        .andExpect(jsonPath("$.schema['x-query'].fields.createdBy").doesNotExist())
        .andExpect(jsonPath("$.schema['x-query'].fields.deletedAt").doesNotExist());
    for (String field : new String[] {"createdBy", "deletedAt", "unknownField"}) {
      mvc.perform(get(path).param("$query", "{\"version\":1,\"where\":{\"field\":\"" + field
              + "\",\"op\":\"is:null\"}}")
              .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }
    mvc.perform(get(path).param("size", "200")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
    mvc.perform(get(path).param("size", "201")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    mvc.perform(get(path).param("sort", "createdBy,asc")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
  }
}
