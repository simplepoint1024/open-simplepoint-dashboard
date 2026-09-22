package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.*;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.core.rest.controller.PlatformAccountController;
import org.simplepoint.plugin.rbac.core.service.impl.PlatformAccountServiceImpl;
import org.simplepoint.plugin.rbac.core.service.impl.PlatformStepUpService;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.*;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real platform authorization, DTO SQL projection, query schemas and HTTP field policies. */
class PlatformAccountQueryHttpTest {
  private static final String BASE = "/platform/accounts";
  private SessionFactory factory;
  private Session em;
  private MockMvc mvc;
  private AuthorizationContext context;
  private User actor;
  private final List<User> users = new ArrayList<>();
  private final List<PlatformSecurityAudit> audits = new ArrayList<>();
  private final List<String> statements = new ArrayList<>();
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setup() {
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting(
                "hibernate.connection.url",
                Optional.ofNullable(System.getenv("PLATFORM_QUERY_DB_URL"))
                    .filter(value -> !value.isBlank())
                    .orElseGet(() -> "jdbc:h2:mem:platform-account-query-" + UUID.randomUUID()))
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .applySetting("hibernate.hbm2ddl.halt_on_error", true)
            .applySetting("hibernate.generate_statistics", true)
            .applySetting(
                "hibernate.session_factory.statement_inspector",
                (org.hibernate.resource.jdbc.spi.StatementInspector)
                    sql -> {
                      statements.add(sql);
                      return sql;
                    })
            .applySetting(
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
            .build();
    factory =
        new MetadataSources(registry)
            .addAnnotatedClass(User.class)
            .addAnnotatedClass(PlatformIdentity.class)
            .addAnnotatedClass(PlatformSecurityAudit.class)
            .buildMetadata()
            .buildSessionFactory();
    em = factory.openSession();
    em.beginTransaction();
    actor = user("Root", -1);
    actor.setSuperAdmin(true);
    for (int i = 0; i < 240; i++) {
      var user = user("Account" + String.format("%03d", i), i);
      users.add(user);
      var identity = new PlatformIdentity();
      identity.setUserId(user.getId());
      identity.setRoles(
          i % 2 == 0
              ? Set.of(PlatformRole.ACCOUNT_ADMIN, PlatformRole.AUDITOR)
              : Set.of(PlatformRole.AUDITOR));
      em.persist(identity);
      var audit = new PlatformSecurityAudit();
      audit.setActorId(actor.getId());
      audit.setTargetId(user.getId());
      audit.setAction("ACTION" + String.format("%03d", i));
      audit.setReason("Reason " + i);
      audit.setBeforeState("Before " + i);
      audit.setAfterState("After " + i);
      audit.setOccurredAt(Instant.parse("2026-09-19T00:00:00Z").plusSeconds(i));
      em.persist(audit);
      audits.add(audit);
    }
    user("Account999", 999).setDeletedAt(Instant.now());
    em.flush();
    em.clear();
    var details = mock(DetailsProviderService.class);
    var config = new JsonSchemaAutoConfiguration(mapper);
    var generator =
        config.schemaGenerator(
            Set.of(config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()));
    when(details.getDialect(JsonSchemaGenerator.class))
        .thenReturn(config.defaultJsonSchemaGenerator(generator));
    when(details.getDialect(JsonSchemaDetailsService.class))
        .thenReturn(mock(JsonSchemaDetailsService.class));
    var service =
        new PlatformAccountServiceImpl(
            em,
            mock(UsersService.class),
            mock(PlatformStepUpService.class),
            mock(TenantRepository.class),
            mock(ResourceAuthorizationVersionService.class),
            details);
    mvc =
        MockMvcBuilders.standaloneSetup(new PlatformAccountController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new JacksonJsonHttpMessageConverter())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    authorize(actor.getId(), AuthorizationScopeType.PLATFORM, Map.of());
  }

  private User user(String name, int revision) {
    var user = new User();
    user.setName(name);
    user.setEmail(name + "@example.test");
    user.setPassword("fixture-password-must-not-be-selected");
    user.setAuthorizationVersion((long) revision);
    user.setEnabled(revision < 0 || revision % 2 == 0);
    em.persist(user);
    return user;
  }

  private void authorize(String userId, AuthorizationScopeType scope, Map<String, String> fields) {
    context = new AuthorizationContext();
    context.setUserId(userId);
    context.setScopeType(scope);
    context.setIsAdministrator(true);
    context.setActorRole(AuthorizationActorRole.PLATFORM_ADMIN);
    context.setResources(
        Set.of(
            "platform.accounts.view",
            "platform.accounts.create",
            "platform.accounts.edit",
            "platform.audit.view",
            "platform.identity.manage"));
    context.setFieldPermissions(fields);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private MockHttpServletRequestBuilder query(String suffix) {
    return get(BASE + suffix).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private String condition(String field, String op, Object value) throws Exception {
    return mapper.writeValueAsString(
        Map.of("version", 1, "where", Map.of("field", field, "op", op, "value", value)));
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    if (em != null) {
      if (em.getTransaction().isActive()) em.getTransaction().rollback();
      em.close();
    }
    if (factory != null) factory.close();
  }

  @Test
  void structuredBooleanAndHeaderQueriesRunBeforeAccountPagingAndCount() throws Exception {
    String document =
        """
        {"version":1,"where":{"op":"and","children":[
          {"op":"or","children":[{"field":"name","op":"equals","value":" ACCOUNT000 "},
            {"field":"name","op":"equals","value":"account238"}]},
          {"op":"not","children":[{"field":"enabled","op":"equals","value":false}]}]}}
        """;
    for (int page = 0; page < 2; page++) {
      mvc.perform(
              query("")
                  .param("$query", document)
                  .param("page", "" + page)
                  .param("size", "1")
                  .param("sort", "enabled,asc", "revision,desc"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.totalElements").value(2))
          .andExpect(jsonPath("$.content[0].revision").value(page == 0 ? 238 : 0))
          .andExpect(jsonPath("$.content[0].password").doesNotExist())
          .andExpect(jsonPath("$.content[0].roles.length()").value(2));
    }
    mvc.perform(
            query("")
                .param("$query", document)
                .param("name", condition("name", "equals", "account238")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(query("").param("name", "equals:ACCOUNT239").param("enabled", "equals:false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(
            query("").param("$query", condition("name", "in", List.of("account000", "ACCOUNT999"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(
            query("")
                .param("keyword", "ACCOUNT238")
                .param("$query", condition("revision", "equals", 239)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void auditAdvancedTimeWindowsAndSortShareTheCount() throws Exception {
    mvc.perform(
            query("/audit")
                .param(
                    "$query",
                    condition(
                        "occurredAt",
                        "between",
                        List.of("2026-09-19T00:03:58Z", "2026-09-19T00:03:59Z")))
                .param("action", "like:action")
                .param("sort", "actorId,asc", "occurredAt,desc")
                .param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].action").value("ACTION239"));
    mvc.perform(
            query("/audit")
                .param("$query", condition("action", "in", List.of("action239", "ACTION238")))
                .param("reason", condition("reason", "equals", "REASON 238")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(query("/audit").param("action", "like:%"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void rolesAreLoadedInOneBoundedPageBatchAndAccountsAreNotHydrated() throws Exception {
    for (int size : List.of(20, 50)) {
      em.clear();
      factory.getStatistics().clear();
      statements.clear();
      mvc.perform(
              query("")
                  .param("name", "like:account")
                  .param("size", "" + size)
                  .param("sort", "revision,desc"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.totalElements").value(240))
          .andExpect(jsonPath("$.content.length()").value(size))
          .andExpect(jsonPath("$.content[0].name").value("Account239"));
      assertTrue(
          factory.getStatistics().getPrepareStatementCount() <= 5,
          "actor find+refresh, page, count, role batch; independent of page size");
      assertTrue(
          factory.getStatistics().getEntityLoadCount() == 2,
          "only the authorization actor is loaded and refreshed, independent of page size");
      assertTrue(
          statements.stream()
              .filter(sql -> sql.contains(" offset "))
              .allMatch(sql -> !sql.contains("password")),
          "paged account projection never selects credentials");
    }
  }

  @Test
  void sourcePermissionsConstrainSchemaQueryAndActualResponse() throws Exception {
    authorize(
        actor.getId(),
        AuthorizationScopeType.PLATFORM,
        Map.of(
            "User#name",
            "HIDDEN",
            "User#superAdmin",
            "MASKED",
            "User#authorizationVersion",
            "MASKED",
            "PlatformIdentity#roles",
            "MASKED"));
    mvc.perform(query("/schema"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema.properties.name").doesNotExist())
        .andExpect(jsonPath("$.schema['x-query'].fields.name").doesNotExist())
        .andExpect(jsonPath("$.schema['x-query'].fields.revision").doesNotExist());
    mvc.perform(query(""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").doesNotExist())
        .andExpect(jsonPath("$.content[0].superAdmin").value("***"))
        .andExpect(jsonPath("$.content[0].revision").value("***"))
        .andExpect(jsonPath("$.content[0].roles").value("***"));
    for (var request :
        List.of(
            query("").param("name", "like:account"),
            query("").param("keyword", "account"),
            query("").param("sort", "revision,asc"),
            query("").param("$query", condition("superAdmin", "equals", true)))) {
      mvc.perform(request)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }
    authorize(
        actor.getId(),
        AuthorizationScopeType.PLATFORM,
        Map.of(
            "PlatformSecurityAudit#actorId", "HIDDEN", "PlatformSecurityAudit#reason", "MASKED"));
    mvc.perform(query("/audit"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].actorId").doesNotExist())
        .andExpect(jsonPath("$.content[0].reason").value("***"));
    mvc.perform(query("/audit").param("sort", "actorId,asc")).andExpect(status().isBadRequest());
  }

  @Test
  void onlyPersistedPlatformIdentityAuthorizesReadsAndRevocationIsImmediate() throws Exception {
    authorize(actor.getId(), AuthorizationScopeType.TENANT, Map.of());
    denied("");
    authorize(users.get(1).getId(), AuthorizationScopeType.PLATFORM, Map.of());
    em.find(User.class, users.get(1).getId()).setEnabled(true);
    em.flush();
    mvc.perform(query("/audit")).andExpect(status().isOk());
    denied("");
    var identity = em.find(PlatformIdentity.class, users.get(1).getId());
    identity.getRoles().clear();
    em.flush();
    denied("/audit");
    authorize(actor.getId(), AuthorizationScopeType.PLATFORM, Map.of());
    em.find(User.class, actor.getId()).setEnabled(false);
    em.flush();
    denied("");
  }

  private void denied(String suffix) {
    // Standalone MVC intentionally excludes the servlet security chain that translates this to 403.
    var error =
        assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(query(suffix)));
    assertInstanceOf(
        org.springframework.security.access.AccessDeniedException.class, error.getCause());
  }

  @Test
  void invalidFieldsTypesOperatorsAndBudgetsFailSafely() throws Exception {
    for (String suffix : List.of("", "/audit")) {
      for (String field :
          List.of(
              "password",
              "twoFactorSecret",
              "decorator",
              "deletedAt",
              "tenantId",
              "roles",
              "unknown")) {
        mvc.perform(query(suffix).param("$query", condition(field, "equals", "secret")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
      }
      mvc.perform(query(suffix).param("size", "51")).andExpect(status().isBadRequest());
      mvc.perform(query(suffix).param("page", "100001")).andExpect(status().isBadRequest());
      mvc.perform(query(suffix).param("sort", "id,asc", "id,desc"))
          .andExpect(status().isBadRequest());
      mvc.perform(
              query(suffix)
                  .param("$query", condition("id", "in", java.util.Collections.nCopies(101, "a"))))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(query("").param("enabled", "equals:not-a-boolean"))
        .andExpect(status().isBadRequest());
    mvc.perform(query("").param("revision", condition("revision", "equals", "not-a-number")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void everyDeclaredScalarCompilesAgainstItsActualPersistedType() throws Exception {
    for (String suffix : List.of("", "/audit")) {
      var schema = mvc.perform(query(suffix + "/schema")).andExpect(status().isOk()).andReturn();
      var fields =
          mapper
              .readTree(schema.getResponse().getContentAsString(StandardCharsets.UTF_8))
              .path("schema")
              .path("x-query")
              .path("fields");
      var response = mvc.perform(query(suffix)).andExpect(status().isOk()).andReturn();
      var row =
          mapper
              .readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8))
              .path("content")
              .get(0);
      for (var entry : fields.properties()) {
        String field = entry.getKey();
        Object value = mapper.convertValue(row.get(field), Object.class);
        mvc.perform(
                query(suffix)
                    .param("$query", condition(field, "equals", value))
                    .param("sort", field + ",desc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThan(0)))
            .andExpect(jsonPath("$.content[0]." + field).value(value));
      }
    }
  }

  @Test
  void scalarNullEmptyAndEscapedTextHaveConsistentSemantics() throws Exception {
    var first = em.find(User.class, users.getFirst().getId());
    first.setName(null);
    var second = em.find(User.class, users.get(1).getId());
    second.setName("");
    var third = em.find(User.class, users.get(2).getId());
    third.setName("MiXeD_%\\value");
    em.flush();
    mvc.perform(query("").param("name", "is:null"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(first.getId()));
    mvc.perform(query("").param("$query", condition("name", "equals", "")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(second.getId()));
    mvc.perform(query("").param("$query", condition("name", "like", "mixed_%\\")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("MiXeD_%\\value"));
    mvc.perform(query("").param("$query", condition("name", "not:equals", "")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(239));
  }

  @Test
  void schemaContractsAreGeneratedForBrowserRegression() throws Exception {
    for (String name : List.of("PlatformAccount", "PlatformAccountAudit")) {
      var result =
          mvc.perform(query(name.endsWith("Audit") ? "/audit/schema" : "/schema"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.schema['x-query'].maxPageSize").value(50))
              .andExpect(jsonPath("$.schema['x-query'].fields.password").doesNotExist())
              .andReturn();
      String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
      try (var fixture =
          getClass().getResourceAsStream("/query-contracts/common-main/" + name + ".json")) {
        assertNotNull(fixture);
        assertEquals(
            mapper.readTree(fixture),
            mapper.readTree(json),
            "Browser schema must match actual HTTP output");
      }
      var output = Path.of("build/generated-test-resources/platform-account/" + name + ".json");
      Files.createDirectories(output.getParent());
      Files.writeString(output, json);
    }
  }
}
