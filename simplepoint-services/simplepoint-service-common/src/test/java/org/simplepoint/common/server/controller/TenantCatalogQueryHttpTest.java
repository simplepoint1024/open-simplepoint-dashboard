package org.simplepoint.common.server.controller;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Transient;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.core.handler.GlobalExceptionHandler;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.PackageApplicationRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantUserRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;

/** Real list/schema endpoints with SQL and authorization collaborators at their data boundary. */
class TenantCatalogQueryHttpTest {
  private SessionFactory factory;
  private Session session;
  private MockMvc mvc;
  private String path;
  private AuthorizationContext context;
  private TenantPackageRelevanceRepository grants;
  private PackageApplicationRelevanceRepository assignments;
  private TenantRepository candidateTenants;
  private TenantUserRelevanceRepository tenantMembers;

  @SuppressWarnings({"rawtypes", "unchecked"})
  void setup(String name) throws Exception {
    Class<?> entityType = Class.forName("org.simplepoint.plugin.rbac.tenant.api.entity." + name);
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", java.util.Optional.ofNullable(System.getenv("TENANT_CATALOG_DB_URL"))
            .filter(value -> !value.isBlank()).orElseGet(() -> "jdbc:h2:mem:tenant-catalog-" + UUID.randomUUID()))
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
    factory = new MetadataSources(registry).addAnnotatedClass(entityType).buildMetadata().buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    var first = seed(entityType, "match-a", 1, "tenant-a", false);
    var second = seed(entityType, "match-b", 2, "tenant-a", false);
    seed(entityType, "match-hidden", 3, "tenant-b", false);
    var deleted = seed(entityType, "match-deleted", 4, "tenant-a", true);
    session.flush();
    session.clear();
    authorize(false);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken("reader", "unused", List.of()));
    Class<?> repositoryType = Class.forName("org.simplepoint.plugin.rbac.tenant.api.repository." + name + "Repository");
    Object repository = mock(repositoryType,
        withSettings().defaultAnswer(delegatesTo(new BaseRepositoryImpl(entityType, session))));
    if (repository instanceof org.simplepoint.plugin.rbac.tenant.api.repository.OrganizationRepository organizations) {
      org.mockito.Mockito.doAnswer(invocation -> session.createQuery(
          "select distinct o.parentId from Organization o where o.parentId in :ids and o.tenantId = :tenant and o.deletedAt is null",
          String.class).setParameter("ids", invocation.getArgument(0))
          .setParameter("tenant", invocation.getArgument(1)).getResultList())
          .when(organizations).findParentIdsWithChildren(org.mockito.ArgumentMatchers.anyCollection(),
              org.mockito.ArgumentMatchers.anyString());
    }
    grants = mock(TenantPackageRelevanceRepository.class);
    assignments = mock(PackageApplicationRelevanceRepository.class);
    candidateTenants = mock(TenantRepository.class);
    var codes = new LinkedHashSet<>(Stream.concat(Stream.of("code-1", "code-2", "code-4"),
        java.util.stream.IntStream.range(0, 150).mapToObj(i -> "unused-" + i)).toList());
    when(grants.findApplicationCodesByTenantId("tenant-a")).thenReturn(codes);
    when(grants.authorized("tenant-a")).thenReturn(codes);
    var members = mock(TenantUserRelevanceRepository.class); tenantMembers = members;
    if (repository instanceof TenantRepository tenants) {
      doReturn(Optional.of((Tenant) first)).when(tenants).findPersonalTenantByOwnerId("reader");
      doReturn(Set.of(new NamedTenantVo(first.getId(), "match-a", TenantType.PERSONAL),
          new NamedTenantVo(second.getId(), "match-b", TenantType.ORGANIZATION),
          new NamedTenantVo(deleted.getId(), "match-deleted", TenantType.ORGANIZATION)))
          .when(tenants).getTenantsByUserId("reader");
      when(members.authorized(first.getId())).thenReturn(List.of("reader"));
    }
    Class<?> serviceType = Class.forName("org.simplepoint.plugin.rbac.tenant.service.impl." + name + "ServiceImpl");
    var constructor = serviceType.getConstructors()[0];
    var types = constructor.getParameterTypes();
    Object[] args = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      args[i] = types[i] == repositoryType ? repository
          : types[i] == DetailsProviderService.class ? schemaProvider()
          : types[i] == TenantPackageRelevanceRepository.class ? grants
          : types[i] == PackageApplicationRelevanceRepository.class ? assignments
          : types[i] == TenantRepository.class ? candidateTenants
          : types[i] == TenantUserRelevanceRepository.class ? members : mock(types[i]);
    }
    var service = constructor.newInstance(args);
    Class<?> controller = Class.forName("org.simplepoint.plugin.rbac.tenant.rest.controller." + name + "Controller");
    path = controller.getAnnotation(RequestMapping.class).value()[0];
    mvc = MockMvcBuilders.standaloneSetup(controller.getConstructors()[0].newInstance(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(
            tools.jackson.databind.json.JsonMapper.builder().addModule(new org.simplepoint.core.jackson.FieldScopeJackson3Module()).build()))
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
  }

  private void authorize(boolean administrator) {
    context = new AuthorizationContext();
    context.setUserId("reader");
    context.setIsAdministrator(administrator);
    context.setDataScopeType("ALL");
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private void platformPriceEditor() {
    // Context identity/administrator/attributes are write-once: use a fresh actual platform scope.
    context = new AuthorizationContext(); context.setUserId("price-editor"); context.setIsAdministrator(true);
    context.setScopeType(org.simplepoint.core.AuthorizationScopeType.PLATFORM); context.setDataScopeType("ALL");
    context.setAttributes(Map.of("X-Tenant-Id", "__platform__"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
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

  @SuppressWarnings("unchecked")
  private BaseEntityImpl<String> seed(Class<?> type, String name, int index, String tenantId, boolean deleted)
      throws Exception {
    var entity = (BaseEntityImpl<String>) type.getConstructor().newInstance();
    var bean = new BeanWrapperImpl(entity);
    for (var field : type.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Transient.class)) continue;
      Class<?> scalar = field.getType();
      Object value = scalar == String.class ? "code-" + index : scalar == Integer.class ? index
          : scalar == Long.class ? (long) index : scalar == BigDecimal.class ? BigDecimal.valueOf(index)
          : scalar == Boolean.class ? Boolean.TRUE : scalar == TenantType.class ? TenantType.ORGANIZATION : null;
      if (value != null) bean.setPropertyValue(field.getName(), value);
    }
    bean.setPropertyValue("name", name);
    if (entity instanceof TenantBaseEntityImpl<?> scoped) scoped.setTenantId(tenantId);
    entity.setCreatedBy("other-owner");
    if (deleted) entity.setDeletedAt(Instant.now());
    session.persist(entity);
    return entity;
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    SecurityContextHolder.clearContext();
    if (session != null) {
      if (session.getTransaction().isActive()) session.getTransaction().rollback();
      session.close();
    }
    if (factory != null) factory.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Application", "Package", "Tenant", "Organization"})
  void advancedFiltersCountAndSortRemainInsideAuthorization(String name) throws Exception {
    setup(name);
    String query = """
        {"version":1,"where":{"op":"or","children":[
          {"field":"name","op":"like","value":"match"},
          {"field":"name","op":"equals","value":"match-hidden"}]}}
        """;
    for (int page = 0; page < 2; page++) {
      mvc.perform(get(path).param("$query", query).param("size", "1").param("page", String.valueOf(page))
              .param("sort", "name,desc").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
          .andExpect(jsonPath("$.content[0].name").value(page == 0 ? "match-b" : "match-a"));
    }
    String field = name.equals("Tenant") ? "name" : "code";
    String value = name.equals("Tenant") ? "match-a" : "code-1";
    mvc.perform(get(path).param("$query", query).param(field,
            "{\"version\":1,\"where\":{\"field\":\"" + field + "\",\"op\":\"equals\",\"value\":\"" + value + "\"}}")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].name").value("match-a"));
    mvc.perform(get(path).param(field, "equals:" + (name.equals("Tenant") ? "match-hidden" : "code-3"))
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Application", "Package", "Tenant", "Organization"})
  void schemaAndValidationMatchTheQueryCompiler(String name) throws Exception {
    setup(name);
    mvc.perform(get(path + "/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.schema['x-query'].fields.name.sortable").value(true))
        .andExpect(jsonPath("$.schema['x-query'].fields.tenantId").doesNotExist())
        .andExpect(jsonPath("$.schema['x-query'].fields.authorizationVersion").doesNotExist());
    for (String field : List.of("tenantId", "authorizationVersion", "ownerPhoneNumber", "deletedAt")) {
      mvc.perform(get(path).param("$query", "{\"version\":1,\"where\":{\"field\":\"" + field
              + "\",\"op\":\"is:null\"}}")
              .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }
    mvc.perform(get(path).param("size", "201").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    authorize(true);
    mvc.perform(get(path).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(name.equals("Organization") ? 2 : 3));
  }

  @org.junit.jupiter.api.Test
  void tenantOwnerReadOnlySchemaPreservesTheRealPickerAndIndependentEditGrant() throws Exception {
    setup("Tenant");
    context.setResources(Set.of("tenants.edit"));
    context.setFieldPermissions(Map.of(Tenant.class.getName() + "#ownerId", "VISIBLE"));
    var response = mvc.perform(get(path + "/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema.properties.ownerId.readOnly").value(true))
        .andExpect(jsonPath("$.schema.properties.ownerId['x-field-access']").value("VISIBLE"))
        .andExpect(jsonPath("$.schema.properties.ownerId['x-ui'].widget").value("UserPicker"))
        .andExpect(jsonPath("$.buttons.length()").value(1))
        .andExpect(jsonPath("$.buttons[0].key").value("edit")).andReturn();
    var mapper = new ObjectMapper();
    var output = java.nio.file.Path.of("build/generated-test-resources/tenant-readonly-owner-schema.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(output, response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    org.junit.jupiter.api.Assertions.assertEquals(
        mapper.readTree(java.nio.file.Path.of("../../simplepoint-react/scripts/tenant-readonly-owner-schema.fixture.json").toFile()),
        mapper.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)),
        "Browser form contract must match the actual permission-filtered HTTP schema");
  }

  @org.junit.jupiter.api.Test
  void omittedReadOnlyTenantOwnerSurvivesActualMetadataHttpWrites() throws Exception {
    setup("Tenant"); platformPriceEditor();
    context.setFieldPermissions(Map.of(Tenant.class.getName() + "#ownerId", "VISIBLE"));
    var tenant = session.createQuery("from Tenant where name = 'match-a'", Tenant.class).getSingleResult();
    String id = tenant.getId(), owner = tenant.getOwnerId(); tenant.setLogo(null); tenant.setBackgroundImage(null); session.flush(); session.clear();
    when(tenantMembers.existingUserIds(Set.of(owner))).thenReturn(Set.of(owner));
    var mapper = new ObjectMapper();
    var row = mapper.readTree(mvc.perform(get(path).param("name", "equals:match-a")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("content").get(0);
    var payload = mapper.convertValue(row, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
    payload.remove("ownerId"); payload.put("description", "Metadata without owner reassignment");
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(payload)).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk());
    session.flush(); session.clear();
    var persisted = session.find(Tenant.class, id); org.junit.jupiter.api.Assertions.assertEquals(owner, persisted.getOwnerId());
    org.junit.jupiter.api.Assertions.assertEquals("Metadata without owner reassignment", persisted.getDescription());
  }

  @ParameterizedTest
  @ValueSource(strings = {"Tenant", "Package", "Application"})
  void configurationBrowserSchemasComeFromActualPermissionFilteredHttpContracts(String name) throws Exception {
    setup(name);
    context.setResources(name.equals("Tenant") ? Set.of("tenants.edit", "tenants.config.package")
        : name.equals("Package") ? Set.of("packages.config.application") : Set.of());
    if (name.equals("Tenant")) context.setFieldPermissions(Map.of(Tenant.class.getName() + "#ownerId", "VISIBLE"));
    var response = mvc.perform(get(path + "/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.schema['x-query'].version").value(1))
        .andExpect(jsonPath("$.buttons.length()").value(name.equals("Tenant") ? 2 : name.equals("Package") ? 1 : 0)).andReturn();
    var output = java.nio.file.Path.of("build/generated-test-resources/configuration-" + name.toLowerCase() + "-schema.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(output, response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    var mapper = new ObjectMapper();
    org.junit.jupiter.api.Assertions.assertEquals(
        mapper.readTree(java.nio.file.Path.of("../../simplepoint-react/scripts/configuration-" + name.toLowerCase() + "-schema.fixture.json").toFile()),
        mapper.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)),
        "Configuration browser fixtures must match their real permission-filtered HTTP schemas");
  }

  @org.junit.jupiter.api.Test
  void packagePriceSchemaAndHttpQueryKeepTheExactDatabaseBoundary() throws Exception {
    setup("Package");
    platformPriceEditor();
    var pkg = session.createQuery("from Package where code = 'code-1'", org.simplepoint.plugin.rbac.tenant.api.entity.Package.class).getSingleResult();
    pkg.setPrice(new BigDecimal("99999999999999999.99")); session.flush(); session.clear();
    mvc.perform(get(path).param("price", "{\"version\":1,\"where\":{\"field\":\"price\",\"op\":\"equals\",\"value\":\"99999999999999999.99\"}}")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].price").isString())
        .andExpect(jsonPath("$.content[0].price").value("99999999999999999.99"));
    var response = mvc.perform(get(path + "/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.schema.properties.price['x-decimal'].scale").value(2))
        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var output = java.nio.file.Path.of("build/generated-test-resources/exact-decimal-package-schema.json");
    java.nio.file.Files.createDirectories(output.getParent()); java.nio.file.Files.writeString(output, response);
    var fixture = java.nio.file.Path.of("../../simplepoint-react/scripts/exact-decimal-package-schema.fixture.json");
    var mapper = new ObjectMapper(); org.junit.jupiter.api.Assertions.assertEquals(mapper.readTree(fixture.toFile()), mapper.readTree(response));
    context.setFieldPermissions(Map.of(org.simplepoint.plugin.rbac.tenant.api.entity.Package.class.getName() + "#price", "MASKED"));
    mvc.perform(get(path).param("code", "equals:code-1").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].price").value("***"));
    var masked = mvc.perform(get(path + "/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.schema.properties.price.readOnly").value(true))
        .andExpect(jsonPath("$.schema.properties.price['x-field-access']").value("MASKED"))
        .andExpect(jsonPath("$.schema.properties.price.pattern").value("^\\*+$")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    java.nio.file.Files.writeString(java.nio.file.Path.of("build/generated-test-resources/exact-decimal-package-masked-schema.json"), masked);
    org.junit.jupiter.api.Assertions.assertEquals(mapper.readTree(java.nio.file.Path.of("../../simplepoint-react/scripts/exact-decimal-package-masked-schema.fixture.json").toFile()), mapper.readTree(masked));
  }

  @org.junit.jupiter.api.Test
  void packagePriceWritesRejectDatabaseRoundingButProtectedPricesSurviveMetadataEdits() throws Exception {
    setup("Package"); platformPriceEditor();
    var pkg = session.createQuery("from Package where code = 'code-1'", org.simplepoint.plugin.rbac.tenant.api.entity.Package.class).getSingleResult();
    var id = pkg.getId(); pkg.setPrice(new BigDecimal("99999999999999999.99")); session.flush(); session.clear();
    var mapper = new ObjectMapper(); var row = mapper.readTree(mvc.perform(get(path).param("code", "equals:code-1")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andReturn().getResponse().getContentAsString()).path("content").get(0);
    var payload = mapper.convertValue(row, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
    payload.put("price", "0.001");
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(payload)).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DECIMAL_VALUE"));
    org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("99999999999999999.99"), session.find(org.simplepoint.plugin.rbac.tenant.api.entity.Package.class, id).getPrice());
    context.setFieldPermissions(Map.of(org.simplepoint.plugin.rbac.tenant.api.entity.Package.class.getName() + "#price", "VISIBLE"));
    payload.remove("price"); payload.put("description", "Protected exact metadata");
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(payload)).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk());
    session.flush(); session.clear();
    org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("99999999999999999.99"), session.find(org.simplepoint.plugin.rbac.tenant.api.entity.Package.class, id).getPrice());
  }

  @org.junit.jupiter.api.Test
  void applicationResourceBrowserUsesTheActualConfigurationActionContract() throws Exception {
    setup("Application");
    context.setResources(Set.of("applications.config.resource"));
    var response = mvc.perform(get(path + "/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.buttons.length()").value(1))
        .andExpect(jsonPath("$.buttons[0].key").value("config.resource")).andReturn();
    var output = java.nio.file.Path.of("build/generated-test-resources/application-resource-root-schema.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(output, response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    var mapper = new ObjectMapper();
    org.junit.jupiter.api.Assertions.assertEquals(
        mapper.readTree(java.nio.file.Path.of("../../simplepoint-react/scripts/application-resource-root-schema.fixture.json").toFile()),
        mapper.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)),
        "The resource configuration browser must use the actual permission-filtered application schema");
  }

  @org.junit.jupiter.api.Test
  void tenantIdentityLookupDoesNotBypassAuthorizedMemberships() throws Exception {
    setup("Tenant");
    for (var tenant : session.createQuery("from Tenant", org.simplepoint.plugin.rbac.tenant.api.entity.Tenant.class).getResultList()) {
      boolean allowed = tenant.getDeletedAt() == null && Set.of("match-a", "match-b").contains(tenant.getName());
      mvc.perform(get(path).param("id", "{\"version\":1,\"where\":{\"field\":\"id\",\"op\":\"equals\",\"value\":\"" + tenant.getId() + "\"}}")
          .param("size", "1").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(allowed ? 1 : 0));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"Application", "Package"})
  void assignmentPanelsFilterAndCountBeforePagingAndExcludeDeleted(String name) throws Exception {
    setup(name);
    authorize(true);
    var tenant = new Tenant();
    tenant.setId("target");
    when(candidateTenants.findById("target")).thenReturn(Optional.of(tenant));
    var selected = new LinkedHashSet<>(java.util.stream.IntStream.range(0, 150)
        .mapToObj(i -> "missing-" + i).toList());
    selected.addAll(List.of("code-1", "code-4"));
    when(assignments.authorized("target")).thenReturn(selected);
    when(grants.authorized("target")).thenReturn(selected);
    String parent = name.equals("Application") ? "packageCode" : "tenantId";
    mvc.perform(get(path + "/items/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.schema['x-query'].fields.code.sortable").value(true));
    mvc.perform(get(path + "/items").param(parent, "target").param("selected", "true")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].code").value("code-1"));
    for (int page = 0; page < 2; page++) {
      mvc.perform(get(path + "/items").param(parent, "target").param("selected", "false")
              .param("size", "1").param("page", String.valueOf(page)).param("sort", "code,desc")
              .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
          .andExpect(jsonPath("$.content[0].code").value("code-" + (3 - page)));
    }
    mvc.perform(get(path + "/items").param(parent, "target").param("selected", "false")
            .param("$query", "{\"version\":1,\"where\":{\"field\":\"code\",\"op\":\"equals\",\"value\":\"code-1\"}}")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get(path + "/items").param(parent, "target").param("selected", "false")
            .param("code", "equals:code-2").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    when(assignments.authorized("target")).thenReturn(List.of());
    when(grants.authorized("target")).thenReturn(List.of());
    mvc.perform(get(path + "/items").param(parent, "target").param("selected", "true")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get(path + "/items").param(parent, "target").param("selected", "false")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
  }

  @org.junit.jupiter.api.Test
  void organizationTreeCountsRootsAndPagesAllChildrenInsideMandatoryParentAndTenant() throws Exception {
    setup("Organization");
    session.createMutationQuery("update Organization o set o.parentId = null").executeUpdate();
    String parent = session.createQuery("select o.id from Organization o where o.code = 'code-1'", String.class)
        .getSingleResult();
    for (int index = 0; index < 127; index++) {
      var child = (org.simplepoint.plugin.rbac.tenant.api.entity.Organization) seed(
          org.simplepoint.plugin.rbac.tenant.api.entity.Organization.class,
          "child-" + index, 1000 + index, index == 125 ? "tenant-b" : "tenant-a", index == 126);
      child.setParentId(parent);
    }
    session.flush(); session.clear();
    mvc.perform(get(path + "/tree").param("size", "1").param("sort", "code,asc")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.content[0].hasChildren").value(true));
    mvc.perform(get(path + "/tree").param("treeParentId", parent).param("page", "2").param("size", "50")
            .param("sort", "sort,asc")
            .param("$query", "{\"version\":1,\"where\":{\"op\":\"or\",\"children\":[{\"field\":\"name\",\"op\":\"like\",\"value\":\"child\"},{\"field\":\"parentId\",\"op\":\"is:null\"}]}}")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(125))
        .andExpect(jsonPath("$.content.length()").value(25))
        .andExpect(jsonPath("$.content[0].name").value("child-100"));
    mvc.perform(get(path + "/tree").param("treeParentId", parent).param("parentId", "is:null")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get(path + "/tree").param("treeParentId", parent).param("size", "201")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
  }

  @org.junit.jupiter.api.Test
  void organizationTreeProjectionAndDefaultOrderRespectFieldPolicies() throws Exception {
    setup("Organization");
    session.createMutationQuery("update Organization o set o.parentId = null").executeUpdate();
    String resource = org.simplepoint.plugin.rbac.tenant.api.entity.Organization.class.getName();
    context.setFieldPermissions(Map.of(resource + "#name", "HIDDEN", resource + "#sort", "HIDDEN",
        resource + "#code", "MASKED"));
    mvc.perform(get(path + "/tree").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].name").doesNotExist())
        .andExpect(jsonPath("$.content[0].sort").doesNotExist())
        .andExpect(jsonPath("$.content[0].code").value("***"));
    mvc.perform(get(path + "/tree").param("sort", "code,asc")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
  }

  @org.junit.jupiter.api.Test
  void tenantApplicationsIntersectAssignmentAndCandidateScopes() throws Exception {
    setup("Application");
    when(assignments.authorized("code-1")).thenReturn(List.of("code-2", "code-3"));
    for (boolean selected : List.of(false, true)) {
      mvc.perform(get(path + "/items").param("packageCode", "code-1")
              .param("selected", String.valueOf(selected))
              .param("$query", "{\"version\":1,\"where\":{\"field\":\"name\",\"op\":\"like\",\"value\":\"match\"}}")
              .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
          .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
          .andExpect(jsonPath("$.content[0].code").value(selected ? "code-2" : "code-1"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"Application", "Package"})
  void assignmentPanelsRejectUnauthorizedParentBeforeQuery(String name) throws Exception {
    setup(name);
    String parent = name.equals("Application") ? "packageCode" : "tenantId";
    for (boolean selected : List.of(false, true)) {
      var failure = assertThrows(jakarta.servlet.ServletException.class, () ->
          mvc.perform(get(path + "/items").param(parent, "outside-scope")
              .param("selected", String.valueOf(selected))
              .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)));
      assertInstanceOf(org.springframework.security.access.AccessDeniedException.class, failure.getCause());
    }
    org.mockito.Mockito.verifyNoInteractions(assignments, candidateTenants);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Application", "Package"})
  void emptyGrantSetDeniesAllWithoutSkippingQueryValidation(String name) throws Exception {
    setup(name);
    when(grants.findApplicationCodesByTenantId("tenant-a")).thenReturn(Set.of());
    when(grants.authorized("tenant-a")).thenReturn(Set.of());
    mvc.perform(get(path).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get(path).param("unknown", "is:null")
            .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
  }
}
