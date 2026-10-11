package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.core.*;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.plugin.rbac.resource.base.repository.*;
import org.simplepoint.plugin.rbac.resource.rest.endpoint.ResourcesController;
import org.simplepoint.plugin.rbac.resource.service.impl.ResourceServiceImpl;
import org.simplepoint.plugin.rbac.resource.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.tenant.api.entity.*;
import org.simplepoint.security.entity.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Native entitlement joins, real tree pagination, and production field serialization. No pre-filtered repository. */
class ResourceDirectoryHttpTest {
  SessionFactory factory; Session em; MockMvc mvc; ResourceServiceImpl service; AuthorizationContext context;
  boolean snakeCase, caseInsensitiveCollation;
  org.springframework.context.support.GenericApplicationContext schemaContext;
  final List<String> statements = new ArrayList<>();

  @BeforeEach void setup() throws Exception {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:resource-directory-" + UUID.randomUUID()
            + (caseInsensitiveCollation ? ";COLLATION=ENGLISH STRENGTH PRIMARY" : ""))
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.physical_naming_strategy", snakeCase ? "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy" : "org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl")
        .applySetting("hibernate.session_factory.statement_inspector", (org.hibernate.resource.jdbc.spi.StatementInspector) sql -> {statements.add(sql); return sql;}).build())
        .addAnnotatedClass(Resource.class).addAnnotatedClass(ResourceAncestor.class).addAnnotatedClass(Tenant.class).addAnnotatedClass(TenantResource.class)
        .addAnnotatedClass(TenantPackageRelevance.class).addAnnotatedClass(PackageApplicationRelevance.class)
        .addAnnotatedClass(org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary.class).addAnnotatedClass(DictionaryItem.class)
        .addAnnotatedClass(ApplicationResourceRelevance.class).buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction();
    var repositories = new JpaRepositoryFactory(em); repositories.setRepositoryBaseClass(BaseRepositoryImpl.class);
    var repository = repositories.getRepository(JpaResourceRepository.class);
    var ancestors = new JpaRepositoryFactory(em).getRepository(JpaResourceAncestorRepository.class);
    var schemaConfig = new org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration(new com.fasterxml.jackson.databind.ObjectMapper());
    var generator = schemaConfig.schemaGenerator(Set.of(schemaConfig.openApiModule(), schemaConfig.jacksonModule(), schemaConfig.javaxValidationModule()));
    schemaContext = new org.springframework.context.support.GenericApplicationContext();
    schemaContext.registerBean(org.simplepoint.api.security.service.JsonSchemaDetailsService.class,
        () -> new org.simplepoint.data.json.schema.service.DefaultJsonSchemaDetailsService(em));
    schemaContext.registerBean(org.simplepoint.api.security.generator.JsonSchemaGenerator.class, () -> schemaConfig.defaultJsonSchemaGenerator(generator)); schemaContext.refresh();
    var details = new org.simplepoint.core.security.SpringBeanDetailsProviderService(); details.setApplicationContext(schemaContext);
    var licenses = new JpaRepositoryFactory(em).getRepository(org.simplepoint.plugin.rbac.tenant.repository.JpaTenantPackageRelevanceRepository.class);
    service = new ResourceServiceImpl(repository, details, ancestors, mock(MicroAppService.class), provider(licenses), provider(), provider(), provider(), provider(), provider());
    var field = ResourceServiceImpl.class.getDeclaredField("permissionEntityManager"); field.setAccessible(true); field.set(service, em);
    context = caller("tenant-a", AuthorizationScopeType.TENANT, false);
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("verified-user", "unused", List.of()));
    var dictionaryService = new org.simplepoint.plugin.rbac.tenant.service.impl.DictionaryServiceImpl(
        repositories.getRepository(org.simplepoint.plugin.rbac.tenant.repository.JpaDictionaryRepository.class), details,
        repositories.getRepository(org.simplepoint.plugin.rbac.tenant.repository.JpaDictionaryItemRepository.class));
    mvc = MockMvcBuilders.standaloneSetup(new ResourcesController(service), new org.simplepoint.plugin.rbac.tenant.rest.controller.DictionaryController(dictionaryService))
        .addFilters(new org.springframework.security.web.access.ExceptionTranslationFilter(
            new org.springframework.security.web.authentication.HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED)))
        .setControllerAdvice(new org.simplepoint.core.handler.GlobalExceptionHandler())
        .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(
            tools.jackson.databind.json.JsonMapper.builder().addModule(new org.simplepoint.core.jackson.FieldScopeJackson3Module()).build()))
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
  }
  @SuppressWarnings("unchecked") private <T> ObjectProvider<T> provider() {return mock(ObjectProvider.class);}
  private <T> ObjectProvider<T> provider(T value) {var result = this.<T>provider(); when(result.getIfAvailable()).thenReturn(value); return result;}
  AuthorizationContext caller(String tenant, AuthorizationScopeType scope, boolean administrator) {
    var result = new AuthorizationContext(); result.setUserId("verified-user"); result.setScopeType(scope); result.setIsAdministrator(administrator);
    result.setAttributes(Map.of("X-Tenant-Id", tenant)); result.setResources(Set.of("resources.view")); return result;
  }
  Resource node(String id, String parent, int sort, ResourceType type, ResourceScopeType scope, boolean grantable) {
    var result = new Resource(); result.setId(id); result.setCode(id); result.setName("Node " + id); result.setParentId(parent);
    result.setSort(sort); result.setType(type); result.setScopeTypes(Set.of(scope)); result.setGrantable(grantable); result.setDisabled(false); result.setPublicAccess(false);
    em.persist(result); if (parent != null) {var relation = new ResourceAncestor(); relation.setAncestorId(parent); relation.setChildId(id); em.persist(relation);} return result;
  }
  void license(String tenant, String code) {
    var tp = new TenantPackageRelevance(); tp.setTenantId(tenant); tp.setPackageCode("package-" + tenant + "-" + code); em.persist(tp);
    var pa = new PackageApplicationRelevance(); pa.setPackageCode(tp.getPackageCode()); pa.setApplicationCode("app-" + code); em.persist(pa);
    var ar = new ApplicationResourceRelevance(); ar.setApplicationCode(pa.getApplicationCode()); ar.setResourceCode(code); em.persist(ar);
  }
  void customCatalog() {
    var tenant = new Tenant(); tenant.setId("tenant-a"); tenant.setName("Actual custom workspace"); tenant.setOwnerId("catalog-owner");
    tenant.setTenantType(TenantType.ORGANIZATION); tenant.setAuthorizationVersion(0L); tenant.setResourceMode(TenantResourceMode.CUSTOM); em.persist(tenant);
    service.setTenantResourceCatalog(new org.simplepoint.plugin.rbac.tenant.service.impl.TenantResourceCatalogImpl(em,
        mock(org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository.class),
        mock(org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository.class),
        mock(org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository.class)));
  }
  TenantResource customNode(String id, String parent, String code, String target) {
    var row = new TenantResource(); row.setTenantId("tenant-a"); row.setId(id); row.setParentId(parent); row.setCode(code); row.setTargetResourceId(target);
    row.setName("Actual tenant label " + id); row.setType(ResourceType.PAGE); row.setPath("/tenant/" + id); row.setComponent("common/system/Resource");
    row.setRouteKind("item"); row.setEnabled(true); row.setGrantable(true); row.setSort(0); em.persist(row); return row;
  }
  void evidence() {em.flush(); em.clear(); statements.clear(); factory.getStatistics().clear();}
  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder children() {
    return get("/resources/children").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }
  @AfterEach void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();} if (factory != null) factory.close();
    if (schemaContext != null) schemaContext.close();
  }

  @Test void byCodeManagementSnapshotsAreCompleteAcrossScopesWithoutInternalMetadataOrUnboundedBinds() throws Exception {
    context = caller("__platform__", AuthorizationScopeType.PLATFORM, true); var codes = new LinkedHashSet<String>();
    for (int index = 0; index < 521; index++) {String code = "snapshot-" + String.format("%03d", index); codes.add(code);
      var resource = node(code, null, index, ResourceType.PAGE, ResourceScopeType.values()[index % 4], true);
      resource.setDescription("Actual snapshot description " + index); resource.setMethod("POST"); resource.setPattern("/actual/" + index);
      resource.setPermissionMetadata("Private internal metadata " + index);}
    node("deleted", null, 0, ResourceType.PAGE, ResourceScopeType.PLATFORM, true).setDeletedAt(Instant.now()); codes.add("deleted");
    var disabled = node("disabled", null, 522, ResourceType.PAGE, ResourceScopeType.TENANT, true); disabled.setDisabled(true); codes.add(disabled.getCode()); evidence();
    var body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(codes);
    var response = mvc.perform(post("/resources/by-codes").contentType("application/json").content(body)
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var rows = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response); assertEquals(522, rows.size());
    var actual = new LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>(); rows.forEach(row -> actual.put(row.path("code").asText(), row));
    assertEquals("Actual snapshot description 520", actual.get("snapshot-520").path("description").asText());
    assertEquals("/actual/520", actual.get("snapshot-520").path("pattern").asText()); assertTrue(actual.get("disabled").path("disabled").asBoolean());
    assertFalse(actual.containsKey("deleted")); assertFalse(response.contains("Private internal metadata")); assertBoundedMemberships();
  }

  @Test void byCodeLicenseQueriesCannotReturnRevokedDeletedDisabledOrForeignResources() throws Exception {
    node("owned", null, 0, ResourceType.PAGE, ResourceScopeType.TENANT, true); license("tenant-a", "owned");
    node("foreign", null, 1, ResourceType.PAGE, ResourceScopeType.TENANT, true); license("tenant-b", "foreign");
    node("revoked", null, 2, ResourceType.PAGE, ResourceScopeType.TENANT, true); license("tenant-a", "revoked");
    em.createQuery("update ApplicationResourceRelevance r set r.deletedAt=:now where r.resourceCode=:code").setParameter("now", Instant.now()).setParameter("code", "revoked").executeUpdate();
    node("deleted", null, 3, ResourceType.PAGE, ResourceScopeType.TENANT, true).setDeletedAt(Instant.now()); license("tenant-a", "deleted");
    node("disabled", null, 4, ResourceType.PAGE, ResourceScopeType.TENANT, true).setDisabled(true); license("tenant-a", "disabled");
    node("public", null, 5, ResourceType.PAGE, ResourceScopeType.TENANT, true).setPublicAccess(true);
    node("other-scope", null, 6, ResourceType.PAGE, ResourceScopeType.PLATFORM, true).setPublicAccess(true); evidence();
    mvc.perform(post("/resources/by-codes").contentType("application/json").content("[\"owned\",\"foreign\",\"revoked\",\"deleted\",\"disabled\",\"public\",\"other-scope\"]")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk()).andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[*].code", org.hamcrest.Matchers.containsInAnyOrder("owned", "public")));
    assertBoundedMemberships();
  }

  @Test void runtimeCodeQueriesWorkBeforeContextConstructionAndReadOnlyBoundedActualScalars() {
    var codes = new LinkedHashSet<String>();
    for (int index = 0; index < 521; index++) {String id = "runtime-" + String.format("%03d", index); codes.add(id);
      node(id, null, index, ResourceType.ACTION, ResourceScopeType.TENANT, index % 2 == 0);}
    var legacy = node("legacy-system", null, 600, ResourceType.ACTION, ResourceScopeType.SYSTEM, true); codes.add(legacy.getCode());
    node("platform", null, 601, ResourceType.ACTION, ResourceScopeType.PLATFORM, true); codes.add("platform");
    node("deleted", null, 602, ResourceType.ACTION, ResourceScopeType.TENANT, true).setDeletedAt(Instant.now()); codes.add("deleted");
    node("disabled", null, 603, ResourceType.ACTION, ResourceScopeType.TENANT, true).setDisabled(true); codes.add("disabled"); evidence();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    assertEquals(521, service.filterAccessibleCodes(codes, AuthorizationScopeType.TENANT, true).size());
    assertEquals(261, service.filterGrantableAccessibleCodes(codes, AuthorizationScopeType.TENANT).size());
    assertEquals(Set.of("legacy-system", "platform"), new HashSet<>(service.filterAccessibleCodes(codes, AuthorizationScopeType.PLATFORM, true)));
    assertEquals(Set.of("platform"), new HashSet<>(service.filterAccessibleCodes(codes, AuthorizationScopeType.PLATFORM, false)));
    assertEquals(521, service.findAllAccessibleCodes(AuthorizationScopeType.TENANT, false).size());
    assertBoundedMemberships(); assertFalse(statements.stream().filter(sql -> sql.startsWith("select")).anyMatch(sql -> sql.contains("permissionMetadata")));
  }

  @Test void publicExecutionCodesCannotResurrectDeletedOrDisabledResources() {
    node("public", null, 0, ResourceType.ACTION, ResourceScopeType.TENANT, false).setPublicAccess(true);
    var deleted = node("deleted-public", null, 1, ResourceType.ACTION, ResourceScopeType.PLATFORM, true); deleted.setPublicAccess(true); deleted.setDeletedAt(Instant.now());
    var disabled = node("disabled-public", null, 2, ResourceType.ACTION, ResourceScopeType.SYSTEM, true); disabled.setPublicAccess(true); disabled.setDisabled(true); evidence();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    assertEquals(Set.of("public"), new HashSet<>(service.findPublicAccessCodes())); assertBoundedMemberships();
  }

  @Test void authoringSnapshotsDoNotExpandTheExistingRuntimeScopeOfInternalResourceConsumers() throws Exception {
    var codes = new LinkedHashSet<String>();
    for (var scope : ResourceScopeType.values()) {String code = "scope-" + scope.name(); codes.add(code); node(code, null, scope.ordinal(), ResourceType.PAGE, scope, true);}
    evidence(); context = caller("__platform__", AuthorizationScopeType.PLATFORM, true);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertEquals(Set.of("scope-PLATFORM", "scope-SYSTEM"), service.findAllByCodes(codes).stream().map(Resource::getCode).collect(java.util.stream.Collectors.toSet()));
    mvc.perform(post("/resources/by-codes").contentType("application/json").content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(codes))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk()).andExpect(jsonPath("$.length()").value(4));
    context.setFieldPermissions(Map.of(Resource.class.getName() + "#code", "HIDDEN"));
    assertEquals(Set.of("scope-TENANT"), new HashSet<>(service.filterAccessibleCodes(codes, AuthorizationScopeType.TENANT, true)),
        "Context construction uses its explicit domain arguments, never a previous caller's scope or display-field restrictions");
    assertBoundedMemberships();
  }

  @Test void runtimeScalarScopesFollowTheActualConverterForLegacyEmptyAndNoncanonicalEnumSets() {
    node("legacy-empty", null, 0, ResourceType.ACTION, ResourceScopeType.SYSTEM, true);
    node("legacy-mixed", null, 1, ResourceType.ACTION, ResourceScopeType.TENANT, true); license("tenant-a", "legacy-mixed"); em.flush();
    em.createNativeMutationQuery("update simpoint_ac_resources set scope_types='' where id='legacy-empty'").executeUpdate();
    em.createNativeMutationQuery("update simpoint_ac_resources set scope_types='PERSONAL, TENANT' where id='legacy-mixed'").executeUpdate(); evidence();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY); var codes = Set.of("legacy-empty", "legacy-mixed");
    assertEquals(Set.of("legacy-empty"), new HashSet<>(service.filterAccessibleCodes(codes, AuthorizationScopeType.PLATFORM, true)));
    assertTrue(service.filterAccessibleCodes(codes, AuthorizationScopeType.PLATFORM, false).isEmpty());
    assertEquals(Set.of("legacy-mixed"), new HashSet<>(service.filterAccessibleCodes(codes, AuthorizationScopeType.PERSONAL, false)));
    assertEquals(Set.of("legacy-mixed"), new HashSet<>(service.findAllAccessibleCodes(AuthorizationScopeType.TENANT, true)));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertEquals(Set.of("legacy-mixed"), service.findAllByCodes(codes).stream().map(Resource::getCode).collect(java.util.stream.Collectors.toSet()));
    assertBoundedMemberships();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"MASKED", "HIDDEN"})
  void byCodeProtectedNamesRemainLawfulButUnreadableCodeQueriesIssueNoSql(String access) throws Exception {
    context = caller("__platform__", AuthorizationScopeType.PLATFORM, true); var resource = node("record", null, 0, ResourceType.PAGE, ResourceScopeType.TENANT, true);
    resource.setName("Private source name"); resource.setDescription("Actual readable description"); evidence();
    context.setFieldPermissions(Map.of(Resource.class.getName() + "#name", access));
    var response = mvc.perform(post("/resources/by-codes").contentType("application/json").content("[\"record\"]")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk()).andExpect(jsonPath("$[0].description").value("Actual readable description"))
        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var row = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get(0);
    if (access.equals("MASKED")) assertEquals("***", row.path("name").asText()); else assertFalse(row.has("name"));
    assertFalse(response.contains("Private source name")); context = caller("__platform__", AuthorizationScopeType.PLATFORM, true);
    context.setFieldPermissions(Map.of(Resource.class.getName() + "#code", access)); statements.clear();
    mvc.perform(post("/resources/by-codes").contentType("application/json").content("[\"record\"]")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isForbidden()); assertTrue(statements.isEmpty());
    assertEquals(0, factory.getStatistics().getEntityLoadCount());
  }

  @Test void byCodeSnapshotsRejectBlankCallerIdentityBeforeAnySql() throws Exception {
    node("record", null, 0, ResourceType.PAGE, ResourceScopeType.PLATFORM, true); evidence();
    context = new AuthorizationContext(); context.setUserId(" "); context.setScopeType(AuthorizationScopeType.PLATFORM); context.setIsAdministrator(true);
    mvc.perform(post("/resources/by-codes").contentType("application/json").content("[\"record\"]")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isForbidden()); assertTrue(statements.isEmpty());
  }

  @Test void completeLicensedCatalogPreservesAncestorsAndEveryResourceWithoutWholeEntityOrMembershipReads() {
    node("catalog-root", null, 0, ResourceType.GROUP, ResourceScopeType.TENANT, false); var expected = new HashSet<String>(); expected.add("catalog-root");
    for (int index = 0; index < 521; index++) {
      String id = "catalog-owned-" + String.format("%03d", index); expected.add(id); var resource = node(id, "catalog-root", index, ResourceType.ACTION, ResourceScopeType.TENANT, true);
      resource.setDescription("Actual catalog description " + index); resource.setPermissionMetadata("Private catalog metadata " + index); license("tenant-a", id);
      String foreign = "catalog-foreign-" + String.format("%03d", index); node(foreign, "catalog-root", index, ResourceType.ACTION, ResourceScopeType.TENANT, true); license("tenant-b", foreign);
    }
    evidence(); RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var actual = service.findAllAccessible(); assertEquals(expected, actual.stream().map(Resource::getId).collect(java.util.stream.Collectors.toSet()));
    assertEquals("Actual catalog description 520", actual.stream().filter(resource -> resource.getId().equals("catalog-owned-520")).findFirst().orElseThrow().getDescription());
    assertBoundedMemberships(); assertFalse(statements.stream().filter(sql -> sql.startsWith("select")).anyMatch(sql -> sql.contains("permissionMetadata")));
  }

  @Test void completeRoutesRetainHeldAndPublicPagesNotEveryLicensedPageAndNeverBindTheWholeClosure() throws Exception {
    node("route-root", null, 0, ResourceType.GROUP, ResourceScopeType.TENANT, false); var held = new LinkedHashSet<String>();
    for (int index = 0; index < 521; index++) {String id = "route-owned-" + String.format("%03d", index); held.add(id);
      var resource = node(id, "route-root", index, ResourceType.PAGE, ResourceScopeType.TENANT, true); resource.setPath("/actual/" + index); resource.setComponent("common/system/Resource");
      resource.setPermissionMetadata("Private route metadata " + index);}
    var unheld = node("licensed-not-held", "route-root", 522, ResourceType.PAGE, ResourceScopeType.TENANT, true); unheld.setPath("/not-held"); license("tenant-a", unheld.getCode());
    var publicPage = node("public-route", "route-root", 523, ResourceType.PAGE, ResourceScopeType.TENANT, true); publicPage.setPublicAccess(true); publicPage.setPath("/public");
    evidence(); context = caller("tenant-a", AuthorizationScopeType.TENANT, false, held);
    var response = mvc.perform(get("/resources/service-routes").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk())
        .andExpect(jsonPath("$.routes.length()").value(1)).andExpect(jsonPath("$.routes[0].children.length()").value(522))
        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(response.contains("route-owned-520")); assertTrue(response.contains("public-route")); assertFalse(response.contains("licensed-not-held"));
    assertFalse(response.contains("Private route metadata")); assertBoundedMemberships();
  }

  @Test void completeCatalogRetainsReadableAdministratorDisabledSettingsButNeverDeletedOrForeignScopeRows() {
    for (var scope : ResourceScopeType.values()) {
      String suffix = scope.name();
      for (String state : List.of("active", "disabled", "deleted")) {
        String id = "catalog-" + state + "-" + suffix; var resource = node(id, null, 0, ResourceType.PAGE, scope, true);
        resource.setPermissionMetadata("Private catalog state metadata"); license("tenant-a", id);
        if (state.equals("disabled")) resource.setDisabled(true);
        if (state.equals("deleted")) resource.setDeletedAt(Instant.now());
      }
    }
    evidence(); RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, caller("__platform__", AuthorizationScopeType.PLATFORM, true));
    assertEquals(Set.of("catalog-active-SYSTEM", "catalog-active-PLATFORM", "catalog-disabled-SYSTEM", "catalog-disabled-PLATFORM"),
        service.findAllAccessible().stream().map(Resource::getId).collect(java.util.stream.Collectors.toSet()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, caller("tenant-a", AuthorizationScopeType.TENANT, false));
    assertEquals(Set.of("catalog-active-TENANT"), service.findAllAccessible().stream().map(Resource::getId).collect(java.util.stream.Collectors.toSet()));
    assertBoundedMemberships(); assertFalse(statements.stream().filter(sql -> sql.startsWith("select")).anyMatch(sql -> sql.contains("permissionMetadata")));
  }

  AuthorizationContext caller(String tenant, AuthorizationScopeType scope, boolean administrator, Set<String> codes) {
    var result = new AuthorizationContext(); result.setUserId("verified-user"); result.setScopeType(scope); result.setIsAdministrator(administrator);
    result.setAttributes(Map.of("X-Tenant-Id", tenant)); result.setResources(codes); return result;
  }

  @Test void routeAncestorsCannotReintroduceDeletedOrForeignScopeNodes() throws Exception {
    node("foreign-scope-parent", null, 0, ResourceType.GROUP, ResourceScopeType.PLATFORM, false).setName("Private platform directory");
    node("deleted-parent", null, 1, ResourceType.GROUP, ResourceScopeType.TENANT, false).setDeletedAt(Instant.now());
    var first = node("first-held", "foreign-scope-parent", 0, ResourceType.PAGE, ResourceScopeType.TENANT, true); first.setPath("/first-held");
    var second = node("second-held", "deleted-parent", 1, ResourceType.PAGE, ResourceScopeType.TENANT, true); second.setPath("/second-held"); evidence();
    context = caller("tenant-a", AuthorizationScopeType.TENANT, false, Set.of("first-held", "second-held"));
    mvc.perform(get("/resources/service-routes").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk())
        .andExpect(jsonPath("$.routes[*].code", org.hamcrest.Matchers.containsInAnyOrder("first-held", "second-held")))
        .andExpect(jsonPath("$.routes.length()").value(2)); assertBoundedMemberships();
  }

  @Test void platformRuntimeRoutesExcludeDeletedDisabledAndOtherScopesWithoutLoadingInternalMetadata() throws Exception {
    for (int index = 0; index < 521; index++) {String id = "admin-route-" + String.format("%03d", index);
      var resource = node(id, null, index, ResourceType.PAGE, ResourceScopeType.values()[index % 4], true); resource.setPath("/admin/" + index); resource.setPermissionMetadata("Private admin route metadata " + index);}
    var deleted = node("deleted-route", null, 600, ResourceType.PAGE, ResourceScopeType.PLATFORM, true); deleted.setPath("/deleted"); deleted.setDeletedAt(Instant.now());
    var disabled = node("disabled-route", null, 601, ResourceType.PAGE, ResourceScopeType.SYSTEM, true); disabled.setPath("/disabled"); disabled.setDisabled(true); evidence();
    context = caller("__platform__", AuthorizationScopeType.PLATFORM, true);
    mvc.perform(get("/resources/service-routes").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk())
        .andExpect(jsonPath("$.routes.length()").value(261)); assertBoundedMemberships();
    assertFalse(statements.stream().filter(sql -> sql.startsWith("select")).anyMatch(sql -> sql.contains("permissionMetadata")));
  }

  @Test void routesRejectBlankIdentityBeforeAnySql() throws Exception {
    var page = node("route", null, 0, ResourceType.PAGE, ResourceScopeType.TENANT, true); page.setPath("/route"); evidence();
    context = new AuthorizationContext(); context.setUserId(" "); context.setScopeType(AuthorizationScopeType.TENANT); context.setResources(Set.of("route"));
    mvc.perform(get("/resources/service-routes").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isForbidden()); assertTrue(statements.isEmpty());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"MASKED", "HIDDEN"})
  void administratorRouteCacheCannotReuseOrStoreRequestSpecificProtectedLabels(String access) {
    var page = node("page", null, 0, ResourceType.PAGE, ResourceScopeType.PLATFORM, true); page.setPath("/protected-page"); page.setName("私有名称"); page.setLabel(null); evidence();
    var unrestricted = caller("__platform__", AuthorizationScopeType.PLATFORM, true); RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, unrestricted);
    var first = service.routes().routes().iterator().next(); assertEquals("私有名称", first.getLabel()); long beforeProtected = factory.getStatistics().getQueryExecutionCount();
    var protectedContext = caller("__platform__", AuthorizationScopeType.PLATFORM, true); protectedContext.setFieldPermissions(Map.of(Resource.class.getName() + "#name", access));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, protectedContext);
    var protectedNode = service.routes().routes().iterator().next(); assertEquals(access.equals("MASKED") ? "***" : "page", protectedNode.getLabel()); assertNotSame(first, protectedNode);
    long afterProtected = factory.getStatistics().getQueryExecutionCount(); assertTrue(afterProtected > beforeProtected);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, unrestricted); assertSame(first, service.routes().routes().iterator().next());
    assertEquals(afterProtected, factory.getStatistics().getQueryExecutionCount(), "Unrestricted navigation retains its original cache and is not polluted by the protected request"); assertBoundedMemberships();
  }

  @Test void browserResourceManagementRouteMatchesActualRuntimeProjectionIncludingClearedIdentifiers() throws Exception {
    var page = node("resource-management-test", null, 0, ResourceType.PAGE, ResourceScopeType.PLATFORM, true);
    page.setCode("common.view"); page.setName("资源管理契约测试"); page.setPath("/smoke/resource-management"); page.setComponent("common/system/Resource"); page.setRouteKind("item"); evidence();
    context = caller("__platform__", AuthorizationScopeType.PLATFORM, true);
    var response = mvc.perform(get("/resources/service-routes").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk())
        .andExpect(jsonPath("$.routes.length()").value(1)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper(); var route = mapper.readTree(response).path("routes").get(0);
    assertFalse(route.hasNonNull("id"), "Runtime navigation clears storage identifiers; JSON may omit null properties");
    assertFalse(route.hasNonNull("parentId")); assertEquals("item", route.path("routeKind").asText());
    var output = java.nio.file.Path.of("build/generated-test-resources/resource-management-navigation.json"); java.nio.file.Files.createDirectories(output.getParent()); java.nio.file.Files.writeString(output, route.toString());
    assertEquals(mapper.readTree(java.nio.file.Path.of("../../simplepoint-react/scripts/resource-management-contracts/navigation.json").toFile()), route);
    assertBoundedMemberships();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"MASKED", "HIDDEN"})
  void customNavigationUsesActualTenantSourcePolicyBeforeDisplayFallbackAndSerialization(String access) throws Exception {
    customCatalog(); var page = customNode("tenant-page", null, "tenant.page", null); page.setName("租户私有名称"); page.setIcon("PrivateTenantIcon"); page.setDescription("Private tenant description"); evidence();
    context = caller("tenant-a", AuthorizationScopeType.TENANT, false, Set.of("tenant.page"));
    context.setFieldPermissions(Map.of(TenantResource.class.getName() + "#name", access, TenantResource.class.getName() + "#icon", access,
        TenantResource.class.getName() + "#description", access));
    var request = mvc.perform(get("/resources/service-routes").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk())
        .andExpect(jsonPath("$.routes[0].label").value(access.equals("MASKED") ? "***" : "tenant.page"));
    if (access.equals("MASKED")) request.andExpect(jsonPath("$.routes[0].name").value("***")).andExpect(jsonPath("$.routes[0].icon").value("***"))
        .andExpect(jsonPath("$.routes[0].description").value("***"));
    else request.andExpect(jsonPath("$.routes[0].name").doesNotExist()).andExpect(jsonPath("$.routes[0].icon").doesNotExist())
        .andExpect(jsonPath("$.routes[0].description").doesNotExist());
    var response = request.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertFalse(response.contains("租户私有名称") || response.contains("PrivateTenantIcon") || response.contains("Private tenant description")); assertBoundedMemberships();
  }

  @Test void platformResourceFieldPolicyCannotAccidentallyGovernActualCustomTenantNavigation() throws Exception {
    customCatalog(); customNode("tenant-page", null, "tenant.page", null).setName("租户公开名称"); evidence();
    context = caller("tenant-a", AuthorizationScopeType.TENANT, false, Set.of("tenant.page")); context.setFieldPermissions(Map.of(Resource.class.getName() + "#name", "HIDDEN"));
    mvc.perform(get("/resources/service-routes").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk())
        .andExpect(jsonPath("$.routes[0].label").value("租户公开名称")).andExpect(jsonPath("$.routes[0].name").value("租户公开名称")); assertBoundedMemberships();
  }

  @Test void customRuntimeRoutesPreserveLargeAliasNavigationWithoutEntityOrUnheldCatalogReads() throws Exception {
    customCatalog(); var root = customNode("tenant-root", null, "tenant.root", null); root.setType(ResourceType.GROUP); root.setPath(null); root.setComponent(null); root.setGrantable(false);
    var held = new LinkedHashSet<String>();
    for (int i = 0; i < 521; i++) {String suffix = String.format("%03d", i), id = "tenant-alias-" + suffix;
      customNode("tenant-target-" + suffix, "tenant-root", "platform-" + suffix, null).setPlatformResourceId("tenant-target-" + suffix);
      customNode(id, "tenant-root", id, "tenant-target-" + suffix); held.add(id);}
    customNode("tenant-unheld", null, "tenant.unheld", null); held.add("platform-000"); evidence();
    context = caller("tenant-a", AuthorizationScopeType.TENANT, false, held);
    var response = mvc.perform(get("/resources/service-routes").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk())
        .andExpect(jsonPath("$.routes.length()").value(1)).andExpect(jsonPath("$.routes[0].children.length()").value(521))
        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(response.contains("tenant-alias-520")); assertFalse(response.contains("tenant-unheld") || response.contains("platform-000"));
    assertTrue(statements.stream().filter(sql -> sql.contains("simpoint_saas_tenant_resources")).allMatch(sql -> sql.contains(" in "))); assertBoundedMemberships();
  }

  @Test void tenantResourceBrowserNavigationMatchesActualScopedSourceProjection() throws Exception {
    customCatalog(); var resources = customNode("workspace-resources", null, "common.view", null);
    resources.setName("租户资源标准测试"); resources.setPath("/tenant/resources"); resources.setComponent("host/TenantHome");
    var settings = customNode("workspace-settings", null, "tenant.settings.view", null);
    settings.setName("租户设置标准测试"); settings.setPath("/tenant/settings"); settings.setComponent("host/TenantHome"); evidence();
    context = caller("tenant-a", AuthorizationScopeType.TENANT, false, Set.of("common.view", "tenant.settings.view"));
    var response = mvc.perform(get("/resources/service-routes").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk())
        .andExpect(jsonPath("$.routes.length()").value(2)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper(); var nodes = mapper.readTree(response).path("routes");
    for (var node : nodes) assertFalse(node.hasNonNull("id") || node.hasNonNull("parentId"));
    var output = java.nio.file.Path.of("build/generated-test-resources/tenant-resource-navigation.json"); java.nio.file.Files.createDirectories(output.getParent()); java.nio.file.Files.writeString(output, nodes.toString());
    assertEquals(mapper.readTree(java.nio.file.Path.of("../../simplepoint-react/scripts/tenant-resource-contracts/navigation.json").toFile()), nodes); assertBoundedMemberships();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"MASKED", "HIDDEN"})
  void protectedTenantNavigationDoesNotSortByPrivateNamesOrConfiguration(String access) throws Exception {
    customCatalog(); var first = customNode("a", null, "a", null); first.setName("Z private label"); first.setSort(1);
    var second = customNode("z", null, "z", null); second.setName("A private label"); second.setSort(0); evidence();
    context = caller("tenant-a", AuthorizationScopeType.TENANT, false, Set.of("a", "z"));
    context.setFieldPermissions(Map.of(TenantResource.class.getName() + "#name", access, TenantResource.class.getName() + "#sort", access));
    mvc.perform(get("/resources/service-routes").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk())
        .andExpect(jsonPath("$.routes[0].code").value("a")).andExpect(jsonPath("$.routes[1].code").value("z")); assertBoundedMemberships();
  }

  @Test void tenantLicensesAreFilteredBeforeCountAndPaginationWithoutRequiringExistingCallerGrants() throws Exception {
    node("root", null, 0, ResourceType.GROUP, ResourceScopeType.TENANT, false);
    for (int i = 0; i < 521; i++) {String id = "owned-" + String.format("%03d", i); node(id, "root", i * 2 + 1, ResourceType.ACTION, ResourceScopeType.TENANT, true); license("tenant-a", id);
      String foreign = "foreign-" + String.format("%03d", i); node(foreign, "root", i * 2, ResourceType.ACTION, ResourceScopeType.TENANT, true); license("tenant-b", foreign);}
    evidence();
    mvc.perform(children().param("parentId", "root").param("page", "0").param("size", "20"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(521))
        .andExpect(jsonPath("$.content[0].id").value("owned-000")).andExpect(jsonPath("$.content[19].id").value("owned-019"));
    mvc.perform(children().param("parentId", "root").param("page", "26").param("size", "20"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].id").value("owned-520"));
    assertTrue(statements.stream().filter(sql -> sql.startsWith("select")).allMatch(sql -> sql.chars().filter(c -> c == '?').count() <= 100));
    assertEquals(0, factory.getStatistics().getEntityLoadCount(), "Read only the bounded directory projection, not complete Resource metadata entities");
  }

  @Test void searchAndHasChildrenCannotRevealForeignOrDeletedPermissions() throws Exception {
    node("root", null, 0, ResourceType.GROUP, ResourceScopeType.TENANT, false);
    node("owned", "root", 0, ResourceType.PAGE, ResourceScopeType.TENANT, true); license("tenant-a", "owned");
    node("foreign-child", "owned", 0, ResourceType.ACTION, ResourceScopeType.TENANT, true); license("tenant-b", "foreign-child");
    node("deleted", "root", 0, ResourceType.ACTION, ResourceScopeType.TENANT, true).setDeletedAt(Instant.now()); license("tenant-a", "deleted"); evidence();
    mvc.perform(children().param("parentId", "root")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].hasChildren").value(false));
    mvc.perform(children().param("keyword", "foreign")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(children().param("parentId", "owned")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test void requiredAncestorsAreVisibleButAnUnlicensedPageAncestorIsNotDirectlyGrantable() throws Exception {
    node("root", null, 0, ResourceType.GROUP, ResourceScopeType.TENANT, false);
    node("page", "root", 0, ResourceType.PAGE, ResourceScopeType.TENANT, true);
    node("action", "page", 0, ResourceType.ACTION, ResourceScopeType.TENANT, true); license("tenant-a", "action");
    var relation = new ResourceAncestor(); relation.setAncestorId("root"); relation.setChildId("action"); em.persist(relation); evidence();
    mvc.perform(children()).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value("root")).andExpect(jsonPath("$.content[0].hasChildren").value(true));
    mvc.perform(children().param("parentId", "root")).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value("page"))
        .andExpect(jsonPath("$.content[0].grantable").value(false)).andExpect(jsonPath("$.content[0].hasChildren").value(true));
  }

  @Test void scopeAndRevokedLicenseLinksAreEnforcedEvenWhenNamesMatchTheSearch() throws Exception {
    node("tenant", null, 0, ResourceType.PAGE, ResourceScopeType.TENANT, true).setPublicAccess(true);
    node("platform", null, 0, ResourceType.PAGE, ResourceScopeType.PLATFORM, true).setPublicAccess(true);
    node("personal", null, 0, ResourceType.PAGE, ResourceScopeType.PERSONAL, true).setPublicAccess(true);
    node("revoked", null, 0, ResourceType.PAGE, ResourceScopeType.TENANT, true); license("tenant-a", "revoked");
    em.createQuery("update TenantPackageRelevance t set t.deletedAt=:now where t.tenantId=:tenant").setParameter("now", Instant.now()).setParameter("tenant", "tenant-a").executeUpdate(); evidence();
    mvc.perform(children().param("keyword", "Node")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value("tenant"));
    context = caller("personal-workspace", AuthorizationScopeType.PERSONAL, false);
    mvc.perform(children()).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value("personal"));
  }

  @Test void platformAuthoringRetainsCrossScopeResourcesAndReadableDisabledRows() throws Exception {
    for (var scope : ResourceScopeType.values()) node(scope.name(), null, scope.ordinal(), ResourceType.PAGE, scope, true);
    node("disabled", null, 10, ResourceType.PAGE, ResourceScopeType.TENANT, true).setDisabled(true); evidence();
    context = caller("__platform__", AuthorizationScopeType.PLATFORM, true);
    mvc.perform(children()).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5));
  }

  @Test void invalidCallerAndPageBudgetsFailBeforeAnySql() throws Exception {
    context = new AuthorizationContext(); context.setUserId(" "); context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a")); evidence();
    mvc.perform(children()).andExpect(status().isForbidden()); assertTrue(statements.isEmpty());
    context = caller("tenant-a", AuthorizationScopeType.TENANT, false);
    mvc.perform(children().param("size", "101")).andExpect(status().isBadRequest()); assertTrue(statements.isEmpty());
  }

  @Test void protectedFieldSearchCannotLeakRawNamesOrCodesThroughMembershipAndSorting() throws Exception {
    node("visible", null, 0, ResourceType.PAGE, ResourceScopeType.TENANT, true).setPublicAccess(true); evidence();
    context.setFieldPermissions(Map.of(Resource.class.getName() + "#name", "MASKED", Resource.class.getName() + "#code", "HIDDEN"));
    mvc.perform(children()).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].name").value("***"))
        .andExpect(jsonPath("$.content[0].label").value("***")).andExpect(jsonPath("$.content[0].code").doesNotExist());
    mvc.perform(children().param("keyword", "Node visible")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    statements.clear(); mvc.perform(children().param("sort", "name,desc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    assertTrue(statements.isEmpty(), "An unreadable explicit sort is rejected before count or page SQL");
  }

  @Test void literalKeywordsAndExplicitDescendingSortAreAppliedInsideTheVisibleSqlPage() throws Exception {
    node("plain", null, 1, ResourceType.PAGE, ResourceScopeType.TENANT, true).setPublicAccess(true);
    var percent = node("percent", null, 2, ResourceType.PAGE, ResourceScopeType.TENANT, true); percent.setPublicAccess(true); percent.setName("Literal %_ input"); evidence();
    mvc.perform(children().param("keyword", "%_")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value("percent"));
    mvc.perform(children().param("sort", "sort,desc").param("size", "1")).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value("percent"));
  }

  @Test void licenseAndAncestorIndexesExistWithProductionSnakeCaseColumnNaming() throws Exception {
    cleanup(); snakeCase = true; statements.clear(); setup();
    var indexes = em.createNativeQuery("select index_name from information_schema.indexes where index_name like 'IDX_%'").getResultList();
    assertTrue(indexes.containsAll(List.of("IDX_TENANT_PACKAGE_LICENSE", "IDX_APPLICATION_PACKAGE_LICENSE", "IDX_RESOURCE_APPLICATION_LICENSE", "IDX_RESOURCE_ANCESTOR_CHILDREN")));
    node("licensed", null, 0, ResourceType.PAGE, ResourceScopeType.TENANT, true); license("tenant-a", "licensed"); evidence();
    mvc.perform(children()).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test void subtreeCodeCollectionIsCompleteBeyondFiveHundredWithoutWholeIdBindsOrEntityLoads() throws Exception {
    node("root", null, 0, ResourceType.GROUP, ResourceScopeType.TENANT, false);
    for (int i = 0; i < 521; i++) {
      String id = "owned-" + String.format("%03d", i); node(id, "root", i, ResourceType.ACTION, ResourceScopeType.TENANT, true); license("tenant-a", id);
      String other = "foreign-" + String.format("%03d", i); node(other, "root", i, ResourceType.ACTION, ResourceScopeType.TENANT, true); license("tenant-b", other);
    }
    evidence();
    mvc.perform(get("/resources/subtree-codes").param("rootId", "root").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(521))
        .andExpect(jsonPath("$[0]").value("owned-000")).andExpect(jsonPath("$[520]").value("owned-520"));
    assertEquals(0, factory.getStatistics().getEntityLoadCount());
    assertTrue(statements.stream().filter(sql -> sql.startsWith("select")).allMatch(sql -> sql.chars().filter(c -> c == '?').count() < 100));
  }

  @Test void platformSubtreesRetainCrossScopeAssignmentAndProtectedCodesCannotEscapeAsStrings() throws Exception {
    node("root", null, 0, ResourceType.GROUP, ResourceScopeType.PLATFORM, false);
    for (var scope : ResourceScopeType.values()) node(scope.name(), "root", scope.ordinal(), ResourceType.ACTION, scope, true); evidence();
    context = caller("__platform__", AuthorizationScopeType.PLATFORM, true);
    mvc.perform(get("/resources/subtree-codes").param("rootId", "root").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
    context.setFieldPermissions(Map.of(Resource.class.getName() + "#code", "HIDDEN")); statements.clear();
    mvc.perform(get("/resources/subtree-codes").param("rootId", "root").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isForbidden()); assertTrue(statements.isEmpty());
  }

  @Test void applicationResourceBrowserContractsUseActualDirectoryPagesAndSubtreeCodes() throws Exception {
    for (int i = 0; i < 521; i++) {
      String padded = String.format("%03d", i);
      var resource = node("resource-" + padded, null, i, i == 0 ? ResourceType.GROUP : ResourceType.PAGE, ResourceScopeType.TENANT, i != 0);
      resource.setCode("resource.code." + padded); resource.setName("Resource" + padded);
    }
    for (int i = 0; i < 41; i++) {
      var child = node("resource-child-" + i, "resource-000", i, ResourceType.ACTION, ResourceScopeType.TENANT, true);
      child.setCode("resource.child." + String.format("%03d", i)); child.setName("Child" + String.format("%03d", i));
    }
    evidence(); context = caller("__platform__", AuthorizationScopeType.PLATFORM, true);
    var outputs = new LinkedHashMap<String, String>();
    for (int page : List.of(0, 26)) outputs.put("root-page-" + page, mvc.perform(children().param("page", String.valueOf(page)).param("size", "20"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    outputs.put("subtree-codes", mvc.perform(get("/resources/subtree-codes").param("rootId", "resource-000")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    var assignedCodes = java.util.stream.IntStream.rangeClosed(1, 501).mapToObj(index -> "resource.code." + String.format("%03d", index)).toList();
    for (int page : List.of(0, 25)) outputs.put("assigned-page-" + page, mvc.perform(assigned(assignedCodes).param("page", String.valueOf(page)).param("size", "20"))
        .andExpect(nativeOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    for (var output : outputs.entrySet()) {
      var generated = java.nio.file.Path.of("build/generated-test-resources/application-resource-" + output.getKey() + ".json");
      java.nio.file.Files.createDirectories(generated.getParent()); java.nio.file.Files.writeString(generated, output.getValue());
    }
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    for (var output : outputs.entrySet()) assertEquals(mapper.readTree(java.nio.file.Path.of("../../simplepoint-react/scripts/application-resource-contracts/" + output.getKey() + ".json").toFile()),
        mapper.readTree(output.getValue()), "Production browser resources must match native projected fields, paging and cross-scope subtree semantics");
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder assigned(Collection<String> codes) throws Exception {
    return post("/resources/assigned-tree").contentType("application/json").content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(codes))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private void assertBoundedMemberships() {
    for (String sql : statements) {
      var membership = java.util.regex.Pattern.compile("(?i)\\bin\\s*\\(([^()]*)\\)").matcher(sql);
      while (membership.find()) assertTrue(membership.group(1).chars().filter(character -> character == '?').count() <= 100,
          "Each expanded membership must remain bounded: " + sql);
    }
    assertEquals(0, factory.getStatistics().getEntityLoadCount(), "Directory projection never loads Resource entities");
  }

  private org.springframework.test.web.servlet.ResultMatcher nativeOk() {
    return result -> assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test void assignedPagesApplyActualTenantLicensesBeforePagingAndKeepOnlySelectedChildFlags() throws Exception {
    node("root", null, 0, ResourceType.GROUP, ResourceScopeType.TENANT, false);
    var codes = new ArrayList<String>();
    for (int i = 0; i < 521; i++) {
      String own = "owned-" + String.format("%03d", i); node(own, "root", i * 2 + 1, ResourceType.ACTION, ResourceScopeType.TENANT, true); license("tenant-a", own); codes.add(own);
      String foreign = "foreign-" + String.format("%03d", i); node(foreign, "root", i * 2, ResourceType.ACTION, ResourceScopeType.TENANT, true); license("tenant-b", foreign); codes.add(foreign);
    }
    node("not-selected", "owned-000", 0, ResourceType.ACTION, ResourceScopeType.TENANT, true).setPublicAccess(true); evidence();
    mvc.perform(assigned(codes)).andExpect(nativeOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value("root")).andExpect(jsonPath("$.content[0].checked").value(false))
        .andExpect(jsonPath("$.content[0].grantable").value(false)).andExpect(jsonPath("$.content[0].hasChildren").value(true));
    mvc.perform(assigned(codes).param("parentId", "root").param("size", "20")).andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(521)).andExpect(jsonPath("$.content[0].id").value("owned-000"))
        .andExpect(jsonPath("$.content[0].checked").value(true)).andExpect(jsonPath("$.content[0].hasChildren").value(false));
    mvc.perform(assigned(codes).param("parentId", "root").param("size", "20").param("page", "26")).andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(521)).andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value("owned-520"));
    assertBoundedMemberships();
  }

  @Test void platformAssignedPagesKeepAllScopesAndNativeDescendingOrderWithBoundedBinds() throws Exception {
    var codes = new ArrayList<String>(); context = caller("__platform__", AuthorizationScopeType.PLATFORM, true);
    for (int i = 0; i < 521; i++) {String id = "platform-" + String.format("%03d", i);
      node(id, null, i, ResourceType.PAGE, ResourceScopeType.values()[i % 4], true); codes.add(id);}
    node("disabled", null, 522, ResourceType.PAGE, ResourceScopeType.TENANT, true).setDisabled(true); codes.add("disabled"); evidence();
    mvc.perform(assigned(codes).param("size", "20").param("sort", "sort,desc")).andExpect(nativeOk())
        .andExpect(jsonPath("$.totalElements").value(522)).andExpect(jsonPath("$.content[0].id").value("disabled"))
        .andExpect(jsonPath("$.content[0].disabled").value(true)).andExpect(jsonPath("$.content[0].checked").value(true));
    mvc.perform(assigned(codes).param("size", "20").param("page", "26").param("sort", "sort,desc")).andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2)).andExpect(jsonPath("$.content[1].id").value("platform-000"));
    assertBoundedMemberships();
  }

  @Test void assignedUnreadableSortAndOversizedPageAreRejectedBeforeAnySql() throws Exception {
    context = caller("__platform__", AuthorizationScopeType.PLATFORM, true); context.setFieldPermissions(Map.of(Resource.class.getName() + "#name", "MASKED"));
    mvc.perform(assigned(List.of("any")).param("sort", "name,desc")).andExpect(status().isBadRequest()); assertTrue(statements.isEmpty());
    mvc.perform(assigned(List.of("any")).param("size", "101")).andExpect(status().isBadRequest()); assertTrue(statements.isEmpty());
    context = new AuthorizationContext(); context.setUserId(" ");
    mvc.perform(assigned(List.of("any"))).andExpect(status().isForbidden()); assertTrue(statements.isEmpty());
  }

  @Test void assignedCompositeCursorsMatchNativeNullOrderingAndMixedDirections() throws Exception {
    var codes = new ArrayList<String>(); context = caller("__platform__", AuthorizationScopeType.PLATFORM, true);
    for (int i = 0; i < 521; i++) {String id = "cursor-" + String.format("%03d", i);
      var row = node(id, null, i % 5, List.of(ResourceType.PAGE, ResourceType.ACTION, ResourceType.FEATURE).get(i % 3), ResourceScopeType.TENANT, true);
      row.setAlias(i % 3 == 0 ? null : "Alias " + i % 7); if (i % 4 == 0) row.setSort(null); codes.add(id);}
    evidence(); var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    assertTrue(em.createQuery("select count(r) from Resource r where r.sort is null", Long.class).getSingleResult() > 0);
    assertTrue(em.createQuery("select count(r) from Resource r where r.alias is null", Long.class).getSingleResult() > 0);
    for (String placement : List.of("LOW", "HIGH")) {
      em.createNativeMutationQuery("SET DEFAULT_NULL_ORDERING " + placement).executeUpdate();
      for (boolean descending : List.of(false, true)) {
        var expected = em.createQuery("select r.id from Resource r order by r.sort " + (descending ? "desc" : "asc") + ", r.type asc, r.alias desc, r.name asc, r.id asc", String.class).getResultList();
        var actual = new ArrayList<String>(); statements.clear(); factory.getStatistics().clear();
        for (int page = 0; page < 6; page++) {
          var response = mvc.perform(assigned(codes).param("size", "100").param("page", String.valueOf(page))
              .param("sort", "sort," + (descending ? "desc" : "asc"), "type,asc", "alias,desc")).andExpect(nativeOk())
              .andExpect(jsonPath("$.totalElements").value(521)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
          for (var row : mapper.readTree(response).get("content")) actual.add(row.get("id").asText());
        }
        assertEquals(expected, actual, "Every page must match native " + placement + " null order and mixed directions");
        assertBoundedMemberships();
      }
    }
  }

  @Test void assignedProtectedLabelsAndAncestorMembershipKeepTheirActualSourceBoundaries() throws Exception {
    node("root", null, 0, ResourceType.GROUP, ResourceScopeType.TENANT, false);
    node("page", "root", 0, ResourceType.PAGE, ResourceScopeType.TENANT, true);
    node("action", "page", 0, ResourceType.ACTION, ResourceScopeType.TENANT, true); license("tenant-a", "action");
    var relation = new ResourceAncestor(); relation.setAncestorId("root"); relation.setChildId("action"); em.persist(relation);
    node("deleted", null, 1, ResourceType.PAGE, ResourceScopeType.TENANT, true).setDeletedAt(Instant.now()); license("tenant-a", "deleted"); evidence();
    context.setFieldPermissions(Map.of(Resource.class.getName() + "#name", "MASKED", Resource.class.getName() + "#code", "HIDDEN"));
    mvc.perform(assigned(List.of("root", "page", "action", "deleted")).param("parentId", "root")).andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value("page"))
        .andExpect(jsonPath("$.content[0].name").value("***")).andExpect(jsonPath("$.content[0].label").value("***"))
        .andExpect(jsonPath("$.content[0].code").doesNotExist()).andExpect(jsonPath("$.content[0].checked").value(false))
        .andExpect(jsonPath("$.content[0].grantable").value(false)).andExpect(jsonPath("$.content[0].hasChildren").value(true));
    assertBoundedMemberships();
  }

  @Test void assignedAncestorClosureBeyondOneHundredRetainsEveryLevelAndUsesTheSmallCurrentLevelPath() throws Exception {
    for (int i = 0; i < 201; i++) node("ancestor-" + String.format("%03d", i), i == 0 ? null : "ancestor-" + String.format("%03d", i - 1),
        i, ResourceType.GROUP, ResourceScopeType.TENANT, false);
    node("leaf", "ancestor-200", 0, ResourceType.ACTION, ResourceScopeType.TENANT, true); license("tenant-a", "leaf");
    for (int i = 0; i < 200; i++) {var relation = new ResourceAncestor(); relation.setAncestorId("ancestor-" + String.format("%03d", i)); relation.setChildId("leaf"); em.persist(relation);}
    evidence();
    mvc.perform(assigned(List.of("leaf"))).andExpect(nativeOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value("ancestor-000")).andExpect(jsonPath("$.content[0].hasChildren").value(true));
    mvc.perform(assigned(List.of("leaf")).param("parentId", "ancestor-199")).andExpect(nativeOk())
        .andExpect(jsonPath("$.content[0].id").value("ancestor-200")).andExpect(jsonPath("$.content[0].hasChildren").value(true));
    mvc.perform(assigned(List.of("leaf")).param("parentId", "ancestor-200")).andExpect(nativeOk())
        .andExpect(jsonPath("$.content[0].id").value("leaf")).andExpect(jsonPath("$.content[0].checked").value(true));
    assertBoundedMemberships();
    assertFalse(statements.stream().anyMatch(sql -> sql.matches("(?is)select\\s+\\w+\\.sort\\s+from\\s+.*")),
        "A small current level must not probe/scan the entire native ordered level just because the full tree is large");
  }

  @Test void assignedStringCursorsFollowActualCaseInsensitiveDatabaseCollation() throws Exception {
    cleanup(); caseInsensitiveCollation = true; statements.clear(); setup();
    assertEquals(Boolean.TRUE, em.createNativeQuery("select 'alpha' = 'ALPHA'").getSingleResult(), "Verify the real native collation, not a Java comparator");
    context = caller("__platform__", AuthorizationScopeType.PLATFORM, true); var codes = new ArrayList<String>();
    var aliases = List.of("alpha", "ALPHA", "Zulu", "zulu", "éclair", "eclair");
    for (int i = 0; i < 521; i++) {String id = "collation-" + String.format("%03d", i);
      node(id, null, 0, ResourceType.PAGE, ResourceScopeType.TENANT, true).setAlias(aliases.get(i % aliases.size())); codes.add(id);}
    evidence(); var expected = em.createQuery("select r.id from Resource r order by r.alias asc, r.name asc, r.id asc", String.class).getResultList();
    var actual = new ArrayList<String>(); var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    for (int page = 0; page < 6; page++) {
      var response = mvc.perform(assigned(codes).param("size", "100").param("page", String.valueOf(page)).param("sort", "alias,asc"))
          .andExpect(nativeOk()).andExpect(jsonPath("$.totalElements").value(521)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
      for (var row : mapper.readTree(response).get("content")) actual.add(row.get("id").asText());
    }
    assertEquals(expected, actual); assertBoundedMemberships();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder management() {
    return get("/resources").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @Test void managementRootAndDescendantLicensesRemainOutsideAdvancedOrAndHeaderQueries() throws Exception {
    node("root", null, 0, ResourceType.GROUP, ResourceScopeType.TENANT, false);
    node("owned", "root", 0, ResourceType.PAGE, ResourceScopeType.TENANT, true); license("tenant-a", "owned");
    node("foreign-child", "root", 1, ResourceType.PAGE, ResourceScopeType.TENANT, true); license("tenant-b", "foreign-child");
    node("foreign-root", null, 1, ResourceType.PAGE, ResourceScopeType.TENANT, true); license("tenant-b", "foreign-root");
    node("platform", null, 2, ResourceType.PAGE, ResourceScopeType.PLATFORM, true).setPublicAccess(true); evidence();
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertEquals(3, service.limit(Map.of("parentId", "is:null:"), org.springframework.data.domain.PageRequest.of(0, 20)).getTotalElements(),
        "The legacy inherited query used by the old tree reads global roots without a license boundary");
    em.clear(); factory.getStatistics().clear(); statements.clear();
    mvc.perform(management().param("name", "{\"version\":1,\"where\":{\"field\":\"name\",\"op\":\"like\",\"value\":\"Node\"}}")
        .param("$query", "{\"version\":1,\"where\":{\"op\":\"or\",\"children\":[{\"field\":\"parentId\",\"op\":\"is:not:null\"},{\"field\":\"name\",\"op\":\"like\",\"value\":\"Node\"}]}}"))
        .andExpect(nativeOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value("root")).andExpect(jsonPath("$.content[0].children.length()").value(1))
        .andExpect(jsonPath("$.content[0].children[0].id").value("owned"));
  }

  @Test void managementCompleteSubtreesKeepRealEditableFieldsWithoutEntityOrPermissionMetadataLoads() throws Exception {
    context = caller("__platform__", AuthorizationScopeType.PLATFORM, true);
    var root = node("root", null, 0, ResourceType.GROUP, ResourceScopeType.PLATFORM, false); root.setDescription("Root actual description");
    for (int i = 0; i < 521; i++) {var child = node("child-" + String.format("%03d", i), "root", i, ResourceType.ACTION, ResourceScopeType.values()[i % 4], true);
      child.setDescription("Actual description " + i); child.setMethod("POST"); child.setPattern("/real/" + i); child.setRouteKind("hidden");
      child.setDanger(true); child.setPluginId("real-plugin"); child.setPermissionMetadata("private permission metadata " + i);}
    evidence();
    mvc.perform(management()).andExpect(nativeOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].description").value("Root actual description"))
        .andExpect(jsonPath("$.content[0].children.length()").value(521))
        .andExpect(jsonPath("$.content[0].children[520].id").value("child-520"))
        .andExpect(jsonPath("$.content[0].children[520].description").value("Actual description 520"))
        .andExpect(jsonPath("$.content[0].children[520].method").value("POST"))
        .andExpect(jsonPath("$.content[0].children[520].pattern").value("/real/520"))
        .andExpect(jsonPath("$.content[0].children[520].routeKind").value("hidden"))
        .andExpect(jsonPath("$.content[0].children[520].danger").value(true))
        .andExpect(jsonPath("$.content[0].children[520].pluginId").value("real-plugin"))
        .andExpect(jsonPath("$.content[0].children[520].permissionMetadata").doesNotExist());
    assertBoundedMemberships();
    assertFalse(statements.stream().filter(sql -> sql.startsWith("select")).anyMatch(sql -> sql.contains("permissionMetadata")), "Never read large internal metadata for a management row");
  }

  @Test void managementTwoHundredRowContractRetainsNativeRootOrderAndBoundedProjectionBatches() throws Exception {
    context = caller("__platform__", AuthorizationScopeType.PLATFORM, true);
    for (int i = 0; i < 521; i++) node("management-" + String.format("%03d", i), null, i, ResourceType.PAGE, ResourceScopeType.TENANT, true); evidence();
    mvc.perform(management().param("size", "200").param("sort", "sort,desc")).andExpect(nativeOk())
        .andExpect(jsonPath("$.totalElements").value(521)).andExpect(jsonPath("$.content.length()").value(200))
        .andExpect(jsonPath("$.content[0].id").value("management-520")).andExpect(jsonPath("$.content[199].id").value("management-321"));
    mvc.perform(management().param("size", "200").param("page", "2").param("sort", "sort,desc")).andExpect(nativeOk())
        .andExpect(jsonPath("$.content.length()").value(121)).andExpect(jsonPath("$.content[120].id").value("management-000"));
    assertBoundedMemberships();
  }

  @Test void managementHiddenParentDoesNotRejectServerOwnedRootConstraintAndBadQueriesIssueNoSql() throws Exception {
    context = caller("__platform__", AuthorizationScopeType.PLATFORM, true);
    node("root", null, 0, ResourceType.PAGE, ResourceScopeType.PLATFORM, true); evidence();
    context.setFieldPermissions(Map.of(Resource.class.getName() + "#parentId", "HIDDEN", Resource.class.getName() + "#name", "MASKED"));
    mvc.perform(management()).andExpect(nativeOk()).andExpect(jsonPath("$.content[0].name").value("***"))
        .andExpect(jsonPath("$.content[0].label").value("***")).andExpect(jsonPath("$.content[0].parentId").doesNotExist());
    statements.clear(); mvc.perform(management().param("sort", "name,desc")).andExpect(status().isBadRequest()); assertTrue(statements.isEmpty());
    mvc.perform(management().param("size", "201")).andExpect(status().isBadRequest()); assertTrue(statements.isEmpty());
    context = new AuthorizationContext(); context.setUserId(" ");
    mvc.perform(management()).andExpect(status().isForbidden()); assertTrue(statements.isEmpty());
  }

  @Test void managementBrowserContractsUseActualSchemaMetadataAndRootPages() throws Exception {
    var dictionary = new org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary(); dictionary.setCode(org.simplepoint.security.SecurityDictionaryCodes.RESOURCE_SCOPE_TYPE);
    dictionary.setName("资源作用域"); dictionary.setEnabled(true); em.persist(dictionary);
    // The exact enum values, label keys and order registered by ResourceScopeTypeDictionaryInitializer.
    for (var scope : ResourceScopeType.values()) {var item = new DictionaryItem(); item.setDictionaryCode(dictionary.getCode());
      item.setName(scope.name()); item.setValue(scope.name()); item.setI18nKey("resources.scope." + scope.name());
      item.setSort((scope.ordinal() + 1) * 10); item.setEnabled(true); em.persist(item);}
    for (int i = 0; i < 521; i++) {String suffix = String.format("%03d", i);
      var row = node("management-" + suffix, null, i, i == 0 ? ResourceType.GROUP : ResourceType.PAGE, ResourceScopeType.PLATFORM, i != 0);
      row.setName("Management" + suffix); row.setDescription("Actual description " + suffix); row.setComponent("common/system/Resource");}
    for (int i = 0; i < 41; i++) {var row = node("management-child-" + String.format("%03d", i), "management-000", i, ResourceType.ACTION, ResourceScopeType.PLATFORM, true);
      row.setDescription("Child actual description " + i); row.setMethod("POST"); row.setPattern("/actual/" + i);}
    evidence(); context = caller("__platform__", AuthorizationScopeType.PLATFORM, true); var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var outputs = new LinkedHashMap<String, String>();
    outputs.put("scope-options", mvc.perform(get("/platform/dictionaries/options").param("dictionaryCode", dictionary.getCode())
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk())
        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    outputs.put("schema", mvc.perform(get("/resources/schema").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(nativeOk())
        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    for (int page : List.of(0, 26)) outputs.put("page-" + page, mvc.perform(management().param("page", String.valueOf(page)).param("size", "20").param("sort", "sort,asc"))
        .andExpect(nativeOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    outputs.put("filtered", mvc.perform(management().param("name", "{\"version\":1,\"where\":{\"field\":\"name\",\"op\":\"like\",\"value\":\"Management520\"}}"))
        .andExpect(nativeOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    for (var output : outputs.entrySet()) {
      var parsed = mapper.readTree(output.getValue()); if (!output.getKey().equals("schema")) stripNondeterministicAudit(parsed);
      var generated = java.nio.file.Path.of("build/generated-test-resources/resource-management-" + output.getKey() + ".json");
      java.nio.file.Files.createDirectories(generated.getParent()); java.nio.file.Files.writeString(generated, parsed.toString());
    }
    for (var output : outputs.entrySet()) {var parsed = mapper.readTree(output.getValue()); if (!output.getKey().equals("schema")) stripNondeterministicAudit(parsed);
      assertEquals(mapper.readTree(java.nio.file.Path.of("../../simplepoint-react/scripts/resource-management-contracts/" + output.getKey() + ".json").toFile()), parsed);}
  }

  private void stripNondeterministicAudit(com.fasterxml.jackson.databind.JsonNode node) {
    if (node.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove(List.of("createdAt", "updatedAt", "createdBy", "updatedBy", "createOrgDeptId"));
    node.forEach(this::stripNondeterministicAudit);
  }

  @Test void limitTreeExposesTheDirectParentScopeBoundaryForEditing() throws Exception {
    context = caller("platform", AuthorizationScopeType.PLATFORM, true);
    var parent = node("parent-id", null, 0, ResourceType.MODULE, ResourceScopeType.TENANT, false);
    parent.setScopeTypes(Set.of(ResourceScopeType.TENANT, ResourceScopeType.PERSONAL));
    node("child-id", parent.getId(), 0, ResourceType.ACTION, ResourceScopeType.TENANT, true);
    evidence();
    mvc.perform(management()).andExpect(nativeOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].children[0].id").value("child-id"))
        .andExpect(jsonPath("$.content[0].children[0].parentScopeTypes",
            org.hamcrest.Matchers.containsInAnyOrder("TENANT", "PERSONAL")))
        .andExpect(jsonPath("$.content[0].children[0].grantable").value(true));
    assertEquals(0, factory.getStatistics().getEntityLoadCount());
  }

  @Test void managementInaccessibleIntermediateNodesCannotPromoteDescendantsIntoUnpagedRoots() throws Exception {
    node("root", null, 0, ResourceType.GROUP, ResourceScopeType.TENANT, false);
    node("owned", "root", 0, ResourceType.PAGE, ResourceScopeType.TENANT, true).setPublicAccess(true);
    node("denied-feature", "root", 1, ResourceType.FEATURE, ResourceScopeType.TENANT, true);
    node("nested", "denied-feature", 0, ResourceType.ACTION, ResourceScopeType.TENANT, true).setPublicAccess(true);
    var relation = new ResourceAncestor(); relation.setAncestorId("root"); relation.setChildId("nested"); em.persist(relation); evidence();
    mvc.perform(management()).andExpect(nativeOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].id").value("root"))
        .andExpect(jsonPath("$.content[0].children.length()").value(1)).andExpect(jsonPath("$.content[0].children[0].id").value("owned"));
  }
}
