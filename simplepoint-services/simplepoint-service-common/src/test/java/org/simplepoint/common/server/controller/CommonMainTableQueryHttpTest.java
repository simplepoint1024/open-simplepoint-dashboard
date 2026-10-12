package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.security.entity.DataScope;
import org.simplepoint.security.entity.FieldAccessType;
import org.simplepoint.security.entity.FieldScope;
import org.simplepoint.security.entity.FieldScopeEntry;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;

/** Main Common lists tested through production controllers, query services, schemas and real SQL. */
class CommonMainTableQueryHttpTest {
  private static final String RBAC = "org.simplepoint.plugin.rbac.";
  private static final String OIDC = "org.simplepoint.plugin.oidc.";
  private SessionFactory factory;
  private Session session;
  private MockMvc mvc;
  private String path;
  private AuthorizationContext context;
  private Object firstRow;
  private final ObjectMapper mapper = new ObjectMapper();

  record Endpoint(String entityName, String serviceName, String controllerName, String label, String order) {
    Class<?> entity() throws Exception { return Class.forName(entityName); }
    String name() { return entityName.substring(entityName.lastIndexOf('.') + 1); }
    boolean tenantScoped() throws Exception { return TenantBaseEntityImpl.class.isAssignableFrom(entity()); }
  }

  static Stream<Endpoint> endpoints() {
    return Stream.of(
        new Endpoint("org.simplepoint.security.entity.Role", RBAC + "core.service.impl.RolesServiceImpl",
            RBAC + "core.rest.controller.RoleController", "roleName", "authority"),
        new Endpoint("org.simplepoint.security.entity.DataScope", RBAC + "core.service.impl.DataScopeServiceImpl",
            RBAC + "core.rest.controller.DataScopeController", "name", "description"),
        new Endpoint("org.simplepoint.security.entity.FieldScope", RBAC + "core.service.impl.FieldScopeServiceImpl",
            RBAC + "core.rest.controller.FieldScopeController", "name", "description"),
        dictionary("Dictionary", "code"), dictionary("DictionaryItem", "value"),
        new Endpoint("org.simplepoint.security.entity.MicroModule", RBAC + "resource.service.impl.RemoteModuleServiceImpl",
            RBAC + "resource.rest.endpoint.MicroAppController", "displayName", "serviceName"),
        new Endpoint(OIDC + "api.entity.ExternalIdentityProvider", OIDC + "service.service.impl.ExternalIdentityProviderServiceImpl",
            OIDC + "rest.controller.ExternalIdentityProviderController", "displayName", "registrationId"),
        new Endpoint(OIDC + "api.entity.Client", OIDC + "service.service.impl.OidcClientServiceImpl",
            OIDC + "rest.controller.OidcClientController", "clientName", "clientId"),
        new Endpoint("org.simplepoint.plugin.notification.api.entity.SystemNotification",
            "org.simplepoint.plugin.notification.service.impl.NotificationManagementServiceImpl",
            "org.simplepoint.plugin.notification.rest.controller.NotificationManagementController", "title", "content"));
  }

  static Endpoint dictionary(String name, String order) {
    return new Endpoint(RBAC + "tenant.api.entity." + name, RBAC + "tenant.service.impl." + name + "ServiceImpl",
        RBAC + "tenant.rest.controller." + name + "Controller", "name", order);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void setup(Endpoint endpoint) throws Exception {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", Optional.ofNullable(System.getenv("COMMON_MAIN_DB_URL"))
            .filter(value -> !value.isBlank()).orElseGet(() -> "jdbc:h2:mem:common-main-" + UUID.randomUUID()))
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.hbm2ddl.halt_on_error", true)
        .applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
        .build();
    var sources = new MetadataSources(registry).addAnnotatedClass(endpoint.entity());
    if (endpoint.entity() == FieldScope.class) sources.addAnnotatedClass(FieldScopeEntry.class);
    factory = sources.buildMetadata().buildSessionFactory();
    session = factory.openSession(); session.beginTransaction();
    seed(endpoint, "match", 1, false, "tenant-a");
    seed(endpoint, "match", 2, false, "tenant-a");
    seed(endpoint, "other", 3, false, "tenant-a");
    seed(endpoint, "match", 4, true, "tenant-a");
    if (endpoint.tenantScoped()) seed(endpoint, "match", 5, false, "outside");
    session.flush(); session.clear();
    // Compare persisted values, including database timestamp precision, not pre-flush nanoseconds.
    firstRow = session.find(endpoint.entity(), new BeanWrapperImpl(firstRow).getPropertyValue("id"));
    context = new AuthorizationContext(); context.setUserId("reader"); context.setIsAdministrator(false);
    context.setDataScopeType("NONE"); context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    var buttons = endpoint.entity().getAnnotation(org.simplepoint.core.annotation.ButtonDeclarations.class);
    if (buttons != null) context.setResources(Arrays.stream(buttons.value()).map(button -> button.authority()).toList());
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var constructor = Class.forName(endpoint.serviceName()).getConstructors()[0];
    var types = constructor.getParameterTypes();
    var target = mock(types[0], withSettings().defaultAnswer(delegatesTo(new BaseRepositoryImpl(endpoint.entity(), session))));
    var proxy = new ProxyFactory(target);
    proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect()));
    Object[] args = new Object[types.length]; args[0] = proxy.getProxy();
    for (int i = 1; i < types.length; i++) {
      args[i] = types[i] == DetailsProviderService.class ? schemaProvider() : mock(types[i]);
    }
    Object service = constructor.newInstance(args);
    var controller = Class.forName(endpoint.controllerName());
    path = controller.getAnnotation(RequestMapping.class).value()[0];
    mvc = MockMvcBuilders.standaloneSetup(controller.getConstructors()[0].newInstance(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new JacksonJsonHttpMessageConverter(tools.jackson.databind.json.JsonMapper.builder()
            .addModule(new org.simplepoint.core.jackson.FieldScopeJackson3Module()).build()))
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
  }

  private DetailsProviderService schemaProvider() {
    var provider = mock(DetailsProviderService.class);
    var config = new JsonSchemaAutoConfiguration(mapper);
    var generator = config.schemaGenerator(Set.of(config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()));
    when(provider.getDialect(JsonSchemaGenerator.class)).thenReturn(config.defaultJsonSchemaGenerator(generator));
    when(provider.getDialect(JsonSchemaDetailsService.class)).thenReturn(mock(JsonSchemaDetailsService.class));
    return provider;
  }

  private void seed(Endpoint endpoint, String label, int index, boolean deleted, String tenant) throws Exception {
    Object entity = endpoint.entity().getConstructor().newInstance();
    var bean = new BeanWrapperImpl(entity);
    for (var field : endpoint.entity().getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())) continue;
      Class<?> type = field.getType();
      Object value = type == String.class ? "v" + index : type == Integer.class ? index
          : type == Boolean.class ? Boolean.TRUE : type.isEnum() ? type.getEnumConstants()[0]
          : type == Instant.class ? Instant.parse("2026-09-19T00:00:00Z").plusSeconds(index) : null;
      if (value != null) bean.setPropertyValue(field.getName(), value);
    }
    bean.setPropertyValue(endpoint.label(), label);
    bean.setPropertyValue("createdBy", "not-the-reader");
    if (endpoint.tenantScoped()) bean.setPropertyValue("tenantId", tenant);
    if (endpoint.name().equals("DictionaryItem")) bean.setPropertyValue("dictionaryCode", index == 3 ? "parent-b" : "parent-a");
    if (entity instanceof DataScope scope) scope.setCustomDeptIds(Set.of("dept-" + index));
    if (deleted) bean.setPropertyValue("deletedAt", Instant.now());
    session.persist(entity);
    if (index == 1) firstRow = entity;
    if (entity instanceof FieldScope scope) {
      var entry = new FieldScopeEntry(); entry.setFieldScopeId(scope.getId()); entry.setTenantId(tenant);
      entry.setResource("Role"); entry.setField("roleName"); entry.setAccess(FieldAccessType.VISIBLE);
      session.persist(entry);
    }
  }

  private MockHttpServletRequestBuilder query() {
    return get(path).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (session != null) { if (session.getTransaction().isActive()) session.getTransaction().rollback(); session.close(); }
    if (factory != null) factory.close();
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void advancedHeaderAndSortIntersectBeforePagingAndCounting(Endpoint endpoint) throws Exception {
    setup(endpoint);
    String document = """
        {"version":1,"where":{"op":"and","children":[
          {"op":"or","children":[{"field":"%s","op":"like","value":"mat"},
            {"field":"%s","op":"equals","value":"other"}]},
          {"op":"not","children":[{"field":"%s","op":"equals","value":"other"}]}]}}
        """.formatted(endpoint.label(), endpoint.label(), endpoint.label());
    for (int page = 0; page < 2; page++) {
      mvc.perform(query().param("$query", document).param("size", "1").param("page", String.valueOf(page))
              .param("sort", endpoint.label() + ",desc", endpoint.order() + ",asc"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
          .andExpect(jsonPath("$.content.length()").value(1))
          .andExpect(jsonPath("$.content[0]." + endpoint.order()).value("v" + (page + 1)));
    }
    mvc.perform(query().param("$query", document).param(endpoint.order(), """
            {"version":1,"where":{"field":"%s","op":"equals","value":"v2"}}
            """.formatted(endpoint.order())))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void actualSchemaRejectsUnsupportedSensitiveAndPermissionRestrictedQueries(Endpoint endpoint) throws Exception {
    setup(endpoint);
    var response = mvc.perform(get(path + "/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema['x-query'].fields." + endpoint.label() + ".sortable").value(true))
        .andExpect(jsonPath("$.schema['x-query'].fields." + endpoint.order() + ".sortable").value(true))
        .andExpect(jsonPath("$.schema['x-query'].fields.createdBy").doesNotExist())
        .andExpect(jsonPath("$.schema['x-query'].fields.deletedAt").doesNotExist()).andReturn();
    String json = response.getResponse().getContentAsString(StandardCharsets.UTF_8);
    var output = Path.of("build/generated-test-resources/common-main/" + endpoint.name() + ".json");
    Files.createDirectories(output.getParent()); Files.writeString(output, json);
    if (!Boolean.getBoolean("simplepoint.query.exportSchemas")) {
      try (var fixture = getClass().getResourceAsStream("/query-contracts/common-main/" + endpoint.name() + ".json")) {
        assertNotNull(fixture);
        assertEquals(mapper.readTree(fixture), mapper.readTree(json), "Browser contract must match actual production HTTP schema");
      }
    }
    var forbidden = new ArrayList<>(List.of("unknownField", "createdBy", "deletedAt", "tenantId"));
    if (endpoint.name().equals("ExternalIdentityProvider")) forbidden.addAll(List.of("clientSecret", "clientSecretCiphertext", "clientSecretConfigured"));
    if (endpoint.name().equals("Client")) forbidden.addAll(List.of("clientSecret", "clientSettings", "tokenSettings"));
    if (endpoint.name().equals("SystemNotification")) forbidden.add("version");
    if (endpoint.name().equals("MicroModule")) forbidden.addAll(List.of("pluginId", "pluginVersion", "remoteVersion", "pluginArtifactSha256"));
    if (endpoint.name().equals("DataScope")) forbidden.add("customDeptIds");
    if (endpoint.name().equals("FieldScope")) forbidden.add("entries");
    for (String field : forbidden) {
      mvc.perform(query().param("$query", "{\"version\":1,\"where\":{\"field\":\"" + field + "\",\"op\":\"is:null\"}}"))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
      mvc.perform(query().param("sort", field + ",asc")).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }
    mvc.perform(query().param("size", "200")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
    mvc.perform(query().param("size", "201")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    for (String permission : List.of("HIDDEN", "MASKED")) {
      var restricted = new AuthorizationContext();
      restricted.setUserId("reader"); restricted.setIsAdministrator(false); restricted.setDataScopeType("NONE");
      restricted.setAttributes(Map.of("X-Tenant-Id", "tenant-a")); restricted.setResources(context.getResources());
      restricted.setFieldPermissions(Map.of(endpoint.entityName() + "#" + endpoint.label(), permission));
      context = restricted;
      RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
      mvc.perform(get(path + "/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isOk()).andExpect(jsonPath("$.schema['x-query'].fields." + endpoint.label()).doesNotExist());
      mvc.perform(query().param(endpoint.label(), "like:match")).andExpect(status().isBadRequest());
      mvc.perform(query().param("sort", endpoint.label() + ",asc")).andExpect(status().isBadRequest());
      var page = mvc.perform(query()).andExpect(status().isOk());
      if (permission.equals("HIDDEN")) page.andExpect(jsonPath("$.content[0]." + endpoint.label()).doesNotExist());
      else page.andExpect(jsonPath("$.content[0]." + endpoint.label()).value("***"));
    }
  }

  @Test
  void notificationLegacyTextAndStructuredHeadersAreNotRewrittenIntoEachOther() throws Exception {
    setup(endpoints().filter(endpoint -> endpoint.name().equals("SystemNotification")).findFirst().orElseThrow());
    for (String value : List.of("mat", "like:mat", "equals:match", "in:match")) {
      mvc.perform(query().param("title", value)).andExpect(status().isOk())
          .andExpect(jsonPath("$.totalElements").value(2));
    }
    mvc.perform(query().param("title", "not:equals:match")).andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].title").value("other"));
    mvc.perform(query().param("title", "{\"version\":1,\"where\":{\"field\":\"title\",\"op\":\"equals\",\"value\":\"other\"}}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(query().param("title", "{}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
  }

  @Test
  void oauthReadResponsesNeverReturnSecretsAndInputStillAcceptsSecretUpdates() throws Exception {
    setup(endpoints().filter(endpoint -> endpoint.name().equals("Client")).findFirst().orElseThrow());
    mvc.perform(query()).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].clientSecret").doesNotExist());
    var client = (org.simplepoint.plugin.oidc.api.entity.Client) firstRow;
    client.setClientSettings(org.simplepoint.plugin.oidc.service.SecurityJacksonParse.writeMap(
        org.springframework.security.oauth2.server.authorization.settings.ClientSettings.builder().build().getSettings()));
    client.setTokenSettings(org.simplepoint.plugin.oidc.service.SecurityJacksonParse.writeMap(
        org.springframework.security.oauth2.server.authorization.settings.TokenSettings.builder().build().getSettings()));
    session.flush();
    mvc.perform(get(path + "/" + client.getId() + "/configuration")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.clientSecret").doesNotExist());
    var httpMapper = tools.jackson.databind.json.JsonMapper.builder()
        .addModule(new org.simplepoint.core.jackson.FieldScopeJackson3Module()).build();
    assertEquals("new-secret", httpMapper.readValue("{\"clientSecret\":\"new-secret\"}",
        org.simplepoint.plugin.oidc.api.pojo.dto.OidcClientConfigurationDto.class).getClientSecret());
    assertEquals("new-secret", httpMapper.readValue("{\"clientSecret\":\"new-secret\"}",
        org.simplepoint.plugin.oidc.api.entity.Client.class).getClientSecret());
    assertEquals("v1", client.getClientSecret(), "list projection never mutates persisted credentials");
  }

  static Stream<Endpoint> collectionEndpoints() {
    return endpoints().filter(endpoint -> Set.of("DataScope", "FieldScope").contains(endpoint.name()));
  }

  @ParameterizedTest
  @MethodSource("collectionEndpoints")
  void selectedPolicyIdentityLookupRetainsTenantAndSoftDeleteRestrictions(Endpoint endpoint) throws Exception {
    setup(endpoint);
    var rows = session.createQuery("from " + endpoint.entity().getSimpleName(), endpoint.entity()).getResultList();
    for (var row : rows) {
      var bean = new BeanWrapperImpl(row);
      String id = (String) bean.getPropertyValue("id");
      boolean allowed = bean.getPropertyValue("deletedAt") == null && "tenant-a".equals(bean.getPropertyValue("tenantId"));
      mvc.perform(query().param("id", "{\"version\":1,\"where\":{\"field\":\"id\",\"op\":\"equals\",\"value\":\"" + id + "\"}}").param("size", "1"))
          .andExpect(result -> assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString()))
          .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(allowed ? 1 : 0));
    }
  }

  @ParameterizedTest
  @MethodSource("endpoints")
  void everyDeclaredScalarCanBeQueriedAndSortedByItsActualType(Endpoint endpoint) throws Exception {
    setup(endpoint);
    var row = new BeanWrapperImpl(firstRow);
    for (var field : endpoint.entity().getAnnotation(org.simplepoint.core.query.TableQuery.class).value()) {
      Object value = row.getPropertyValue(field.value());
      String json = mapper.writeValueAsString(Map.of("version", 1, "where", value == null
          ? Map.of("field", field.value(), "op", "is:null")
          : Map.of("field", field.value(), "op", value instanceof String ? "like" : "equals",
              "value", value instanceof Enum<?> || value instanceof Instant ? value.toString() : value)));
      mvc.perform(query().param("$query", json).param("sort", field.value() + ",desc"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThan(0)));
    }
  }

  @ParameterizedTest
  @MethodSource("collectionEndpoints")
  void eagerPolicyCollectionsAreBatchedAndPreservedForEditing(Endpoint endpoint) throws Exception {
    setup(endpoint);
    for (int i = 6; i < 31; i++) seed(endpoint, "batch-" + i, i, false, "tenant-a");
    session.flush(); session.clear(); factory.getStatistics().clear();
    String collection = endpoint.name().equals("DataScope") ? "customDeptIds" : "entries";
    mvc.perform(query().param("size", "20"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(28))
        .andExpect(jsonPath("$.content.length()").value(20))
        .andExpect(jsonPath("$.content[0]." + collection + ".length()").value(1));
    assertTrue(factory.getStatistics().getPrepareStatementCount() <= 3,
        "page + count + one collection batch, never one query per policy");
  }

  @Test
  void dictionaryParentCannotBeRemovedByViewsOrConflictingUserConditions() throws Exception {
    setup(dictionary("DictionaryItem", "value"));
    mvc.perform(query().param("parentDictionaryCode", "parent-a").param("size", "1").param("page", "1").param("sort", "value,asc"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.content[0].value").value("v2"));
    mvc.perform(query().param("parentDictionaryCode", "parent-a").param("dictionaryCode", "equals:parent-b"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(query().param("parentDictionaryCode", "parent-a").param("$query", """
        {"version":1,"where":{"op":"or","children":[
          {"field":"dictionaryCode","op":"equals","value":"parent-b"},
          {"field":"value","op":"equals","value":"v2"}]}}
        """))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].value").value("v2"));
    mvc.perform(query().param("parentDictionaryCode", "parent-b"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(query().param("parentDictionaryCode", ""))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    mvc.perform(query().param("parentDictionaryCode", "a".repeat(129)))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
  }

  @Test
  void providerPageNeverSerializesStoredCredentials() throws Exception {
    setup(endpoints().filter(endpoint -> endpoint.name().equals("ExternalIdentityProvider")).findFirst().orElseThrow());
    mvc.perform(query()).andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].clientSecret").doesNotExist())
        .andExpect(jsonPath("$.content[0].clientSecretCiphertext").doesNotExist())
        .andExpect(jsonPath("$.content[0].clientSecretConfigured").value(true));
  }

  @Test
  void textLobQueriesUseWholeContentAndContentOrderRatherThanObjectIdentifiers() throws Exception {
    setup(endpoints().filter(endpoint -> endpoint.name().equals("MicroModule")).findFirst().orElseThrow());
    String text = "z".repeat(700) + "needle_%\\中文";
    var rows = session.createQuery("from MicroModule order by serviceName", org.simplepoint.security.entity.MicroModule.class).getResultList();
    rows.get(0).setDescription(text); rows.get(1).setDescription("alpha"); rows.get(2).setDescription("middle");
    session.flush(); session.clear();
    mvc.perform(query().param("description", "like:needle_%\\中文"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].description").value(text));
    for (String op : List.of("equals", "in")) {
      String document = mapper.writeValueAsString(Map.of("version", 1,
          "where", Map.of("field", "description", "op", op, "value", op.equals("in") ? List.of(text) : text)));
      mvc.perform(query().param("$query", document)).andExpect(status().isOk())
          .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].description").value(text));
    }
    mvc.perform(query().param("sort", "description,asc").param("size", "1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].serviceName").value("v2"))
        .andExpect(jsonPath("$.totalElements").value(3));
    mvc.perform(query().param("description", "not:like:needle_%\\中文").param("sort", "description,desc").param("size", "1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].serviceName").value("v3"))
        .andExpect(jsonPath("$.totalElements").value(2));
  }
}
