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
import org.simplepoint.plugin.rbac.core.api.repository.FieldScopeRepository;
import org.simplepoint.plugin.rbac.core.base.repository.JpaFieldScopeRepository;
import org.simplepoint.plugin.rbac.core.rest.controller.FieldScopeController;
import org.simplepoint.plugin.rbac.core.service.impl.*;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Genuine HTTP/schema/service/JPA policies: the registered metadata and validation are never substituted. */
class FieldScopeLifecycleHttpTest {
  SessionFactory factory; Session em; MockMvc mvc; AuthorizationContext context;
  FieldScopeRepository repository; ResourceAuthorizationVersionService versions;
  final ObjectMapper mapper = new ObjectMapper();
  final List<String> statements = new ArrayList<>();
  org.springframework.context.support.GenericApplicationContext schemaContext;
  static final String MODEL = "org.simplepoint.remote.Person";

  @BeforeEach void setup() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:field-scope-lifecycle-" + UUID.randomUUID())
        .applySetting("hibernate.session_factory.statement_inspector", (org.hibernate.resource.jdbc.spi.StatementInspector) sql -> {statements.add(sql); return sql;})
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").applySetting("hibernate.generate_statistics", true).build())
        .addAnnotatedClass(Resource.class).addAnnotatedClass(DataScope.class).addAnnotatedClass(User.class)
        .addAnnotatedClass(Role.class).addAnnotatedClass(Tenant.class).addAnnotatedClass(Organization.class)
        .addAnnotatedClass(FieldScope.class).addAnnotatedClass(FieldScopeEntry.class)
        .addAnnotatedClass(RoleScopeBinding.class).addAnnotatedClass(RoleResourceGrant.class)
        .buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction();
    register("people.view", MODEL, "name", "email");
    var organizations = register("organizations.view", Organization.class.getName()); organizations.setName("组织管理");
    organizations.setPermissionMetadata(new PermissionPageDescriptor(List.of(PermissionModelDescriptor.describe(Organization.class))).toJson());
    var unrelated = register("unrelated.view", "remote.Unrelated", "name"); unrelated.setPermissionMetadata("malformed unrelated metadata");
    var policy = new FieldScope(); policy.setId("own-policy"); policy.setTenantId("scope-tenant"); policy.setName("原名称"); em.persist(policy);
    var foreign = new FieldScope(); foreign.setId("foreign-policy"); foreign.setTenantId("other-tenant"); foreign.setName("其他空间"); em.persist(foreign);
    em.flush(); em.clear();
    context = new AuthorizationContext(); context.setUserId("verified-user");
    context.setResources(Set.of("people.view", "organizations.view", "field-scope.view", "field-scope.edit", "field-scope.create"));
    context.setAttributes(Map.of("X-Tenant-Id", "scope-tenant"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("verified-user", "unused", List.of()));
    var repoFactory = new JpaRepositoryFactory(em); repoFactory.setRepositoryBaseClass(BaseRepositoryImpl.class);
    repository = repoFactory.getRepository(JpaFieldScopeRepository.class);
    // Resolve the actual schema-generation dialect, not a hand-built schema or a getAllFieldNames override.
    var config = new JsonSchemaAutoConfiguration(mapper);
    var generator = config.schemaGenerator(Set.of(config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()));
    schemaContext = new org.springframework.context.support.GenericApplicationContext();
    schemaContext.registerBean(JsonSchemaGenerator.class, () -> config.defaultJsonSchemaGenerator(generator));
    schemaContext.registerBean(JsonSchemaDetailsService.class, () -> new org.simplepoint.data.json.schema.service.DefaultJsonSchemaDetailsService(em)); schemaContext.refresh();
    var details = new org.simplepoint.core.security.SpringBeanDetailsProviderService(); details.setApplicationContext(schemaContext);
    versions = mock(ResourceAuthorizationVersionService.class);
    var service = new FieldScopeServiceImpl(repository, details, versions, new FieldScopeCatalog(em), new PermissionPolicyGuard(em));
    mvc = MockMvcBuilders.standaloneSetup(new FieldScopeController(service))
        .defaultRequest(get("/").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .addFilters(new org.springframework.security.web.access.ExceptionTranslationFilter(
            new org.springframework.security.web.authentication.HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED)))
        .setControllerAdvice(new org.simplepoint.core.handler.GlobalExceptionHandler())
        .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(
            tools.jackson.databind.json.JsonMapper.builder().addModule(new org.simplepoint.core.jackson.FieldScopeJackson3Module()).build()))
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
    factory.getStatistics().clear();
  }

  Resource register(String code, String model, String... fields) {
    var properties = new LinkedHashMap<String, PermissionModelDescriptor.Field>();
    for (var name : fields) properties.put(name, new PermissionModelDescriptor.Field("java.lang.String", name, List.of("equals"), List.of()));
    var resource = new Resource(); resource.setId(code); resource.setCode(code); resource.setName(code);
    resource.setType(ResourceType.PAGE); resource.setDisabled(false);
    resource.setPermissionMetadata(new PermissionPageDescriptor(List.of(new PermissionModelDescriptor(model, true, true, "id", null, properties))).toJson());
    em.persist(resource); return resource;
  }
  String body(String name, String field, FieldAccessType access) throws Exception {
    var entry = Map.of("resource", MODEL, "field", field, "access", access.name(), "tenantId", "spoofed-tenant", "fieldScopeId", "foreign-policy");
    return mapper.writeValueAsString(Map.of("name", name, "description", "真实配置提交", "entries", List.of(entry)));
  }
  long metadataQueries() {
    return Arrays.stream(factory.getStatistics().getQueries()).filter(query -> query.contains("permissionMetadata"))
        .mapToLong(query -> factory.getStatistics().getQueryStatistics(query).getExecutionCount()).sum();
  }
  @AfterEach void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY); SecurityContextHolder.clearContext();
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();}
    if (factory != null) factory.close();
    if (schemaContext != null) schemaContext.close();
  }

  @Test void actualSchemaAndConfigurationPersistCanonicalFieldsAndServerOwnedIdentityWithOneMetadataQuery() throws Exception {
    mvc.perform(get("/field-scopes/schema")).andExpect(status().isOk())
        .andExpect(jsonPath("$.schema.properties.name").exists()).andExpect(jsonPath("$.schema.properties.entries.items.properties.access.enum").isArray());
    var result = mvc.perform(post("/field-scopes/configuration").contentType("application/json").content(body("真实规则", "name", FieldAccessType.MASKED)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].resource").value(MODEL))
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    String id = mapper.readTree(result).path("id").asText(); assertFalse(id.isBlank());
    em.flush(); em.clear(); var created = repository.findByIdAndTenantIdAndDeletedAtIsNull(id, "scope-tenant").orElseThrow();
    assertEquals("真实规则", created.getName()); assertEquals("真实配置提交", created.getDescription());
    assertEquals(id, created.getEntries().getFirst().getFieldScopeId()); assertEquals("scope-tenant", created.getEntries().getFirst().getTenantId());
    assertEquals(1, metadataQueries(), "Authorized fields are validated once; unrelated global malformed metadata must not be read");
    verify(versions, times(2)).refreshTenant("scope-tenant");
  }

  @Test void browserFieldMetadataMatchesTheActualAuthorizedRegisteredModelAndHttpProjection() throws Exception {
    var actual = mvc.perform(get("/field-scopes/page-fields").param("code", "organizations.view"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    Path generated = Path.of("build/generated-test-resources/field-scope-page-fields.json");
    Files.createDirectories(generated.getParent()); Files.writeString(generated, actual);
    assertEquals(mapper.readTree(Path.of("../../simplepoint-react/scripts/field-scope-contracts/page-fields.json").toFile()), mapper.readTree(actual),
        "Production matrix metadata must match the real controller/model contract, not a synthetic field provider");
    mvc.perform(get("/field-scopes/page-fields").param("code", "unrelated.view")).andExpect(status().isBadRequest());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"alias,MASKED", "alias,HIDDEN", "title,MASKED", "title,HIDDEN", "name,MASKED", "name,HIDDEN", "label,MASKED", "label,HIDDEN"})
  void directoryAndPageFieldTitlesProjectTheirActualFallbackSources(String source, String access) throws Exception {
    var resource = em.find(Resource.class, "organizations.view");
    resource.setAlias(null); resource.setTitle(null); resource.setName("Allowed fallback name"); resource.setLabel("Allowed fallback label");
    String secret = source.equals("title") ? "menu.private.title" : "Private " + source;
    switch (source) {
      case "alias" -> resource.setAlias(secret);
      case "title" -> resource.setTitle(secret);
      case "name" -> resource.setName(secret);
      case "label" -> resource.setLabel(secret);
      default -> throw new AssertionError(source);
    }
    var permissions = new LinkedHashMap<String, String>(); permissions.put(Resource.class.getName() + "#" + source, access);
    if (source.equals("label")) permissions.put(Resource.class.getName() + "#name", "HIDDEN");
    context.setFieldPermissions(permissions); em.flush(); em.clear(); factory.getStatistics().clear();
    String expected = access.equals("MASKED") ? "***" : source.equals("name") ? "Allowed fallback label" : source.equals("label") ? "" : "Allowed fallback name";
    var directory = mvc.perform(get("/field-scopes/resources")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    var nodes = mapper.readTree(directory).path("content");
    var actual = java.util.stream.StreamSupport.stream(nodes.spliterator(), false).filter(node -> node.path("id").asText().equals(resource.getId())).findFirst().orElseThrow();
    assertEquals(expected, actual.path("title").asText()); assertEquals("organizations.view", actual.path("pageCode").asText());
    var fields = mvc.perform(get("/field-scopes/page-fields").param("code", "organizations.view")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertEquals(expected, mapper.readTree(fields).path("title").asText());
    assertEquals(mapper.readTree(resource.getPermissionMetadata()).path("models"), mapper.readTree(fields).path("models"));
    assertFalse(directory.contains(secret)); assertFalse(fields.contains(secret));
    assertEquals(0, factory.getStatistics().getEntityLoadCount());
  }

  @Test void browserDirectoryPagesMatchActualHttpAndAllExpandedMembershipBindsAreBounded() throws Exception {
    var grants = new LinkedHashSet<>(List.of("field-scope.view", "field-scope.create", "field-scope.edit"));
    var descriptor = new PermissionPageDescriptor(List.of(PermissionModelDescriptor.describe(Organization.class))).toJson();
    for (int index = 0; index < 521; index++) {
      String code = "matrix.page" + index; grants.add(code);
      var resource = new Resource(); resource.setCode(code); resource.setId("matrix-resource-" + index);
      resource.setName("实际资源 " + index); resource.setSort(index); resource.setPermissionMetadata(descriptor);
      resource.setType(ResourceType.PAGE); resource.setDisabled(false); em.persist(resource);
    }
    var directoryContext = new AuthorizationContext(); directoryContext.setUserId("verified-user"); directoryContext.setResources(grants);
    directoryContext.setAttributes(Map.of("X-Tenant-Id", "scope-tenant"));
    em.flush(); em.clear(); factory.getStatistics().clear(); statements.clear();
    var outputs = new LinkedHashMap<String, String>();
    for (int page : List.of(0, 34)) {
      var response = mvc.perform(get("/field-scopes/resources").param("page", String.valueOf(page)).param("size", "15")
          .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, directoryContext))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
      var content = mapper.readTree(response).path("content");
      assertEquals(page == 0 ? 15 : 11, content.size());
      assertEquals("matrix-resource-" + page * 15, content.get(0).path("id").asText());
      assertEquals("matrix.page" + (page == 0 ? 14 : 520), content.get(content.size() - 1).path("pageCode").asText());
      outputs.put("resources-page-" + page, response);
    }
    for (var sql : statements) if (sql.startsWith("select")) {
      var memberships = java.util.regex.Pattern.compile("(?i)\\bin\\s*\\(([^)]*)\\)").matcher(sql);
      while (memberships.find()) assertTrue(memberships.group(1).chars().filter(character -> character == '?').count() <= 100, sql);
    }
    assertEquals(0, factory.getStatistics().getEntityLoadCount(), "HTTP directory should read projections, never complete Resource entities");
    for (var output : outputs.entrySet()) {
      Path generated = Path.of("build/generated-test-resources/field-scope-" + output.getKey() + ".json");
      Files.createDirectories(generated.getParent()); Files.writeString(generated, output.getValue());
    }
    for (var output : outputs.entrySet()) assertEquals(mapper.readTree(Path.of("../../simplepoint-react/scripts/field-scope-contracts/" + output.getKey() + ".json").toFile()),
        mapper.readTree(output.getValue()), "Browser directory must use actual native HTTP projection and page metadata");
  }

  @Test void updateReusesExistingRowsAndPreflightFailuresDoNotChangeSqlOrAuthorizationVersion() throws Exception {
    mvc.perform(put("/field-scopes/configuration").param("id", "own-policy").contentType("application/json")
        .content(body("首次保存", "email", FieldAccessType.MASKED))).andExpect(status().isOk()); em.flush(); em.clear();
    var original = repository.findByIdAndTenantIdAndDeletedAtIsNull("own-policy", "scope-tenant").orElseThrow();
    String rowId = original.getEntries().getFirst().getId(); clearInvocations(versions); factory.getStatistics().clear();
    mvc.perform(put("/field-scopes/configuration").param("id", "own-policy").contentType("application/json")
        .content(body("不应保存", "unknown", FieldAccessType.HIDDEN))).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PERMISSION_POLICY"));
    em.flush(); assertEquals(0, factory.getStatistics().getEntityUpdateCount()); verifyNoInteractions(versions);
    assertEquals("首次保存", original.getName()); assertEquals(FieldAccessType.MASKED, original.getEntries().getFirst().getAccess());
    mvc.perform(put("/field-scopes/configuration").param("id", "own-policy").contentType("application/json")
        .content(body("再次保存", "email", FieldAccessType.VISIBLE))).andExpect(status().isOk()); em.flush(); em.clear();
    var updated = repository.findByIdAndTenantIdAndDeletedAtIsNull("own-policy", "scope-tenant").orElseThrow();
    assertEquals(rowId, updated.getEntries().getFirst().getId()); assertEquals(FieldAccessType.VISIBLE, updated.getEntries().getFirst().getAccess());
  }

  @Test void retainedUnavailableEntriesCanBeRemovedWithoutReloadingEitherCatalogButAccessChangesRemainDenied() throws Exception {
    mvc.perform(put("/field-scopes/configuration").param("id", "own-policy").contentType("application/json")
        .content(body("已保存", "name", FieldAccessType.MASKED))).andExpect(status().isOk()); em.flush();
    em.find(Resource.class, "people.view").setDeletedAt(java.time.Instant.now()); em.flush(); factory.getStatistics().clear(); clearInvocations(versions);
    mvc.perform(put("/field-scopes/configuration").param("id", "own-policy").contentType("application/json")
        .content(body("仅改说明", "name", FieldAccessType.MASKED))).andExpect(status().isOk()); em.flush();
    assertEquals(0, metadataQueries());
    mvc.perform(put("/field-scopes/configuration").param("id", "own-policy").contentType("application/json")
        .content(body("不应保存", "name", FieldAccessType.VISIBLE))).andExpect(status().isBadRequest());
    mvc.perform(put("/field-scopes/configuration").param("id", "own-policy").contentType("application/json")
        .content("{\"name\":\"清除旧项\",\"entries\":[]}")).andExpect(status().isOk()); em.flush(); em.clear();
    assertTrue(repository.findByIdAndTenantIdAndDeletedAtIsNull("own-policy", "scope-tenant").orElseThrow().getEntries().isEmpty());
  }

  @Test void actualNestedReadonlyCollectionPoliciesDenyChangesBeforeWritingMetadata() throws Exception {
    mvc.perform(put("/field-scopes/configuration").param("id", "own-policy").contentType("application/json")
        .content(body("原名称", "name", FieldAccessType.MASKED))).andExpect(status().isOk()); em.flush(); clearInvocations(versions);
    context.setFieldPermissions(Map.of(FieldScope.class.getName() + "#entries", "VISIBLE")); factory.getStatistics().clear();
    mvc.perform(get("/field-scopes/schema")).andExpect(status().isOk()).andExpect(jsonPath("$.schema.properties.entries.readOnly").value(true));
    mvc.perform(put("/field-scopes/configuration").param("id", "own-policy").contentType("application/json")
        .content(body("不得修改", "name", FieldAccessType.HIDDEN))).andExpect(status().isForbidden());
    em.flush(); assertEquals(0, factory.getStatistics().getEntityUpdateCount()); verifyNoInteractions(versions);
    assertEquals("原名称", em.find(FieldScope.class, "own-policy").getName());
  }

  @Test void foreignPolicyAndCallerPageAliasesCannotUseGlobalRegistrationsToBypassConfigurationGrants() throws Exception {
    mvc.perform(put("/field-scopes/configuration").param("id", "foreign-policy").contentType("application/json")
        .content(body("非法修改", "name", FieldAccessType.VISIBLE))).andExpect(status().isBadRequest());
    var aliasBody = body("非法创建", "name", FieldAccessType.VISIBLE).replace(MODEL, "Person");
    mvc.perform(post("/field-scopes/configuration").contentType("application/json").content(aliasBody)).andExpect(status().isBadRequest());
    em.flush(); assertEquals(0, factory.getStatistics().getEntityInsertCount()); assertEquals(0, factory.getStatistics().getEntityUpdateCount());
    assertEquals("其他空间", em.find(FieldScope.class, "foreign-policy").getName()); verifyNoInteractions(versions);
  }

  @Test void legacyEntriesCanUseCanonicalLocalFieldsWithoutReadingUnrelatedRemoteRegistry() throws Exception {
    String entries = mapper.writeValueAsString(List.of(Map.of("resource", User.class.getName(), "field", " email ", "access", "VISIBLE")));
    mvc.perform(put("/field-scopes/entries").param("fieldScopeId", "own-policy").contentType("application/json").content(entries))
        .andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].resource").value(User.class.getName()));
    em.flush(); assertEquals(0, metadataQueries());
    assertEquals("email", em.find(FieldScope.class, "own-policy").getEntries().getFirst().getField()); verify(versions).refreshTenant("scope-tenant");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"VISIBLE", "MASKED", "HIDDEN"})
  void protectedRequiredNamesAreRestoredBeforeGenericMetadataValidationAndExistingEntriesNeverChange(String level) throws Exception {
    mvc.perform(put("/field-scopes/configuration").param("id", "own-policy").contentType("application/json")
        .content(body("原名称", "name", FieldAccessType.MASKED))).andExpect(status().isOk()); em.flush();
    String entryId = em.find(FieldScope.class, "own-policy").getEntries().getFirst().getId();
    context.setFieldPermissions(Map.of(FieldScope.class.getName() + "#name", level, FieldScope.class.getName() + "#entries", "HIDDEN"));
    factory.getStatistics().clear(); clearInvocations(versions);
    mvc.perform(put("/field-scopes").contentType("application/json").content("{\"id\":\"own-policy\",\"description\":\"合法说明修改\",\"entries\":[]}"))
        .andExpect(status().isOk()); em.flush(); em.clear();
    var stored = em.find(FieldScope.class, "own-policy"); assertEquals("原名称", stored.getName()); assertEquals("合法说明修改", stored.getDescription());
    assertEquals(entryId, stored.getEntries().getFirst().getId()); assertEquals(FieldAccessType.MASKED, stored.getEntries().getFirst().getAccess());
    assertEquals(0, metadataQueries()); verify(versions).refreshTenant("scope-tenant");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"MASKED", "HIDDEN"})
  void unavailableProtectedNameDoesNotPreventAnAuthorizedConfigurationChange(String level) throws Exception {
    context.setFieldPermissions(Map.of(FieldScope.class.getName() + "#name", level));
    String request = mapper.writeValueAsString(Map.of("description", "合法规则修改", "entries",
        List.of(Map.of("resource", MODEL, "field", "email", "access", "VISIBLE"))));
    mvc.perform(put("/field-scopes/configuration").param("id", "own-policy").contentType("application/json").content(request))
        .andExpect(status().isOk()); em.flush(); em.clear();
    var stored = em.find(FieldScope.class, "own-policy"); assertEquals("原名称", stored.getName()); assertEquals("合法规则修改", stored.getDescription());
    assertEquals("email", stored.getEntries().getFirst().getField()); assertEquals(FieldAccessType.VISIBLE, stored.getEntries().getFirst().getAccess());
    verify(versions, times(2)).refreshTenant("scope-tenant");
  }

  @Test void protectedDescriptionIsRestoredBeforeLengthValidationAndForeignMetadataCannotBeUpdated() throws Exception {
    var original = em.find(FieldScope.class, "own-policy"); original.setDescription("原说明"); em.flush();
    context.setFieldPermissions(Map.of(FieldScope.class.getName() + "#description", "VISIBLE"));
    mvc.perform(put("/field-scopes").contentType("application/json").content(mapper.writeValueAsString(Map.of(
        "id", "own-policy", "name", "可编辑名称", "description", "x".repeat(201)))))
        .andExpect(status().isOk()); em.flush();
    assertEquals("原说明", original.getDescription()); assertEquals("可编辑名称", original.getName());
    mvc.perform(put("/field-scopes").contentType("application/json").content("{\"id\":\"foreign-policy\",\"name\":\"不应修改\"}"))
        .andExpect(status().isBadRequest()); em.flush(); assertEquals("其他空间", em.find(FieldScope.class, "foreign-policy").getName());
  }

  @Test void createCannotSmuggleAProtectedRequiredNameAndFailsBeforeAnySqlInsertOrRefresh() throws Exception {
    context.setFieldPermissions(Map.of(FieldScope.class.getName() + "#name", "VISIBLE"));
    mvc.perform(post("/field-scopes").contentType("application/json").content("{\"name\":\"伪造保护字段\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PERMISSION_POLICY"));
    mvc.perform(post("/field-scopes/configuration").contentType("application/json").content("{\"name\":\"伪造保护字段\",\"entries\":[]}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PERMISSION_POLICY"));
    em.flush(); assertEquals(0, factory.getStatistics().getEntityInsertCount()); verifyNoInteractions(versions);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"hidden-name", "masked-name", "hidden-entries", "masked-entries", "masked-child", "hidden-description", "masked-description"})
  void protectedBrowserSchemaAndRowsAreActualHttpContracts(String mode) throws Exception {
    mvc.perform(put("/field-scopes/configuration").param("id", "own-policy").contentType("application/json")
        .content(body("原名称", "name", FieldAccessType.MASKED))).andExpect(status().isOk()); em.flush();
    context.setFieldPermissions(switch (mode) {
      case "hidden-name" -> Map.of(FieldScope.class.getName() + "#name", "HIDDEN");
      case "masked-name" -> Map.of(FieldScope.class.getName() + "#name", "MASKED");
      case "hidden-entries" -> Map.of(FieldScope.class.getName() + "#entries", "HIDDEN");
      case "masked-entries" -> Map.of(FieldScope.class.getName() + "#entries", "MASKED");
      case "masked-child" -> Map.of(FieldScopeEntry.class.getName() + "#resource", "MASKED");
      case "hidden-description" -> Map.of(FieldScope.class.getName() + "#description", "HIDDEN");
      case "masked-description" -> Map.of(FieldScope.class.getName() + "#description", "MASKED");
      default -> throw new IllegalArgumentException(mode);
    });
    var schema = mvc.perform(get("/field-scopes/schema")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    var page = mvc.perform(get("/field-scopes").param("page", "0").param("size", "20")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    var source = mapper.readTree(page).path("content").get(0);
    var row = mapper.createObjectNode();
    // Snapshot only the exact projected values consumed by this editor; omit nondeterministic audit timestamps/row IDs.
    for (var key : List.of("id", "name", "description", "entries")) if (source.has(key)) row.set(key, source.get(key));
    if (row.path("entries").isArray()) for (var entry : row.withArray("entries")) {
      var properties = new java.util.HashSet<String>(); entry.fieldNames().forEachRemaining(properties::add);
      properties.removeAll(List.of("resource", "field", "access")); ((com.fasterxml.jackson.databind.node.ObjectNode) entry).remove(properties);
    }
    var outputs = Map.of("schema", schema, "row", row.toString());
    for (var result : outputs.entrySet()) {
      String name = mode + "-" + result.getKey(); Path generated = Path.of("build/generated-test-resources/field-scope-" + name + ".json");
      Files.createDirectories(generated.getParent()); Files.writeString(generated, result.getValue());
    }
    for (var result : outputs.entrySet()) {
      String name = mode + "-" + result.getKey();
      assertEquals(mapper.readTree(Path.of("../../simplepoint-react/scripts/field-scope-contracts/" + name + ".json").toFile()), mapper.readTree(result.getValue()),
          "Protected browser schema/row must match actual HTTP projection: " + name);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"VISIBLE", "MASKED", "HIDDEN"})
  void createWithoutEntriesWorksThroughBothRealEndpointsWhenOnlyTheCollectionIsProtected(String level) throws Exception {
    context.setFieldPermissions(Map.of(FieldScope.class.getName() + "#entries", level));
    for (var endpoint : List.of("/field-scopes", "/field-scopes/configuration")) {
      var result = mvc.perform(post(endpoint).contentType("application/json").content("{\"name\":\"合法空规则\",\"description\":\"基础信息\",\"entries\":[]}"))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
      String id = mapper.readTree(result).path("id").asText(); em.flush(); em.clear();
      var saved = repository.findByIdAndTenantIdAndDeletedAtIsNull(id, "scope-tenant").orElseThrow();
      assertEquals("合法空规则", saved.getName()); assertTrue(saved.getEntries().isEmpty());
    }
    assertEquals(0, metadataQueries()); verify(versions, times(3)).refreshTenant("scope-tenant");
  }

  @Test void genericMetadataWriterNeverAcceptsRuleInjectionOrChangesEntriesOnAnInvalidMetadataRequest() throws Exception {
    mvc.perform(put("/field-scopes/configuration").param("id", "own-policy").contentType("application/json")
        .content(body("原名称", "name", FieldAccessType.MASKED))).andExpect(status().isOk()); em.flush();
    var original = em.find(FieldScope.class, "own-policy"); var id = original.getEntries().getFirst().getId();
    factory.getStatistics().clear(); clearInvocations(versions);
    mvc.perform(put("/field-scopes").contentType("application/json").content(body("规则注入", "email", FieldAccessType.VISIBLE).replaceFirst("\\{", "{\"id\":\"own-policy\",")))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PERMISSION_POLICY"));
    mvc.perform(put("/field-scopes").contentType("application/json").content("{\"id\":\"own-policy\",\"name\":\" \",\"entries\":[]}"))
        .andExpect(status().isBadRequest());
    em.flush(); assertEquals("原名称", original.getName()); assertEquals(id, original.getEntries().getFirst().getId());
    assertEquals(FieldAccessType.MASKED, original.getEntries().getFirst().getAccess());
    assertEquals(0, factory.getStatistics().getEntityUpdateCount()); assertEquals(0, metadataQueries()); verifyNoInteractions(versions);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"VISIBLE", "MASKED", "HIDDEN", "MASKED_CHILD"})
  void genericPartialUpdatePreservesUnreadableAndReadonlyCollectionRowsWithoutCatalogAccess(String level) throws Exception {
    mvc.perform(put("/field-scopes/configuration").param("id", "own-policy").contentType("application/json")
        .content(body("原名称", "name", FieldAccessType.MASKED))).andExpect(status().isOk()); em.flush();
    String entryId = em.find(FieldScope.class, "own-policy").getEntries().getFirst().getId();
    context.setFieldPermissions(level.equals("MASKED_CHILD") ? Map.of(FieldScopeEntry.class.getName() + "#resource", "MASKED")
        : Map.of(FieldScope.class.getName() + "#entries", level));
    factory.getStatistics().clear(); clearInvocations(versions);
    mvc.perform(put("/field-scopes").contentType("application/json").content("{\"id\":\"own-policy\",\"name\":\"合法基础信息\",\"description\":\"保留原始规则\"}"))
        .andExpect(status().isOk()); em.flush(); em.clear();
    var row = repository.findByIdAndTenantIdAndDeletedAtIsNull("own-policy", "scope-tenant").orElseThrow();
    assertEquals("合法基础信息", row.getName()); assertEquals("保留原始规则", row.getDescription()); assertEquals(1, row.getEntries().size());
    var entry = row.getEntries().getFirst(); assertEquals(entryId, entry.getId()); assertEquals(MODEL, entry.getResource());
    assertEquals("name", entry.getField()); assertEquals(FieldAccessType.MASKED, entry.getAccess());
    assertEquals(0, factory.getStatistics().getEntityInsertCount()); assertEquals(0, factory.getStatistics().getEntityDeleteCount());
    assertEquals(0, metadataQueries()); verify(versions).refreshTenant("scope-tenant");
  }
}
