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
import org.simplepoint.core.jackson.FieldScopeJackson3Module;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.service.RoleResourceConfigurationService.*;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.plugin.rbac.core.rest.controller.RoleResourceConfigurationController;
import org.simplepoint.plugin.rbac.core.service.impl.RoleResourceConfigurationServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.entity.*;
import org.simplepoint.security.entity.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/** Real SQL pagination/visibility and HTTP field projection with isolated H2 data. */
class RoleResourceConfigurationHttpTest {
  SessionFactory factory;
  Session em;
  RoleService roles;
  RoleRepository repository;
  ResourceAuthorizationVersionService authorizationVersions;
  RoleResourceConfigurationServiceImpl service;
  Role role;
  Role foreign;
  AuthorizationContext context;
  MockMvc mvc;
  List<Resource> resources = new ArrayList<>();

  @BeforeEach
  void setup() {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:resource-editor-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .applySetting("hibernate.generate_statistics", true).build();
    factory = new MetadataSources(registry).addAnnotatedClass(Role.class)
        .addAnnotatedClass(RoleScopeBinding.class).addAnnotatedClass(Resource.class)
        .addAnnotatedClass(ResourceAncestor.class).addAnnotatedClass(RoleResourceGrant.class)
        .addAnnotatedClass(DataScope.class).addAnnotatedClass(FieldScope.class)
        .addAnnotatedClass(FieldScopeEntry.class)
        .addAnnotatedClass(TenantPackageRelevance.class)
        .addAnnotatedClass(PackageApplicationRelevance.class)
        .addAnnotatedClass(ApplicationResourceRelevance.class)
        .addAnnotatedClass(TenantResource.class)
        .buildMetadata().buildSessionFactory();
    em = factory.openSession(); em.beginTransaction();
    role = new Role(); role.setTenantId("tenant-a"); role.setRoleName("Manager");
    role.setAuthority("MANAGER"); em.persist(role);
    foreign = new Role(); foreign.setTenantId("tenant-b"); foreign.setRoleName("Other");
    foreign.setAuthority("OTHER"); em.persist(foreign);
    for (int i = 0; i < 243; i++) {
      Resource resource = new Resource();
      resource.setCode("resource-" + String.format("%03d", i));
      resource.setName("Resource " + String.format("%03d", i)); resource.setType(ResourceType.ACTION);
      resource.setGrantable(true); resource.setDisabled(i == 240);
      resource.setPublicAccess(true); resource.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
      if (i == 241) resource.setDeletedAt(Instant.now());
      if (i == 242) resource.setScopeTypes(EnumSet.of(ResourceScopeType.PLATFORM));
      em.persist(resource); resources.add(resource);
      if (i < 240 && i % 2 == 0) grant(resource, "tenant-a");
      grant(resource, "tenant-b");
    }
    grant(resources.get(0), "tenant-a"); // Duplicate legacy grants must not multiply list/count rows.
    resources.get(2).setParentId(resources.get(1).getId());
    resources.get(4).setParentId(resources.get(1).getId());
    em.flush(); em.clear();
    context = new AuthorizationContext(); context.setUserId("owner");
    context.setScopeType(AuthorizationScopeType.TENANT); context.setIsAdministrator(false);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    roles = mock(RoleService.class); when(roles.requireManagementTenant()).thenReturn("tenant-a");
    repository = mock(RoleRepository.class);
    when(repository.lockForAuthorization("tenant-a", role.getId())).thenReturn(Optional.of(role));
    doAnswer(call -> {
      org.simplepoint.plugin.rbac.core.api.pojo.dto.RoleResourceGrantDto dto = call.getArgument(0);
      for (String code : dto.getResourceCodes()) {
        RoleResourceGrant grant = new RoleResourceGrant(); grant.setTenantId("tenant-a");
        grant.setRoleId(dto.getRoleId()); grant.setResourceCode(code); em.persist(grant);
      }
      return List.of();
    }).when(roles).authorize(any());
    doAnswer(call -> {
      em.createMutationQuery("delete RoleResourceGrant g where g.tenantId = 'tenant-a'"
          + " and g.roleId = :role and g.resourceCode in :codes")
          .setParameter("role", role.getId()).setParameter("codes", call.getArgument(1)).executeUpdate();
      return null;
    }).when(roles).unauthorized(anyString(), anySet());
    authorizationVersions = mock(ResourceAuthorizationVersionService.class);
    service = new RoleResourceConfigurationServiceImpl(em, roles, repository, authorizationVersions);
    mvc = MockMvcBuilders.standaloneSetup(new RoleResourceConfigurationController(service))
        .setMessageConverters(new JacksonJsonHttpMessageConverter(
            tools.jackson.databind.json.JsonMapper.builder().addModule(new FieldScopeJackson3Module()).build()))
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
  }

  RoleResourceGrant grant(Resource resource, String tenant) {
    var grant = new RoleResourceGrant(); grant.setTenantId(tenant); grant.setRoleId(role.getId());
    grant.setResourceCode(resource.getCode()); em.persist(grant); return grant;
  }

  String endpoint() { return "/role-resource-configuration/roles/" + role.getId(); }

  List<Row> list(String keyword, String state) {
    return service.list(role.getId(), keyword, state, null, null, false, false, PageRequest.of(0, 100)).getContent();
  }

  Change change(Row row, boolean granted) {
    return new Change(row.id(), row.code(), row.revision(), granted);
  }

  @AfterEach
  void close() {
    if (em != null) {if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();}
    if (factory != null) factory.close();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void dataAssetsAreNotAdvertisedBeforeTenantPageCoverageExists() {
    Resource page = new Resource();
    page.setCode("dna.data-assets.view"); page.setName("Data assets");
    page.setType(ResourceType.PAGE); page.setGrantable(true); page.setDisabled(false);
    page.setPublicAccess(true); page.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
    em.persist(page);
    DataScope allowed = new DataScope(); allowed.setTenantId("tenant-a");
    allowed.setName("Only my assets"); allowed.setType(DataScopeType.SELF); em.persist(allowed);
    em.flush(); em.clear();

    Row row = list("dna.data-assets", "all").stream().filter(item -> item.code().equals(page.getCode()))
        .findFirst().orElseThrow();
    assertFalse(row.dataPermission().supported());
    assertNull(row.dataPermission().policyId());
    assertThrows(ResponseStatusException.class, () -> service.save(role.getId(), new Patch(List.of(
        new Change(row.id(), row.code(), row.revision(), true,
            new PolicyUpdate(allowed.getId()), null)))));
  }

  @Test
  void organizationDataRuleCanBeListedBoundAndClearedOnlyWithinTenant() {
    Resource page = new Resource();
    page.setCode("organizations.view"); page.setName("Organizations");
    page.setPermissionMetadata(new org.simplepoint.core.permission.PermissionPageDescriptor(List.of(
        org.simplepoint.core.permission.PermissionModelDescriptor.describe(Organization.class))).toJson());
    page.setType(ResourceType.PAGE); page.setGrantable(true); page.setDisabled(false);
    page.setPublicAccess(true); page.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
    em.persist(page);
    RoleResourceGrant grant = grant(page, "tenant-a");
    DataScope allowed = new DataScope(); allowed.setTenantId("tenant-a");
    allowed.setName("Own organization"); allowed.setType(DataScopeType.DEPT); em.persist(allowed);
    DataScope foreignScope = new DataScope(); foreignScope.setTenantId("tenant-b");
    foreignScope.setName("Foreign organization"); foreignScope.setType(DataScopeType.ALL);
    em.persist(foreignScope);
    em.flush(); em.clear();

    var options = service.policies(role.getId(), "organizations.view", "data", "",
        PageRequest.of(0, 10));
    assertEquals(1, options.getTotalElements());
    assertEquals(allowed.getId(), options.getContent().getFirst().id());
    Row row = list("organizations.view", "all").stream()
        .filter(item -> item.code().equals("organizations.view")).findFirst().orElseThrow();
    assertTrue(row.dataPermission().supported());
    assertThrows(AccessDeniedException.class, () -> service.save(role.getId(), new Patch(List.of(
        new Change(row.id(), row.code(), row.revision(), true,
            new PolicyUpdate(foreignScope.getId()), null)))));
    service.save(role.getId(), new Patch(List.of(new Change(row.id(), row.code(), row.revision(),
        true, new PolicyUpdate(allowed.getId()), null))));
    em.flush(); em.clear();
    assertEquals(allowed.getId(), em.find(RoleResourceGrant.class, grant.getId()).getDataScopeId());
    Row assigned = list("organizations.view", "all").stream()
        .filter(item -> item.code().equals("organizations.view")).findFirst().orElseThrow();
    service.save(role.getId(), new Patch(List.of(new Change(assigned.id(), assigned.code(),
        assigned.revision(), true, new PolicyUpdate(null), null))));
    em.flush(); em.clear();
    assertNull(em.find(RoleResourceGrant.class, grant.getId()).getDataScopeId());
  }

  @Test
  void fieldPolicyOptionsAndAssignmentStayWithinThePageModel() {
    Resource page = new Resource();
    page.setCode("users.view"); page.setName("Members");
    page.setPermissionMetadata(new org.simplepoint.core.permission.PermissionPageDescriptor(List.of(
        org.simplepoint.core.permission.PermissionModelDescriptor.describe(User.class))).toJson());
    page.setType(ResourceType.PAGE); page.setGrantable(true); page.setDisabled(false);
    page.setPublicAccess(true); page.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
    em.persist(page);
    RoleResourceGrant grant = grant(page, "tenant-a");
    FieldScope allowed = new FieldScope(); allowed.setTenantId("tenant-a");
    allowed.setName("Hide email"); em.persist(allowed);
    FieldScopeEntry entry = new FieldScopeEntry(); entry.setTenantId("tenant-a");
    entry.setFieldScopeId(allowed.getId()); entry.setResource(User.class.getName());
    entry.setField("email"); entry.setAccess(FieldAccessType.HIDDEN); em.persist(entry);
    FieldScope unrelated = new FieldScope(); unrelated.setTenantId("tenant-a");
    unrelated.setName("Other model"); em.persist(unrelated);
    FieldScopeEntry unrelatedEntry = new FieldScopeEntry(); unrelatedEntry.setTenantId("tenant-a");
    unrelatedEntry.setFieldScopeId(unrelated.getId()); unrelatedEntry.setResource(Organization.class.getName());
    unrelatedEntry.setField("name"); unrelatedEntry.setAccess(FieldAccessType.HIDDEN); em.persist(unrelatedEntry);
    em.flush(); em.clear();

    var options = service.policies(role.getId(), "users.view", "field", "", PageRequest.of(0, 10));
    assertEquals(1, options.getTotalElements());
    assertEquals(allowed.getId(), options.getContent().getFirst().id());
    Row row = list("users.view", "all").stream().filter(item -> item.code().equals("users.view"))
        .findFirst().orElseThrow();
    assertTrue(row.fieldPermission().supported());
    assertThrows(ResponseStatusException.class, () -> service.save(role.getId(), new Patch(List.of(
        new Change(row.id(), row.code(), row.revision(), true, null,
            new PolicyUpdate(unrelated.getId()))))));
    service.save(role.getId(), new Patch(List.of(new Change(row.id(), row.code(), row.revision(), true,
        null, new PolicyUpdate(allowed.getId())))));
    em.flush(); em.clear();
    assertEquals(allowed.getId(), em.find(RoleResourceGrant.class, grant.getId()).getFieldScopeId());
  }

  @Test
  void customCatalogIsTheOnlySourceForRoleEditorAndSave() {
    TenantResource copy = new TenantResource();
    copy.setTenantId("tenant-a"); copy.setId(resources.get(1).getId());
    copy.setPlatformResourceId(resources.get(1).getId());
    copy.setCode(resources.get(1).getCode()); copy.setName("Workspace page");
    copy.setType(ResourceType.PAGE); copy.setGrantable(true); copy.setEnabled(true);
    em.persist(copy);
    TenantResource foreignCopy = new TenantResource();
    foreignCopy.setTenantId("tenant-b"); foreignCopy.setId(resources.get(2).getId());
    foreignCopy.setPlatformResourceId(resources.get(2).getId());
    foreignCopy.setCode(resources.get(2).getCode()); foreignCopy.setName("Foreign page");
    foreignCopy.setType(ResourceType.PAGE); foreignCopy.setGrantable(true); foreignCopy.setEnabled(true);
    em.persist(foreignCopy);
    em.flush();
    TenantResourceCatalog catalog = mock(TenantResourceCatalog.class);
    when(catalog.mode("tenant-a")).thenReturn(TenantResourceMode.CUSTOM);
    when(catalog.grantableCodes(eq("tenant-a"), anyCollection())).thenReturn(Set.of(copy.getCode()));
    service.setTenantResourceCatalog(catalog);
    var page = service.list(role.getId(), "", "all", null, null, false, false, PageRequest.of(0, 20));
    assertEquals(1, page.getTotalElements());
    assertEquals(copy.getId(), page.getContent().getFirst().id());
    Row row = page.getContent().getFirst();
    service.save(role.getId(), new Patch(List.of(change(row, true))));
    verify(roles).authorize(argThat(dto -> dto.getResourceCodes().contains(copy.getCode())));
  }

  @Test
  void paginatesBeforeLoadingAndProjectsReservedSlots() throws Exception {
    factory.getStatistics().clear();
    mvc.perform(get(endpoint() + "/resources").param("page", "11").param("size", "20")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(20))
        .andExpect(jsonPath("$.totalElements").value(240))
        .andExpect(jsonPath("$.content[0].code").value("resource-220"))
        .andExpect(jsonPath("$.content[0].granted").value(true))
        .andExpect(jsonPath("$.content[1].granted").value(false))
        .andExpect(jsonPath("$.content[0].dataPermission.supported").value(false))
        .andExpect(jsonPath("$.content[0].fieldPermission.supported").value(false));
    assertTrue(factory.getStatistics().getPrepareStatementCount() <= 5);
    assertTrue(factory.getStatistics().getEntityLoadCount() <= 31,
        "only page resources and this page's role grants may be loaded");
  }

  @Test
  void directoryCheckboxCanStageEveryUnloadedDescendantAndSaveAtomically() throws Exception {
    String rootId = resources.getFirst().getId();
    for (int i = 1; i < 240; i++) em.find(Resource.class, resources.get(i).getId()).setParentId(rootId);
    em.flush();
    var subtree = service.subtreeRows(role.getId(), rootId);
    assertEquals(240, subtree.size());
    assertTrue(subtree.stream().anyMatch(row -> row.code().equals("resource-239")));
    mvc.perform(get(endpoint() + "/resource-subtree/resources").param("resourceId", rootId)
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(240));
    var changes = subtree.stream().filter(row -> !row.granted())
        .map(row -> new Change(row.id(), row.code(), row.revision(), true)).toList();
    assertEquals(120, changes.size());
    service.save(role.getId(), new Patch(changes));
    em.flush();
    assertEquals(240, service.list(role.getId(), "", "granted", null, null,
        false, false, PageRequest.of(0, 20)).getTotalElements());
    var revoke = service.subtreeRows(role.getId(), rootId).stream()
        .map(row -> new Change(row.id(), row.code(), row.revision(), false)).toList();
    assertEquals(240, revoke.size());
    service.save(role.getId(), new Patch(revoke));
    em.flush();
    assertEquals(0, service.list(role.getId(), "", "granted", null, null,
        false, false, PageRequest.of(0, 20)).getTotalElements());
  }

  @Test
  void filtersInSqlAndKeepsTenantAndRoleBoundary() {
    var assigned = service.list(role.getId(), "", "granted", null, null, false, false, PageRequest.of(5, 20));
    assertEquals(120, assigned.getTotalElements());
    assertEquals("resource-200", assigned.getContent().getFirst().code());
    assertEquals(120, service.list(role.getId(), "", "ungranted", null, null, false, false, PageRequest.of(0, 20)).getTotalElements());
    assertEquals(2, service.list(role.getId(), "", "all", null, resources.get(1).getId(), false, false, PageRequest.of(0, 20)).getTotalElements());
    assertTrue(list("%", "all").isEmpty(), "wildcards are literal search text");
    assertThrows(AccessDeniedException.class, () -> service.list(foreign.getId(), "", "all", null, null, false, false, PageRequest.of(0, 20)));
    assertThrows(AccessDeniedException.class, () -> service.save(foreign.getId(), new Patch(List.of())));
    assertThrows(ResponseStatusException.class, () -> service.list(role.getId(), "", "all", null, null, false, false, PageRequest.of(0, 101)));
  }

  @Test
  void loadsOnlyOneTreeLevelAndProjectsResourceIcon() throws Exception {
    Resource parent = em.find(Resource.class, resources.get(1).getId());
    parent.setIcon("AppstoreOutlined"); em.flush();
    mvc.perform(get(endpoint() + "/resources").param("rootsOnly", "true")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(238));
    mvc.perform(get(endpoint() + "/resources").param("parentId", parent.getId())
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
    Row row = service.list(role.getId(), parent.getCode(), "all", null, null, false, false,
        PageRequest.of(0, 20)).getContent().getFirst();
    assertEquals("AppstoreOutlined", row.icon());
    assertTrue(row.hasChildren());
    assertThrows(ResponseStatusException.class, () -> service.list(role.getId(), "", "all",
        null, parent.getId(), true, false, PageRequest.of(0, 20)));
  }

  @Test
  void navigationTreeExcludesActionsAndApisWithoutHidingTheirGrants() throws Exception {
    Resource parent = em.find(Resource.class, resources.get(1).getId());
    Resource api = em.find(Resource.class, resources.get(4).getId());
    parent.setType(ResourceType.PAGE);
    api.setType(ResourceType.API);
    em.flush();
    Row navigationParent = service.list(role.getId(), parent.getCode(), "all", null, null,
        false, true, PageRequest.of(0, 20)).getContent().getFirst();
    assertTrue(navigationParent.hasChildren(), "operations remain available in the detail panel");
    assertFalse(navigationParent.hasNavigationChildren(), "operation-only children do not expand the tree");
    mvc.perform(get(endpoint() + "/resources").param("parentId", parent.getId())
        .param("navigationOnly", "true")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    assertEquals(2, service.list(role.getId(), "", "all", null, parent.getId(),
        false, false, PageRequest.of(0, 20)).getTotalElements());
    api.setType(ResourceType.PAGE);
    em.flush();
    assertTrue(service.list(role.getId(), parent.getCode(), "all", null, null,
        false, true, PageRequest.of(0, 20)).getContent().getFirst().hasNavigationChildren());
    mvc.perform(get(endpoint() + "/resources").param("parentId", parent.getId())
        .param("navigationOnly", "true")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].code").value(api.getCode()));
  }

  @Test
  void packageMembershipAndScopeSetsAreFilteredBeforeCounting() {
    Resource resource = em.find(Resource.class, resources.get(1).getId());
    resource.setPublicAccess(false); em.flush();
    assertTrue(list(resource.getCode(), "all").isEmpty());
    var tp = new TenantPackageRelevance(); tp.setTenantId("tenant-a"); tp.setPackageCode("package"); em.persist(tp);
    var pa = new PackageApplicationRelevance(); pa.setPackageCode("package"); pa.setApplicationCode("app"); em.persist(pa);
    var ar = new ApplicationResourceRelevance(); ar.setApplicationCode("app"); ar.setResourceCode(resource.getCode()); em.persist(ar);
    resource.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT, ResourceScopeType.PERSONAL));
    em.flush(); assertEquals(1, list(resource.getCode(), "all").size());
    context.replaceScopeType(AuthorizationScopeType.PERSONAL);
    assertEquals(1, service.list(role.getId(), "", "all", null, null, false, false, PageRequest.of(0, 20)).getTotalElements());
    ar.setDeletedAt(Instant.now()); em.flush(); assertTrue(list(resource.getCode(), "all").isEmpty());
  }

  @Test
  void savesOnlyExplicitChangesAndRejectsConflictBeforeAnyWrite() {
    Row remove = list("resource-000", "all").getFirst();
    Row add = list("resource-001", "all").getFirst();
    grant(resources.get(1), "tenant-a"); em.flush();
    ResponseStatusException failure = assertThrows(ResponseStatusException.class,
        () -> service.save(role.getId(), new Patch(List.of(change(remove, false), change(add, true)))));
    assertEquals(409, failure.getStatusCode().value());
    verify(roles, never()).unauthorized(anyString(), anySet());
    verify(roles, never()).authorize(any());
    assertTrue(list("resource-000", "all").getFirst().granted());
    Row fresh = list("resource-003", "all").getFirst();
    service.save(role.getId(), new Patch(List.of(change(remove, false), change(fresh, true))));
    em.flush();
    assertFalse(list("resource-000", "all").getFirst().granted());
    assertTrue(list("resource-003", "all").getFirst().granted());
    assertTrue(list("resource-238", "all").getFirst().granted());
    verify(repository, times(2)).lockForAuthorization("tenant-a", role.getId());
    verify(roles, never()).updateScopeAssignment(any());
  }

  @Test
  void legacyPoliciesCannotDisappearWhenTheirResourceIsRevoked() {
    RoleResourceGrant legacy = grant(resources.get(0), "tenant-a");
    legacy.setDataScopeId("legacy-data"); legacy.setFieldScopeId("legacy-fields"); em.flush();
    Row row = list("resource-000", "all").getFirst();
    ResponseStatusException failure = assertThrows(ResponseStatusException.class,
        () -> service.save(role.getId(), new Patch(List.of(change(row, false)))));
    assertTrue(failure.getReason().contains("迁移旧策略"));
    verify(roles, never()).unauthorized(anyString(), anySet());
    RoleScopeBinding binding = new RoleScopeBinding();
    binding.setTenantId("tenant-a"); binding.setRoleId(role.getId());
    binding.setDataScopeId("legacy-data"); binding.setFieldScopeId("legacy-fields"); em.persist(binding);
    em.flush();
    assertEquals(1L, em.createQuery("select count(b) from RoleScopeBinding b where b.tenantId = :tenant and b.roleId = :role and b.deletedAt is null", Long.class).setParameter("tenant", "tenant-a").setParameter("role", role.getId()).getSingleResult());
    service.save(role.getId(), new Patch(List.of(change(row, false))));
    em.flush(); em.clear();
    assertEquals("legacy-data", em.find(RoleScopeBinding.class, binding.getId()).getDataScopeId());
    assertFalse(list("resource-000", "all").getFirst().granted());
  }

  @Test
  void omittedGrantDecisionAndDuplicateOrOversizedChangesCannotRevokeAnything() {
    Row row = list("resource-000", "all").getFirst();
    for (Patch patch : List.of(
        new Patch(List.of(new Change(row.id(), row.code(), row.revision(), null))),
        new Patch(List.of(change(row, false), change(row, false))),
        new Patch(Collections.nCopies(201, change(row, false))))) {
      assertEquals(400, assertThrows(ResponseStatusException.class,
          () -> service.save(role.getId(), patch)).getStatusCode().value());
    }
    verify(roles, never()).unauthorized(anyString(), anySet());
    verify(roles, never()).authorize(any());
  }

  @Test
  void managementRevocationStopsBothReadsAndSaves() {
    when(roles.requireManagementTenant()).thenThrow(new AccessDeniedException("owner required"));
    assertThrows(AccessDeniedException.class, () -> list("", "all"));
    assertThrows(AccessDeniedException.class, () -> service.save(role.getId(), new Patch(List.of())));
    verify(repository, never()).lockForAuthorization(anyString(), anyString());
  }

  @Test
  void hiddenGrantStateCannotBeInferredThroughCountsOrWrites() {
    context.setFieldPermissions(Map.of(RoleResourceGrant.class.getName() + "#resourceCode", "HIDDEN"));
    factory.getStatistics().clear();
    assertThrows(AccessDeniedException.class, () -> list("", "granted"));
    assertThrows(AccessDeniedException.class, () -> service.save(role.getId(), new Patch(List.of())));
    assertEquals(0, factory.getStatistics().getPrepareStatementCount());
  }

  @Test
  void hiddenResourceFieldsAreExcludedFromHttpAndSearch() throws Exception {
    context.setFieldPermissions(Map.of(Resource.class.getName() + "#name", "HIDDEN"));
    assertTrue(list("Resource 1", "all").isEmpty());
    mvc.perform(get(endpoint() + "/resources").param("size", "1")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].label").doesNotExist());
  }

  @Test
  void httpPatchUsesRevisionAndDoesNotAcceptFullReplacement() throws Exception {
    Row row = list("resource-001", "all").getFirst();
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    mvc.perform(put(endpoint() + "/resource-changes").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(new Patch(List.of(change(row, true)))))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isNoContent());
    mvc.perform(put(endpoint() + "/resource-changes").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(new Patch(List.of(change(row, true)))))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isConflict());
    mvc.perform(put(endpoint() + "/resource-changes").contentType(MediaType.APPLICATION_JSON)
        .content("{\"resourceCodes\":[]}")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest());
  }

  @Test
  void subtreeChangeIncludesUnloadedDescendantsAndRejectsStalePreview() throws Exception {
    Resource root = em.find(Resource.class, resources.get(0).getId());
    root.setType(ResourceType.GROUP); root.setGrantable(false);
    for (int i = 1; i < 240; i++) {
      Resource child = em.find(Resource.class, resources.get(i).getId());
      if (i != 2 && i != 4) child.setParentId(root.getId());
      ResourceAncestor relation = new ResourceAncestor();
      relation.setAncestorId(root.getId()); relation.setChildId(child.getId()); em.persist(relation);
    }
    for (int i : List.of(2, 4)) {
      ResourceAncestor relation = new ResourceAncestor();
      relation.setAncestorId(resources.get(1).getId());
      relation.setChildId(resources.get(i).getId()); em.persist(relation);
    }
    ResourceAncestor hidden = new ResourceAncestor();
    hidden.setAncestorId(root.getId()); hidden.setChildId(resources.get(242).getId()); em.persist(hidden);
    em.flush();
    BulkPreview preview = service.previewSubtree(role.getId(), root.getId(), true);
    assertEquals(239, preview.total());
    assertEquals(120, preview.changed());
    mvc.perform(get(endpoint() + "/resource-subtree").param("resourceId", root.getId())
        .param("granted", "true")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(239))
        .andExpect(jsonPath("$.changed").value(120));
    grant(resources.get(1), "tenant-a"); em.flush();
    assertEquals(409, assertThrows(ResponseStatusException.class,
        () -> service.saveSubtree(role.getId(),
            new BulkCommand(root.getId(), true, preview.revision()))).getStatusCode().value());
    verify(roles, never()).authorize(any());
    BulkPreview fresh = service.previewSubtree(role.getId(), root.getId(), true);
    assertEquals(119, fresh.changed());
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    mvc.perform(put(endpoint() + "/resource-subtree").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(new BulkCommand(root.getId(), true, fresh.revision())))
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isNoContent());
    em.flush();
    assertEquals(0, service.previewSubtree(role.getId(), root.getId(), true).changed());
    assertTrue(list("resource-239", "all").getFirst().granted());
    assertFalse(list("resource-242", "all").stream().anyMatch(Row::granted),
        "invisible descendant must not be authorized");
    BulkPreview revoke = service.previewSubtree(role.getId(), root.getId(), false);
    assertEquals(239, revoke.changed());
    service.saveSubtree(role.getId(), new BulkCommand(root.getId(), false, revoke.revision()));
    em.flush();
    assertEquals(0, service.previewSubtree(role.getId(), root.getId(), false).changed());
    assertFalse(list("resource-239", "all").getFirst().granted());
  }

  @Test
  void subtreeRevocationPreservesLegacyScopePolicy() {
    Resource parent = em.find(Resource.class, resources.get(1).getId());
    Resource child = em.find(Resource.class, resources.get(2).getId());
    ResourceAncestor relation = new ResourceAncestor();
    relation.setAncestorId(parent.getId()); relation.setChildId(child.getId()); em.persist(relation);
    RoleResourceGrant legacy = grant(child, "tenant-a");
    legacy.setDataScopeId("legacy-data"); em.flush();
    BulkPreview preview = service.previewSubtree(role.getId(), parent.getId(), false);
    assertEquals(3, preview.total());
    assertEquals(2, preview.changed());
    ResponseStatusException failure = assertThrows(ResponseStatusException.class,
        () -> service.saveSubtree(role.getId(),
            new BulkCommand(parent.getId(), false, preview.revision())));
    assertEquals(409, failure.getStatusCode().value());
    verify(roles, never()).unauthorized(anyString(), anySet());
  }
}
