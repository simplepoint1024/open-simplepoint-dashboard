package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import java.util.regex.Pattern;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.simplepoint.core.*;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.data.jpa.base.repository.BaseRepositoryImpl;
import org.simplepoint.plugin.rbac.core.api.service.*;
import org.simplepoint.plugin.rbac.core.rest.controller.CurrentUserAccessController;
import org.simplepoint.plugin.rbac.core.service.impl.CurrentUserAccessServiceImpl;
import org.simplepoint.plugin.rbac.resource.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.resource.base.repository.*;
import org.simplepoint.plugin.rbac.resource.service.impl.ResourceServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.entity.*;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.rbac.tenant.service.impl.TenantResourceCatalogImpl;
import org.simplepoint.security.entity.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Profile HTTP with genuine scope/license SQL and serialization; no prefiltered resource/catalog provider. */
class CurrentUserAccessHttpTest {
  SessionFactory factory; Session em; CurrentUserAccessServiceImpl service; MockMvc mvc;
  org.springframework.context.support.GenericApplicationContext schemaContext;
  TenantService tenants; UsersService users; AuthorizationContext context;
  org.simplepoint.security.service.ResourceService resourceBoundary;
  boolean organizationOnly;
  final List<String> statements = new ArrayList<>();

  @BeforeEach void setup(TestInfo info) throws Exception {
    organizationOnly = info.getTags().contains("self-organization");
    var metadata = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:profile-access-http-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop").applySetting("hibernate.generate_statistics", true)
        .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
        .applySetting("hibernate.session_factory.statement_inspector", (org.hibernate.resource.jdbc.spi.StatementInspector) sql -> {statements.add(sql); return sql;}).build())
        .addAnnotatedClass(Organization.class).addAnnotatedClass(TenantUserRelevance.class);
    if (!organizationOnly) metadata.addAnnotatedClass(Resource.class).addAnnotatedClass(ResourceAncestor.class).addAnnotatedClass(Tenant.class).addAnnotatedClass(TenantResource.class)
        .addAnnotatedClass(TenantPackageRelevance.class).addAnnotatedClass(PackageApplicationRelevance.class).addAnnotatedClass(ApplicationResourceRelevance.class);
    factory = metadata.buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction();
    tenants = mock(TenantService.class); users = mock(UsersService.class);
    if (organizationOnly) {
      // This endpoint reads genuine membership/organization SQL, not a resource
      // catalog. The unused boundary is verified below, not a filtered provider.
      resourceBoundary = mock(org.simplepoint.security.service.ResourceService.class);
      service = new CurrentUserAccessServiceImpl(em, tenants, users, resourceBoundary);
    } else {
    var repositories = new JpaRepositoryFactory(em); repositories.setRepositoryBaseClass(BaseRepositoryImpl.class);
    var repository = repositories.getRepository(JpaResourceRepository.class);
    var licenses = new JpaRepositoryFactory(em).getRepository(org.simplepoint.plugin.rbac.tenant.repository.JpaTenantPackageRelevanceRepository.class);
    var config = new org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration(new com.fasterxml.jackson.databind.ObjectMapper());
    var generator = config.schemaGenerator(Set.of(config.openApiModule(), config.jacksonModule(), config.javaxValidationModule()));
    schemaContext = new org.springframework.context.support.GenericApplicationContext();
    schemaContext.registerBean(org.simplepoint.api.security.service.JsonSchemaDetailsService.class, () -> new org.simplepoint.data.json.schema.service.DefaultJsonSchemaDetailsService(em));
    schemaContext.registerBean(org.simplepoint.api.security.generator.JsonSchemaGenerator.class, () -> config.defaultJsonSchemaGenerator(generator)); schemaContext.refresh();
    var details = new org.simplepoint.core.security.SpringBeanDetailsProviderService(); details.setApplicationContext(schemaContext);
    var resources = new ResourceServiceImpl(repository, details, repositories.getRepository(JpaResourceAncestorRepository.class), mock(MicroAppService.class),
        provider(licenses), provider(), provider(), provider(), provider(), provider());
    var entityManager = ResourceServiceImpl.class.getDeclaredField("permissionEntityManager"); entityManager.setAccessible(true); entityManager.set(resources, em);
    resourceBoundary = resources; service = new CurrentUserAccessServiceImpl(em, tenants, users, resources);
    service.setTenantResourceCatalog(new TenantResourceCatalogImpl(em, mock(org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository.class), licenses, repository));
    }
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("reader", "unused", List.of()));
    mvc = MockMvcBuilders.standaloneSetup(new CurrentUserAccessController(service))
        .addFilters(new org.springframework.security.web.access.ExceptionTranslationFilter(new org.springframework.security.web.authentication.HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED)))
        .setControllerAdvice(new org.simplepoint.core.handler.GlobalExceptionHandler())
        .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(tools.jackson.databind.json.JsonMapper.builder().addModule(new org.simplepoint.core.jackson.FieldScopeJackson3Module()).build()))
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
    context = caller(Set.of());
  }
  @SuppressWarnings("unchecked") private <T> ObjectProvider<T> provider() {return mock(ObjectProvider.class);}
  private <T> ObjectProvider<T> provider(T value) {var result = this.<T>provider(); when(result.getIfAvailable()).thenReturn(value); return result;}
  AuthorizationContext caller(Set<String> codes) {var result = new AuthorizationContext(); result.setUserId("reader"); result.setScopeType(AuthorizationScopeType.TENANT);
    result.setResources(codes); result.setAttributes(Map.of("X-Tenant-Id", "tenant-a")); return result;}
  void custom() {var tenant = new Tenant(); tenant.setId("tenant-a"); tenant.setName("Workspace"); tenant.setOwnerId("owner"); tenant.setAuthorizationVersion(0L);
    tenant.setTenantType(TenantType.ORGANIZATION); tenant.setResourceMode(TenantResourceMode.CUSTOM); em.persist(tenant);}
  TenantResource customNode(String id, String parent, ResourceType type) {var row = new TenantResource(); row.setTenantId("tenant-a"); row.setId(id); row.setCode(id);
    row.setName("Actual " + id); row.setParentId(parent); row.setType(type); row.setEnabled(true); row.setGrantable(type != ResourceType.GROUP);
    row.setPath(type == ResourceType.PAGE ? "/actual/" + id : null); row.setIcon("FileOutlined"); em.persist(row); return row;}
  Resource node(String id, String parent, ResourceType type) {var row = new Resource(); row.setId(id); row.setCode(id); row.setName("Actual " + id); row.setParentId(parent);
    row.setType(type); row.setScopeTypes(Set.of(ResourceScopeType.TENANT)); row.setDisabled(false); row.setPublicAccess(true); row.setGrantable(true); row.setPermissionMetadata("Private internal metadata " + id);
    em.persist(row); return row;}
  void evidence() {em.flush(); em.clear(); statements.clear(); factory.getStatistics().clear();}
  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder list(String type) {return get("/users/me/access/resources").param("type", type).param("size", "24")
      .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);}
  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder detail(String code) {return get("/users/me/access/page-detail").param("code", code).requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);}
  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder organization() {return get("/users/me/organization").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);}
  Organization department(String tenant, String id, String parent, String name) {var row = new Organization(); row.setTenantId(tenant); row.setId(id); row.setCode(id); row.setName(name);
    row.setParentId(parent); row.setDescription("Private organization configuration " + id); row.setEnabled(true); em.persist(row); return row;}
  TenantUserRelevance membership(String tenant, String user, String org) {var row = new TenantUserRelevance(); row.setTenantId(tenant); row.setUserId(user); row.setOrgId(org); em.persist(row); return row;}
  void bounded() {assertEquals(0, factory.getStatistics().getEntityLoadCount()); var pattern = Pattern.compile("(?i)\\bin\\s*\\(([^)]*)\\)");
    for (String sql : statements) {var matcher = pattern.matcher(sql); while (matcher.find()) assertTrue(matcher.group(1).chars().filter(c -> c == '?').count() <= 100, sql);
      assertFalse(sql.toLowerCase(Locale.ROOT).replace("_", "").contains("permissionmetadata"), sql);}}
  @AfterEach void cleanup() {RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY); org.springframework.security.core.context.SecurityContextHolder.clearContext();
    if (organizationOnly) verifyNoInteractions(resourceBoundary, tenants, users);
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();} if (factory != null) factory.close(); if (schemaContext != null) schemaContext.close();}

  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"MASKED", "HIDDEN"})
  void customPageAndBreadcrumbProjectActualTenantFieldsBeforeFallback(String access) throws Exception {
    custom(); customNode("directory", null, ResourceType.GROUP).setName("私有目录名称"); customNode("page", "directory", ResourceType.PAGE).setName("私有页面名称"); evidence();
    context = caller(Set.of("page")); context.setFieldPermissions(Map.of(TenantResource.class.getName() + "#name", access));
    var response = mvc.perform(list("PAGE")).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].label").value(access.equals("MASKED") ? "***" : "page"))
        .andExpect(jsonPath("$.content[0].directory[0]").value(access.equals("MASKED") ? "***" : "i18n:permissions.unavailable")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertFalse(response.contains("私有目录名称") || response.contains("私有页面名称")); bounded();
  }
  @Test void globalResourceNamePolicyCannotHideActualTenantPageNames() throws Exception {
    custom(); customNode("page", null, ResourceType.PAGE).setName("租户公开名称"); evidence(); context = caller(Set.of("page")); context.setFieldPermissions(Map.of(Resource.class.getName() + "#name", "HIDDEN"));
    mvc.perform(list("PAGE")).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].label").value("租户公开名称")); bounded();
  }
  @Test void hiddenAliasesNamesAndDirectoriesCannotLeakThroughKeywordMatches() throws Exception {
    node("directory", null, ResourceType.GROUP).setName("PrivateDirectoryToken"); var page = node("page", "directory", ResourceType.PAGE);
    page.setAlias("PrivateAliasToken"); page.setName("PrivateNameToken"); page.setTitle("公开页面标题"); evidence(); context = caller(Set.of("page"));
    context.setFieldPermissions(Map.of(Resource.class.getName() + "#alias", "HIDDEN", Resource.class.getName() + "#name", "HIDDEN"));
    mvc.perform(list("PAGE")).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].label").value("公开页面标题"));
    for (String secret : List.of("PrivateAliasToken", "PrivateNameToken")) mvc.perform(list("PAGE").param("keyword", secret)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    bounded();
  }
  @Test void platformDetailPreservesAllOperationsWithoutFullEntitiesOrLargeMembershipBindings() throws Exception {
    node("page", null, ResourceType.PAGE); var held = new LinkedHashSet<String>(); held.add("page");
    for (int i = 0; i < 521; i++) {String id = "operation-" + String.format("%03d", i); node(id, "page", ResourceType.ACTION); held.add(id);}
    node("unheld", "page", ResourceType.ACTION); node("other-page", "page", ResourceType.PAGE); node("other-operation", "other-page", ResourceType.ACTION); evidence(); context = caller(held);
    mvc.perform(detail("page")).andExpect(status().isOk()).andExpect(jsonPath("$.operations.length()").value(521)); bounded();
  }
  @Test void narrowCustomPageReadDoesNotHydrateUnrelatedPresentationOrRepeatedCompleteDirectories() throws Exception {
    custom(); customNode("directory", null, ResourceType.GROUP); customNode("page", "directory", ResourceType.PAGE);
    for (int i = 0; i < 521; i++) customNode("unrelated-" + i, null, ResourceType.PAGE); evidence(); context = caller(Set.of("page"));
    mvc.perform(list("PAGE")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].directory[0]").value("Actual directory"));
    assertTrue(statements.stream().filter(sql -> sql.contains("simpoint_saas_tenant_resources")).allMatch(sql -> sql.contains(" in ")), "The one-page preview must not scan the complete tenant directory"); bounded();
  }
  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"MASKED", "HIDDEN"})
  void protectedTenantCodesAreNotExposedOrAcceptedAsPublicDetailIdentifiers(String access) throws Exception {
    custom(); customNode("page", null, ResourceType.PAGE); evidence(); context = caller(Set.of("page")); context.setFieldPermissions(Map.of(TenantResource.class.getName() + "#code", access));
    var response = mvc.perform(list("PAGE")).andExpect(status().isOk());
    if (access.equals("MASKED")) response.andExpect(jsonPath("$.content[0].code").value("***")); else response.andExpect(jsonPath("$.content[0].code").doesNotExist());
    statements.clear(); mvc.perform(detail("page")).andExpect(status().isForbidden());
    assertTrue(statements.stream().noneMatch(sql -> sql.contains("simpoint_saas_tenant_resources") || sql.contains("simpoint_ac_resources")), "Protected public code lookup fails before resource metadata SQL");
  }
  @Test void foreignRoleAndBlankCallerFailBeforeCatalogRead() throws Exception {
    context = caller(Set.of("page")); when(tenants.getCurrentUserRoles("tenant-a")).thenReturn(List.of(new RoleGrantedAuthority("mine", "Mine", "MINE")));
    mvc.perform(list("PAGE").param("roleId", "foreign")).andExpect(status().isForbidden()); assertTrue(statements.isEmpty());
    context = new AuthorizationContext(); context.setUserId(" "); mvc.perform(list("PAGE")).andExpect(status().isForbidden()); assertTrue(statements.isEmpty());
  }

  @Test void ownedRolePreviewSeparatesNativeAvailableRowsFromCurrentEffectiveGrants() throws Exception {
    node("page", null, ResourceType.PAGE); node("action", "page", ResourceType.ACTION); evidence(); context = caller(Set.of("page"));
    when(tenants.getCurrentUserRoles("tenant-a")).thenReturn(List.of(new RoleGrantedAuthority("mine", "Mine", "MINE")));
    when(users.loadResourcesInRoleIds(List.of("mine"))).thenReturn(Set.of("page", "action", "unavailable"));
    mvc.perform(list("ALL").param("roleId", "mine")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.content[0].code").value("page")).andExpect(jsonPath("$.content[0].effective").value(true))
        .andExpect(jsonPath("$.content[1].code").value("action")).andExpect(jsonPath("$.content[1].effective").value(false))
        .andExpect(jsonPath("$.content[1].available").value(true)).andExpect(jsonPath("$.content[2].code").value("unavailable"))
        .andExpect(jsonPath("$.content[2].effective").value(false)).andExpect(jsonPath("$.content[2].available").value(false));
    mvc.perform(list("ALL")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)); bounded();
  }

  @Test void actualCustomAliasRetainsBackingOperationsButNotAnotherPagesSubtree() throws Exception {
    custom(); customNode("target", null, ResourceType.PAGE).setPlatformResourceId("target");
    customNode("alias", null, ResourceType.PAGE).setTargetResourceId("target");
    customNode("action", "target", ResourceType.ACTION); customNode("unheld", "target", ResourceType.ACTION);
    customNode("other", "target", ResourceType.PAGE); customNode("other-action", "other", ResourceType.ACTION);
    evidence(); context = caller(Set.of("target", "alias", "action", "other-action"));
    mvc.perform(list("PAGE")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].code").value("alias"));
    mvc.perform(detail("alias")).andExpect(status().isOk()).andExpect(jsonPath("$.operations.length()").value(1)).andExpect(jsonPath("$.operations[0].code").value("action")); bounded();
  }

  @Test void protectedTypeCannotBeUsedAsAnUndisclosedFilter() throws Exception {
    evidence(); context = caller(Set.of("page")); context.setFieldPermissions(Map.of(Resource.class.getName() + "#type", "HIDDEN"));
    mvc.perform(list("PAGE")).andExpect(status().isForbidden());
    mvc.perform(detail("page")).andExpect(status().isForbidden());
    assertTrue(statements.stream().noneMatch(sql -> sql.contains("simpoint_saas_tenant_resources") || sql.contains("simpoint_ac_resources")), "Mode resolution is permitted, protected resource filtering is not");
  }

  @Tag("self-organization")
  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"MASKED", "HIDDEN"})
  void selfOrganizationUsesActualSourceProtectionWithoutHydratingMembershipOrOrganization(String access) throws Exception {
    department("tenant-a", "private-root-id", null, "私有上级组织"); department("tenant-a", "private-leaf-id", "private-root-id", "私有所属组织");
    membership("tenant-a", "reader", "private-leaf-id"); evidence();
    context = caller(Set.of()); context.setFieldPermissions(Map.of(Organization.class.getName() + "#name", access, Organization.class.getName() + "#id", access));
    var result = mvc.perform(organization()).andExpect(status().isOk());
    if (access.equals("MASKED")) result.andExpect(jsonPath("$.id").value("***")).andExpect(jsonPath("$.name").value("***"));
    else result.andExpect(jsonPath("$.id").doesNotExist()).andExpect(jsonPath("$.name").doesNotExist());
    result.andExpect(jsonPath("$.path.length()").value(2)).andExpect(jsonPath("$.path[0]").value(access.equals("MASKED") ? "***" : "i18n:permissions.unavailable"));
    String json = result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertFalse(json.contains("私有") || json.contains("private-root-id") || json.contains("private-leaf-id")); bounded();
    assertTrue(statements.stream().noneMatch(sql -> sql.contains("description") || sql.contains("revision") || sql.contains("user_type")), "Only membership orgId and organization id/parent/name may be selected");
  }

  @Tag("self-organization")
  @Test void hiddenOrganizationIdentityDoesNotReplaceLeafNameWithItsParentAndOtherModelsCannotHideNames() throws Exception {
    department("tenant-a", "root", null, "实际上级组织"); department("tenant-a", "leaf", "root", "实际所属组织"); membership("tenant-a", "reader", "leaf"); evidence();
    context = caller(Set.of()); context.setFieldPermissions(Map.of(Organization.class.getName() + "#id", "HIDDEN", Resource.class.getName() + "#name", "HIDDEN", TenantResource.class.getName() + "#name", "HIDDEN"));
    mvc.perform(organization()).andExpect(status().isOk()).andExpect(jsonPath("$.id").doesNotExist()).andExpect(jsonPath("$.name").value("实际所属组织"))
        .andExpect(jsonPath("$.path[0]").value("实际上级组织")).andExpect(jsonPath("$.path[1]").value("实际所属组织")); bounded();
  }

  @Tag("self-organization")
  @Test void organizationRemainsLimitedToActiveOwnMembershipAndSameTenantNodes() throws Exception {
    department("tenant-b", "foreign", null, "其他租户私有组织"); department("tenant-a", "other-user-org", null, "其他成员私有组织");
    membership("tenant-a", "reader", "foreign"); membership("tenant-a", "someone-else", "other-user-org"); evidence();
    mvc.perform(organization()).andExpect(status().isOk()).andExpect(jsonPath("$.path.length()").value(0)).andExpect(jsonPath("$.name").doesNotExist()); bounded();
    var own = em.createQuery("select m from TenantUserRelevance m where m.tenantId = 'tenant-a' and m.userId = 'reader'", TenantUserRelevance.class).getSingleResult();
    own.setOrgId("other-user-org"); own.setEnabled(false); evidence();
    mvc.perform(organization()).andExpect(status().isOk()).andExpect(jsonPath("$.path.length()").value(0)); bounded();
    assertEquals(1, statements.size(), "Disabled membership must not query any organization");
  }

  @Tag("self-organization")
  @Test void organizationPathKeepsExistingTwentyNodeBound() throws Exception {
    for (int i = 0; i < 25; i++) department("tenant-a", "depth-" + i, i == 0 ? null : "depth-" + (i - 1), "节点 " + i);
    membership("tenant-a", "reader", "depth-24"); evidence();
    mvc.perform(organization()).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("节点 24")).andExpect(jsonPath("$.path.length()").value(20))
        .andExpect(jsonPath("$.path[0]").value("节点 5")).andExpect(jsonPath("$.path[19]").value("节点 24")); bounded(); assertEquals(21, statements.size());
  }

  @Tag("self-organization")
  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"DISABLED", "DELETED", "FOREIGN", "MISSING", "CYCLE"})
  void organizationParentsKeepExistingPartialPathAndCycleBoundary(String boundary) throws Exception {
    department("tenant-a", "leaf", "parent", "实际所属组织");
    if (!boundary.equals("MISSING")) {
      var parent = department(boundary.equals("FOREIGN") ? "tenant-b" : "tenant-a", "parent", boundary.equals("CYCLE") ? "leaf" : null, "上级组织");
      if (boundary.equals("DISABLED")) parent.setEnabled(false);
      if (boundary.equals("DELETED")) parent.setDeletedAt(java.time.Instant.now());
    }
    membership("tenant-a", "reader", "leaf"); evidence();
    var result = mvc.perform(organization()).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("实际所属组织"))
        .andExpect(jsonPath("$.path.length()").value(boundary.equals("CYCLE") ? 2 : 1));
    if (!boundary.equals("CYCLE")) result.andExpect(jsonPath("$.path[0]").value("实际所属组织")); bounded();
  }

  @Tag("self-organization")
  @Test void browserOrganizationContractsUseActualReadableProtectedAndUnassignedSelfRows() throws Exception {
    department("tenant-a", "native-org-root", null, "原生上级部门"); department("tenant-a", "native-org-leaf", "native-org-root", "原生所属部门");
    var member = membership("tenant-a", "reader", "native-org-leaf"); var memberId = member.getId(); evidence();
    var outputs = new LinkedHashMap<String, String>();
    for (String access : List.of("READABLE", "MASKED", "HIDDEN")) {
      context = caller(Set.of()); if (!access.equals("READABLE")) context.setFieldPermissions(Map.of(Organization.class.getName() + "#name", access, Organization.class.getName() + "#id", access));
      var result = mvc.perform(organization()).andExpect(status().isOk());
      if (access.equals("MASKED")) result.andExpect(jsonPath("$.name").value("***"));
      if (access.equals("HIDDEN")) result.andExpect(jsonPath("$.name").doesNotExist()).andExpect(jsonPath("$.path[0]").value("i18n:permissions.unavailable"));
      outputs.put("organization-" + access.toLowerCase(Locale.ROOT), result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    bounded(); em.find(TenantUserRelevance.class, memberId).setEnabled(false); evidence(); context = caller(Set.of());
    outputs.put("organization-unassigned", mvc.perform(organization()).andExpect(status().isOk()).andExpect(jsonPath("$.path.length()").value(0)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); bounded();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    for (var output : outputs.entrySet()) {var value = mapper.readTree(output.getValue()); var generated = java.nio.file.Path.of("build/generated-test-resources/profile-access-" + output.getKey() + ".json");
      java.nio.file.Files.createDirectories(generated.getParent()); java.nio.file.Files.writeString(generated, value.toString());}
    for (var output : outputs.entrySet()) assertEquals(mapper.readTree(java.nio.file.Path.of("../../simplepoint-react/scripts/profile-access-contracts/" + output.getKey() + ".json").toFile()), mapper.readTree(output.getValue()));
  }

  @Tag("self-organization")
  @Test void platformAndUnauthenticatedOrganizationRequestsDoNotReadTenantData() throws Exception {
    evidence(); context = new AuthorizationContext(); context.setUserId("reader"); context.setScopeType(AuthorizationScopeType.PLATFORM); context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    mvc.perform(organization()).andExpect(status().isOk()).andExpect(jsonPath("$.path.length()").value(0)); assertTrue(statements.isEmpty());
    context = new AuthorizationContext(); context.setUserId(" "); mvc.perform(organization()).andExpect(status().isForbidden()); assertTrue(statements.isEmpty());
  }

  @Test void overBudgetDetailStopsAfterTheFirstOverflowNodeWithoutReadingAllSiblings() throws Exception {
    node("page", null, ResourceType.PAGE); for (int i = 0; i < 6010; i++) node("child-" + String.format("%04d", i), "page", ResourceType.ACTION);
    evidence(); context = caller(Set.of("page")); mvc.perform(detail("page")).andExpect(status().isUnprocessableEntity()); bounded();
    long structuralRows = Arrays.stream(factory.getStatistics().getQueries()).filter(query -> query.startsWith("select r.id as id, r.parentId as parent, r.code as code, r.type as type") && query.contains("r.parentId in :values"))
        .mapToLong(query -> factory.getStatistics().getQueryStatistics(query).getExecutionRowCount()).sum();
    assertEquals(5001, structuralRows, "A 5000-node detail budget must stop at its first overflow witness, not hydrate all siblings");
  }

  @Test void browserProfileContractsUseActualReadableAndProtectedHttpRows() throws Exception {
    custom(); customNode("directory", null, ResourceType.GROUP).setName("原生权限目录");
    customNode("profile-page", "directory", ResourceType.PAGE).setName("原生权限页面");
    customNode("profile-second", "directory", ResourceType.PAGE).setName("第二权限页面");
    customNode("profile-edit", "profile-page", ResourceType.ACTION).setName("编辑权限"); evidence();
    var outputs = new LinkedHashMap<String, String>(); context = caller(Set.of("profile-page", "profile-second", "profile-edit"));
    outputs.put("readable-page", mvc.perform(list("PAGE")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    outputs.put("readable-detail", mvc.perform(detail("profile-page")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    for (String access : List.of("MASKED", "HIDDEN")) {context = caller(Set.of("profile-page", "profile-second", "profile-edit"));
      context.setFieldPermissions(Map.of(TenantResource.class.getName() + "#code", access));
      outputs.put(access.toLowerCase(Locale.ROOT) + "-page", mvc.perform(list("PAGE")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    for (var output : outputs.entrySet()) {var value = mapper.readTree(output.getValue()); var generated = java.nio.file.Path.of("build/generated-test-resources/profile-access-" + output.getKey() + ".json");
      java.nio.file.Files.createDirectories(generated.getParent()); java.nio.file.Files.writeString(generated, value.toString());}
    for (var output : outputs.entrySet()) assertEquals(mapper.readTree(java.nio.file.Path.of("../../simplepoint-react/scripts/profile-access-contracts/" + output.getKey() + ".json").toFile()), mapper.readTree(output.getValue()));
    bounded();
  }
}
