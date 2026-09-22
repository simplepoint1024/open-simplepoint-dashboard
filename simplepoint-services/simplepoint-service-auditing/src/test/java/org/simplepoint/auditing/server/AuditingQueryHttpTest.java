package org.simplepoint.auditing.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
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
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real audit controllers/services and SQL execution; no business database or Redis writes. */
class AuditingQueryHttpTest {
  private SessionFactory factory;
  private Session session;
  private MockMvc mvc;
  private AuthorizationContext context;

  record Endpoint(String module, String name, String path, String field) {
    Class<?> type(String suffix) throws ClassNotFoundException {
      return Class.forName("org.simplepoint.plugin.auditing." + module + "." + suffix);
    }

    Class<?> entity() throws ClassNotFoundException {
      return type("api.entity." + name);
    }
  }

  static Stream<Endpoint> endpoints() {
    return Stream.of(
        new Endpoint("logging", "LoginLog", "/logging/login-logs", "username"),
        new Endpoint("logging", "ErrorLog", "/logging/error-logs", "message"),
        new Endpoint("logging", "ResourceGrantLog", "/logging/resource-grant-logs", "subjectLabel"),
        new Endpoint("ratelimit", "ServiceRateLimitRule", "/rate-limit/service-rules", "name"),
        new Endpoint("ratelimit", "EndpointRateLimitRule", "/rate-limit/endpoint-rules", "name"));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  void setup(Endpoint endpoint) throws Exception {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:audit-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
    factory = new MetadataSources(registry).addAnnotatedClass(endpoint.entity())
        .buildMetadata().buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    seed(endpoint, "match-a", 1, false);
    seed(endpoint, "match-b", 2, false);
    seed(endpoint, "other", 3, false);
    seed(endpoint, "match-deleted", 4, true);
    session.flush();
    var repository = mock(endpoint.type("api.repository." + endpoint.name() + "Repository"),
        withSettings().defaultAnswer(delegatesTo(new BaseRepositoryImpl(endpoint.entity(), session))));
    var constructor = endpoint.type("service.impl." + endpoint.name() + "ServiceImpl").getConstructors()[0];
    // Details provider and (rate limit only) Redis publisher are not used by read operations.
    Object[] arguments = new Object[constructor.getParameterCount()];
    arguments[0] = repository;
    Object service = constructor.newInstance(arguments);
    Object controller = endpoint.type("rest.controller." + endpoint.name() + "Controller")
        .getConstructors()[0].newInstance(service);
    mvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
    context = new AuthorizationContext();
    context.setIsAdministrator(true);
    context.setDataScopeType("ALL");
  }

  private void seed(Endpoint endpoint, String label, int index, boolean deleted) throws Exception {
    Object entity = endpoint.entity().getConstructor().newInstance();
    var bean = new BeanWrapperImpl(entity);
    // Populate these scalar-only fixture entities, including their non-null/unique columns.
    for (var field : endpoint.entity().getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())) continue;
      Object value = field.getType() == String.class ? "fixture-" + index
          : field.getType() == Instant.class ? Instant.parse("2026-09-19T00:00:00Z")
          : field.getType() == Integer.class ? index
          : field.getType() == Long.class ? (long) index
          : field.getType() == Boolean.class ? Boolean.TRUE : null;
      if (value != null) bean.setPropertyValue(field.getName(), value);
    }
    bean.setPropertyValue(endpoint.field(), label);
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
  void structuredPredicatesSortingAndCountExecuteBeforePagination(Endpoint endpoint) throws Exception {
    setup(endpoint);
    String document = """
        {"version":1,"where":{"op":"and","children":[
          {"op":"or","children":[{"field":"%s","op":"like","value":"MATCH"},
            {"field":"%s","op":"equals","value":"other"}]},
          {"op":"not","children":[{"field":"%s","op":"equals","value":"other"}]}]}}
        """.formatted(endpoint.field(), endpoint.field(), endpoint.field());
    for (int page = 0; page < 2; page++) {
      String secondary = endpoint.module().equals("logging")
          ? QueryCapabilities.declaration(endpoint.entity()).value()[0].value() : "serviceId";
      mvc.perform(get(endpoint.path()).param("$query", document)
              .param("page", String.valueOf(page)).param("size", "1")
              .param("sort", endpoint.field() + ",desc", secondary + ",asc")
              .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.totalElements").value(2))
          .andExpect(jsonPath("$.content.length()").value(1))
          .andExpect(jsonPath("$.content[0]." + endpoint.field()).value(page == 0 ? "match-b" : "match-a"));
    }
    mvc.perform(get(endpoint.path())
            .param(endpoint.field(), "{\"version\":1,\"where\":{\"field\":\"" + endpoint.field()
                + "\",\"op\":\"equals\",\"value\":\"match-a\"}}")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void schemaWhitelistAndInvalidQueriesAgree(Endpoint endpoint) throws Exception {
    setup(endpoint);
    var schema = new ObjectMapper().createObjectNode();
    QueryCapabilities.applySchema(endpoint.entity(), schema);
    assertTrue(schema.path("x-query").path("fields").has(endpoint.field()));
    assertFalse(schema.path("x-query").path("fields").has("deletedAt"));
    for (var field : endpoint.entity().getDeclaredFields()) {
      var declaration = field.getAnnotation(Schema.class);
      if (declaration == null || declaration.hidden()) continue;
      boolean visible = Arrays.stream(declaration.extensions()).flatMap(extension -> Arrays.stream(extension.properties()))
          .anyMatch(property -> property.name().equals("x-list-visible") && property.value().equals("true"));
      if (visible) assertTrue(schema.path("x-query").path("fields").has(field.getName()), field.getName());
    }
    for (String field : new String[] {"deletedAt", "unknownField"}) {
      mvc.perform(get(endpoint.path()).param("$query",
              "{\"version\":1,\"where\":{\"field\":\"" + field + "\",\"op\":\"is:null\"}}")
              .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }
    mvc.perform(get(endpoint.path()).param("sort", "unknownField,desc")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
  }
}
