package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.*;
import org.simplepoint.core.*;
import org.simplepoint.core.permission.*;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.plugin.rbac.core.api.repository.DataScopeRepository;
import org.simplepoint.plugin.rbac.core.base.repository.JpaDataScopeRepository;
import org.simplepoint.plugin.rbac.core.rest.controller.DataScopeController;
import org.simplepoint.plugin.rbac.core.service.impl.*;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.repository.OrganizationRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real policy service/schema/HTTP/SQL; no substituted form contract or policy validation. */
class DataScopeLifecycleHttpTest {
  SessionFactory factory; Session em; MockMvc mvc; AuthorizationContext context;
  DataScopeRepository repository; DataScopeServiceImpl service;
  ResourceAuthorizationVersionService versions; final ObjectMapper mapper = new ObjectMapper();
  final List<String> statements = new ArrayList<>();
  org.springframework.context.support.GenericApplicationContext schemaContext;

  @BeforeEach void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:data-scope-lifecycle-" + UUID.randomUUID())
        .applySetting("hibernate.session_factory.statement_inspector", (org.hibernate.resource.jdbc.spi.StatementInspector) sql -> {statements.add(sql); return sql;})
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").applySetting("hibernate.generate_statistics", true).build())
        .addAnnotatedClass(Resource.class).addAnnotatedClass(DataScope.class).addAnnotatedClass(User.class)
        .addAnnotatedClass(Role.class).addAnnotatedClass(Tenant.class).addAnnotatedClass(Organization.class)
        .addAnnotatedClass(FieldScope.class).addAnnotatedClass(FieldScopeEntry.class)
        .addAnnotatedClass(RoleScopeBinding.class).addAnnotatedClass(RoleResourceGrant.class)
        .buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction();
    var resource = new Resource(); resource.setId("scope-resource"); resource.setCode("organizations.view");
    resource.setName("组织管理"); resource.setType(ResourceType.PAGE); resource.setDisabled(false);
    resource.setPermissionMetadata(new PermissionPageDescriptor(List.of(PermissionModelDescriptor.describe(Organization.class))).toJson()); em.persist(resource);
    var user = new User(); user.setId("verified-user"); user.setName("验证用户"); user.setNickname("不是实名"); em.persist(user);
    var tenant = new Tenant(); tenant.setId("scope-tenant"); tenant.setName("验证工作空间"); tenant.setAuthorizationVersion(0L);
    tenant.setOwnerId(user.getId()); tenant.setTenantType(org.simplepoint.plugin.rbac.tenant.api.entity.TenantType.ORGANIZATION); em.persist(tenant);
    var organization = new Organization(); organization.setId("scope-organization"); organization.setTenantId(tenant.getId());
    organization.setName("已授权组织"); organization.setCode("AUTHORIZED"); organization.setType("department"); organization.setEnabled(true); em.persist(organization);
    var policy = new DataScope(); policy.setId("scope-policy"); policy.setTenantId(tenant.getId()); policy.setName("真实表达式策略");
    policy.setType(DataScopeType.EXPRESSION); policy.setResourceCode(resource.getCode()); policy.setExpression("name = \"可见组织\"");
    policy.setCreatedAt(java.time.Instant.parse("2026-10-10T00:00:00Z")); policy.setUpdatedAt(policy.getCreatedAt()); em.persist(policy);
    em.flush(); em.clear();
    context = new AuthorizationContext(); context.setUserId(user.getId()); context.setDataScopeType("CUSTOM");
    context.setDeptIds(Set.of(organization.getId())); context.setDataScopeIncludeSelf(false);
    context.setResources(Set.of(resource.getCode(), "data-scope.view", "data-scope.edit", "data-scope.create"));
    context.setAttributes(Map.of("X-Tenant-Id", tenant.getId()));
    context.setResourceDataScopes(Map.of(resource.getCode(), new ResourceDataScope("CUSTOM", Set.of(organization.getId()), false,
        List.of("name = \"可见组织\""))));
    context.setResourceFieldPermissions(Map.of(resource.getCode(), Map.of(Organization.class.getName() + "#name", "VISIBLE")));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getId(), "unused", List.of()));
    var repoFactory = new JpaRepositoryFactory(em); repoFactory.setRepositoryBaseClass(BaseRepositoryImpl.class);
    repository = repoFactory.getRepository(JpaDataScopeRepository.class);
    var config = new JsonSchemaAutoConfiguration(mapper);
    var generator = config.schemaGenerator(Set.of(config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()));
    schemaContext = new org.springframework.context.support.GenericApplicationContext();
    schemaContext.registerBean(JsonSchemaGenerator.class, () -> config.defaultJsonSchemaGenerator(generator));
    schemaContext.registerBean(JsonSchemaDetailsService.class, () -> new org.simplepoint.data.json.schema.service.DefaultJsonSchemaDetailsService(em)); schemaContext.refresh();
    var details = new org.simplepoint.core.security.SpringBeanDetailsProviderService(); details.setApplicationContext(schemaContext);
    versions = mock(ResourceAuthorizationVersionService.class);
    var organizations = mock(OrganizationRepository.class);
    when(organizations.findByIdAndTenantId(organization.getId(), tenant.getId())).thenAnswer(invocation -> Optional.ofNullable(em.find(Organization.class, organization.getId())));
    service = new DataScopeServiceImpl(repository, details, versions, organizations, new PermissionPolicyGuard(em));
    var expressions = new PermissionExpressionService(em); ReflectionTestUtils.setField(service, "expressions", expressions);
    var controller = new DataScopeController(service); ReflectionTestUtils.setField(controller, "expressions", expressions);
    ReflectionTestUtils.setField(controller, "presentation", new PermissionPresentationService(em, new FieldScopeCatalog(em)));
    mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new org.simplepoint.core.handler.GlobalExceptionHandler())
        .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(
            tools.jackson.databind.json.JsonMapper.builder().addModule(new org.simplepoint.core.jackson.FieldScopeJackson3Module()).build()))
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
  }
  @AfterEach void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY); SecurityContextHolder.clearContext();
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();}
    if (factory != null) factory.close();
    if (schemaContext != null) schemaContext.close();
  }
  String read(String path) throws Exception {
    return mvc.perform(get("/data-scopes" + path).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"MASKED", "HIDDEN"})
  void presentationMapsCannotBypassActualSourceFieldPermissions(String access) throws Exception {
    var role = new Role(); role.setId("scope-role"); role.setTenantId("scope-tenant"); role.setRoleName("Private role"); role.setAuthority("ROLE_SCOPE"); em.persist(role);
    var fieldRule = new FieldScope(); fieldRule.setId("field-rule"); fieldRule.setTenantId("scope-tenant"); fieldRule.setName("Private field rule"); em.persist(fieldRule);
    var user = em.find(User.class, "verified-user"); user.setEmail("private@example.invalid");
    em.flush(); em.clear(); factory.getStatistics().clear();
    context = new AuthorizationContext(); context.setUserId("verified-user"); context.setDeptIds(Set.of("scope-organization"));
    context.setDataScopeType("CUSTOM"); context.setDataScopeIncludeSelf(false);
    context.setResources(Set.of("organizations.view", "data-scope.view", "field-scope.view"));
    context.setAttributes(Map.of("X-Tenant-Id", "scope-tenant", "X-Role-Id", "scope-role"));
    context.setFieldPermissions(Map.of(User.class.getName() + "#name", access, User.class.getName() + "#email", access,
        Tenant.class.getName() + "#name", access, Role.class.getName() + "#roleName", access,
        DataScope.class.getName() + "#name", access, FieldScope.class.getName() + "#name", access,
        Organization.class.getName() + "#name", access, Resource.class.getName() + "#name", access));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var result = mapper.readTree(read("/presentation"));
    if (access.equals("MASKED")) {
      for (String name : List.of("userName", "tenantName", "roleName")) assertEquals("***", result.path(name).asText(), name);
      assertEquals("***", result.path("dataRules").path("scope-policy").asText());
      assertEquals("***", result.path("fieldRules").path("field-rule").asText());
      assertEquals("***", result.path("organizations").path("scope-organization").asText());
      assertEquals("***", result.path("pages").path("organizations.view").path("title").asText());
    } else {
      for (String name : List.of("userName", "tenantName", "roleName")) assertFalse(result.has(name), name);
      for (String name : List.of("dataRules", "fieldRules", "organizations")) assertEquals(0, result.path(name).size(), name);
      assertEquals("", result.path("pages").path("organizations.view").path("title").asText());
    }
    assertFalse(result.toString().contains("Private")); assertFalse(result.toString().contains("private@example.invalid"));
    assertEquals(0, factory.getStatistics().getEntityLoadCount(), "Presentation reads must use actual scalar projections, not whole policy entities");
    assertEquals(1, result.path("pages").path("organizations.view").path("models").size(), "Readable registration metadata remains usable");
    var outputs = new LinkedHashMap<String, String>(); String prefix = access.toLowerCase(Locale.ROOT);
    outputs.put(prefix + "-presentation", result.toString()); outputs.put(prefix + "-effective", read("/effective"));
    outputs.put(prefix + "-schema", read("/schema")); outputs.put(prefix + "-list", read("?page=0&size=20"));
    for (var output : outputs.entrySet()) {Path generated = Path.of("build/generated-test-resources/data-scope-" + output.getKey() + ".json"); Files.createDirectories(generated.getParent()); Files.writeString(generated, output.getValue());}
    for (var output : outputs.entrySet()) assertEquals(mapper.readTree(Path.of("../../simplepoint-react/scripts/data-scope-contracts/" + output.getKey() + ".json").toFile()), mapper.readTree(output.getValue()));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"MASKED", "VISIBLE"})
  void hiddenFullNameUsesOnlyTheActuallyReadableEmailFallback(String emailAccess) throws Exception {
    var user = em.find(User.class, "verified-user"); user.setEmail("readable@example.invalid"); em.flush(); em.clear();
    context.setFieldPermissions(Map.of(User.class.getName() + "#name", "VISIBLE", "User#name", "HIDDEN", User.class.getName() + "#email", emailAccess));
    var result = mapper.readTree(read("/presentation"));
    assertEquals(emailAccess.equals("MASKED") ? "***" : "readable@example.invalid", result.path("userName").asText());
    assertFalse(result.toString().contains("验证用户"));
  }

  @Test void presentationUsesTheSharedRealNameRuleWithoutProtectedSourceLeakage() throws Exception {
    var user = em.find(User.class, "verified-user");
    user.setName(null);
    user.setFamilyName(" 张 "); user.setGivenName(" 三 "); user.setEmail("fallback@example.test");
    em.flush();
    assertEquals("张 三", mapper.readTree(read("/presentation")).path("userName").asText());
    user.setName("完整姓名"); em.flush();
    assertEquals("完整姓名", mapper.readTree(read("/presentation")).path("userName").asText());
    context.setFieldPermissions(Map.of(User.class.getName() + "#name", "HIDDEN",
        User.class.getName() + "#familyName", "MASKED", User.class.getName() + "#givenName", "HIDDEN"));
    statements.clear();
    assertEquals("fallback@example.test", mapper.readTree(read("/presentation")).path("userName").asText());
    String identityQuery = statements.stream().filter(sql -> sql.contains("simpoint_ac_users")).findFirst().orElseThrow();
    assertFalse(identityQuery.contains("familyName"));
    assertFalse(identityQuery.contains("givenName"));
    assertFalse(identityQuery.contains("nickname"));
  }

  @Test void completePresentationRulesAndCollectionEdgesRemainBoundedBeyondFiveHundred() throws Exception {
    var foreign = new DataScope(); foreign.setId("foreign-data"); foreign.setTenantId("other-tenant"); foreign.setName("Foreign rule"); foreign.setType(DataScopeType.SELF); em.persist(foreign);
    var foreignField = new FieldScope(); foreignField.setId("foreign-field"); foreignField.setTenantId("other-tenant"); foreignField.setName("Foreign field rule"); em.persist(foreignField);
    for (int index = 0; index < 521; index++) {
      String suffix = String.format("%03d", index), id = "department-" + suffix;
      var organization = new Organization(); organization.setId(id); organization.setTenantId("scope-tenant"); organization.setCode(id); organization.setEnabled(true); organization.setName("实际组织 " + suffix); em.persist(organization);
      var policy = new DataScope(); policy.setId("rule-" + suffix); policy.setTenantId("scope-tenant"); policy.setName("实际范围 " + suffix); policy.setType(DataScopeType.CUSTOM);
      policy.setCustomDeptIds(new LinkedHashSet<>(Set.of(id)));
      if (index == 0) for (int extra = 0; extra < 200; extra++) {
        String extraId = "extra-department-" + String.format("%03d", extra); var item = new Organization(); item.setId(extraId); item.setTenantId("scope-tenant"); item.setCode(extraId); item.setEnabled(true); item.setName("额外实际组织 " + extra); em.persist(item); policy.getCustomDeptIds().add(extraId);
      }
      em.persist(policy);
      var field = new FieldScope(); field.setId("field-" + suffix); field.setTenantId("scope-tenant"); field.setName("实际字段规则 " + suffix); em.persist(field);
    }
    em.flush(); em.clear(); factory.getStatistics().clear(); statements.clear();
    context = new AuthorizationContext(); context.setUserId("verified-user"); context.setDeptIds(Set.of("scope-organization"));
    context.setResources(Set.of("organizations.view", "data-scope.view", "field-scope.view")); context.setAttributes(Map.of("X-Tenant-Id", "scope-tenant"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var result = mapper.readTree(read("/presentation"));
    assertEquals(522, result.path("dataRules").size()); assertEquals("真实表达式策略", result.path("dataRules").path("scope-policy").asText(), "Zero-department rules cannot disappear through an inner join");
    assertEquals("实际范围 520", result.path("dataRules").path("rule-520").asText()); assertEquals(521, result.path("fieldRules").size());
    assertEquals("实际字段规则 520", result.path("fieldRules").path("field-520").asText()); assertEquals(722, result.path("organizations").size());
    assertEquals("额外实际组织 199", result.path("organizations").path("extra-department-199").asText()); assertFalse(result.toString().contains("Foreign"));
    assertEquals(0, factory.getStatistics().getEntityLoadCount());
    for (var sql : statements) if (sql.startsWith("select")) {
      var memberships = java.util.regex.Pattern.compile("(?i)\\bin\\s*\\(([^)]*)\\)").matcher(sql);
      while (memberships.find()) assertTrue(memberships.group(1).chars().filter(character -> character == '?').count() <= 100, sql);
      if (sql.contains("simpoint_ac_data_scope") || sql.contains("simpoint_ac_field_scope")) assertTrue(sql.contains("fetch first ? rows only"), sql);
    }
  }
  void export(String name, String content) throws Exception {
    Path output = Path.of("build/generated-test-resources/data-scope-" + name + ".json"); Files.createDirectories(output.getParent()); Files.writeString(output, content);
    Path fixture = Path.of("../../simplepoint-react/scripts/data-scope-contracts/" + name + ".json");
    assertEquals(mapper.readTree(fixture.toFile()), mapper.readTree(content), "Browser contract must match the real HTTP result: " + name);
  }
  @Test void actualPreviewCatalogAndFormContractsComeFromCurrentCallerOnly() throws Exception {
    export("schema", read("/schema")); export("catalog", read("/expression/catalog")); export("presentation", read("/presentation"));
    assertEquals(mapper.readTree(read("/expression/catalog")), mapper.readTree(read("/expression/catalog?resourceCode=organizations.view")),
        "Target browser metadata must match the same actual single-page contract, not a synthetic field provider");
    var effective = mapper.readTree(read("/effective")); assertEquals("verified-user", effective.path("userId").asText());
    assertEquals("scope-tenant", effective.path("tenantId").asText()); assertFalse(effective.path("includeSelf").asBoolean()); export("effective", effective.toString());
    context.setFieldPermissions(Map.of(DataScope.class.getName() + "#type", "MASKED", DataScope.class.getName() + "#name", "VISIBLE",
        DataScope.class.getName() + "#expression", "VISIBLE", DataScope.class.getName() + "#resourceCode", "VISIBLE"));
    export("protected-schema", read("/schema"));
    mvc.perform(get("/data-scopes/effective").param("userId", "someone-else").param("tenantId", "other-tenant")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value("verified-user")).andExpect(jsonPath("$.tenantId").value("scope-tenant"));
  }
  @Test void protectedRequiredAndExpressionFieldsAreRestoredBeforePolicyValidation() throws Exception {
    context.setFieldPermissions(Map.of(DataScope.class.getName() + "#type", "MASKED", DataScope.class.getName() + "#name", "VISIBLE",
        DataScope.class.getName() + "#expression", "VISIBLE", DataScope.class.getName() + "#resourceCode", "VISIBLE"));
    mvc.perform(put("/data-scopes").contentType("application/json").content("{\"id\":\"scope-policy\",\"description\":\"合法说明修改\"}")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk());
    em.flush(); em.clear(); var actual = em.find(DataScope.class, "scope-policy");
    assertEquals("真实表达式策略", actual.getName()); assertEquals(DataScopeType.EXPRESSION, actual.getType());
    assertEquals("name = \"可见组织\"", actual.getExpression()); assertEquals("organizations.view", actual.getResourceCode());
    assertEquals("合法说明修改", actual.getDescription()); verify(versions).refreshTenant("scope-tenant");
  }
  @Test void invalidWritablePolicyNeverReachesSqlOrAuthorizationRefresh() throws Exception {
    long updates = factory.getStatistics().getEntityUpdateCount();
    mvc.perform(put("/data-scopes").contentType("application/json").content("""
        {"id":"scope-policy","name":"新名称","type":"EXPRESSION","resourceCode":"foreign.view","expression":"name = \\\"x\\\""}
        """).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PERMISSION_POLICY"));
    em.flush(); assertEquals(updates, factory.getStatistics().getEntityUpdateCount()); verifyNoInteractions(versions);
  }
  @Test void expressionAndProjectedRowsAreRealBrowserContracts() throws Exception {
    var validation = mvc.perform(post("/data-scopes/expression/validate").contentType("application/json")
        .content("{\"resourceCode\":\"organizations.view\",\"expression\":\"name = \\\"可见组织\\\"\"}")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    var row = mapper.readTree(read("?page=0&size=15")).path("content").get(0);
    context.setFieldPermissions(Map.of(DataScope.class.getName() + "#type", "MASKED", DataScope.class.getName() + "#name", "VISIBLE",
        DataScope.class.getName() + "#expression", "VISIBLE", DataScope.class.getName() + "#resourceCode", "VISIBLE"));
    var protectedRow = mapper.readTree(read("?page=0&size=15")).path("content").get(0);
    var outputs = Map.of("validation", validation, "row", row.toString(), "protected-row", protectedRow.toString());
    for (var entry : outputs.entrySet()) {
      Path path = Path.of("build/generated-test-resources/data-scope-" + entry.getKey() + ".json"); Files.createDirectories(path.getParent()); Files.writeString(path, entry.getValue());
    }
    for (var entry : outputs.entrySet()) export(entry.getKey(), entry.getValue());
  }
  @Test void protectedDependenciesCannotBeReinterpretedByChangingTheirDiscriminator() throws Exception {
    context.setFieldPermissions(Map.of(DataScope.class.getName() + "#expression", "VISIBLE"));
    long updates = factory.getStatistics().getEntityUpdateCount();
    mvc.perform(put("/data-scopes").contentType("application/json")
        .content("{\"id\":\"scope-policy\",\"name\":\"真实表达式策略\",\"type\":\"SELF\"}")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PERMISSION_POLICY"));
    em.flush(); assertEquals(updates, factory.getStatistics().getEntityUpdateCount()); verifyNoInteractions(versions);
    assertEquals(DataScopeType.EXPRESSION, em.find(DataScope.class, "scope-policy").getType());
  }
  @Test void createCannotSmuggleProtectedRequiredFieldsAndUnknownUpdatesCannotWrite() throws Exception {
    context.setFieldPermissions(Map.of(DataScope.class.getName() + "#name", "VISIBLE"));
    long inserts = factory.getStatistics().getEntityInsertCount();
    mvc.perform(post("/data-scopes").contentType("application/json").content("{\"name\":\"伪造保护字段\",\"type\":\"SELF\"}")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isBadRequest());
    mvc.perform(put("/data-scopes").contentType("application/json").content("{\"id\":\"not-a-policy\",\"type\":\"SELF\"}")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isBadRequest());
    em.flush(); assertEquals(inserts, factory.getStatistics().getEntityInsertCount()); verifyNoInteractions(versions);
  }
}
