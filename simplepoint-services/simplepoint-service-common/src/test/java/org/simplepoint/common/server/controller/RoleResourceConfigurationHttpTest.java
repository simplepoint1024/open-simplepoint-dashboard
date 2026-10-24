package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.jackson.FieldScopeJackson3Module;
import org.simplepoint.plugin.rbac.core.api.repository.RoleRepository;
import org.simplepoint.plugin.rbac.core.api.service.RoleResourceConfigurationService.BulkCommand;
import org.simplepoint.plugin.rbac.core.api.service.RoleResourceConfigurationService.BulkPreview;
import org.simplepoint.plugin.rbac.core.api.service.RoleResourceConfigurationService.Change;
import org.simplepoint.plugin.rbac.core.api.service.RoleResourceConfigurationService.Patch;
import org.simplepoint.plugin.rbac.core.api.service.RoleResourceConfigurationService.PolicyUpdate;
import org.simplepoint.plugin.rbac.core.api.service.RoleResourceConfigurationService.Row;
import org.simplepoint.plugin.rbac.core.api.service.RoleService;
import org.simplepoint.plugin.rbac.core.rest.controller.RoleResourceConfigurationController;
import org.simplepoint.plugin.rbac.core.service.impl.RoleResourceConfigurationServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceEntitlement;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.DataScope;
import org.simplepoint.security.entity.DataScopeType;
import org.simplepoint.security.entity.FieldAccessType;
import org.simplepoint.security.entity.FieldScope;
import org.simplepoint.security.entity.FieldScopeEntry;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.simplepoint.security.entity.Role;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.simplepoint.security.entity.RoleScopeBinding;
import org.simplepoint.security.entity.User;
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
  List<TenantResource> resources = new ArrayList<>();

  @BeforeEach
  void setup() {
    var registry =
        new StandardServiceRegistryBuilder()
            .applySetting(
                "hibernate.connection.url", "jdbc:h2:mem:resource-editor-" + UUID.randomUUID())
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .applySetting("hibernate.generate_statistics", true)
            .build();
    factory =
        new MetadataSources(registry)
            .addAnnotatedClass(Role.class)
            .addAnnotatedClass(RoleScopeBinding.class)
            .addAnnotatedClass(TenantResource.class)
            .addAnnotatedClass(RoleResourceGrant.class)
            .addAnnotatedClass(DataScope.class)
            .addAnnotatedClass(FieldScope.class)
            .addAnnotatedClass(FieldScopeEntry.class)
            .addAnnotatedClass(Tenant.class)
            .addAnnotatedClass(TenantResourceSetup.class)
            .addAnnotatedClass(TenantResourceEntitlement.class)
            .buildMetadata()
            .buildSessionFactory();
    em = factory.openSession();
    em.beginTransaction();
    for (String id : List.of("tenant-a", "tenant-b")) {
      Tenant tenant = new Tenant();
      tenant.setId(id);
      tenant.setName(id);
      tenant.setOwnerId("owner");
      tenant.setTenantType(TenantType.ORGANIZATION);
      tenant.setAuthorizationVersion(0L);
      em.persist(tenant);
      TenantResourceSetup setup = new TenantResourceSetup();
      setup.setTenantId(id);
      setup.setState(TenantResourceSetup.State.READY);
      setup.setInitializedAt(Instant.now());
      em.persist(setup);
    }
    role = new Role();
    role.setTenantId("tenant-a");
    role.setRoleName("Manager");
    role.setAuthority("MANAGER");
    em.persist(role);
    foreign = new Role();
    foreign.setTenantId("tenant-b");
    foreign.setRoleName("Other");
    foreign.setAuthority("OTHER");
    em.persist(foreign);
    for (int i = 0; i < 243; i++) {
      TenantResource resource = resource();
      resource.setCode("resource-" + String.format("%03d", i));
      resource.setName("TenantResource " + String.format("%03d", i));
      resource.setType(ResourceType.ACTION);
      resource.setGrantable(true);
      resource.setEnabled(i != 240);
      resource.setPublicAccess(true);
      resource.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
      if (i == 241) {
        resource.setSourceAvailable(false);
      }
      if (i == 242) {
        resource.setScopeTypes(EnumSet.of(ResourceScopeType.PLATFORM));
      }
      em.persist(resource);
      resources.add(resource);
      if (i < 240 && i % 2 == 0) {
        grant(resource, "tenant-a");
      }
      grant(resource, "tenant-b");
    }
    grant(
        resources.get(0), "tenant-a"); // Duplicate legacy grants must not multiply list/count rows.
    resources.get(2).setParentId(resources.get(1).getId());
    resources.get(4).setParentId(resources.get(1).getId());
    em.flush();
    em.clear();
    context = new AuthorizationContext();
    context.setUserId("owner");
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setIsAdministrator(false);
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    roles = mock(RoleService.class);
    when(roles.requireManagementTenant()).thenReturn("tenant-a");
    repository = mock(RoleRepository.class);
    when(repository.lockForAuthorization("tenant-a", role.getId())).thenReturn(Optional.of(role));
    doAnswer(
            call -> {
              org.simplepoint.plugin.rbac.core.api.pojo.dto.RoleResourceGrantDto dto =
                  call.getArgument(0);
              for (String code : dto.getResourceCodes()) {
                RoleResourceGrant grant = new RoleResourceGrant();
                grant.setTenantId("tenant-a");
                grant.setRoleId(dto.getRoleId());
                grant.setResourceCode(code);
                em.persist(grant);
              }
              return List.of();
            })
        .when(roles)
        .authorize(any());
    doAnswer(
            call -> {
              em.createMutationQuery(
                      "delete RoleResourceGrant g where g.tenantId = 'tenant-a'"
                          + " and g.roleId = :role and g.resourceCode in :codes")
                  .setParameter("role", role.getId())
                  .setParameter("codes", call.getArgument(1))
                  .executeUpdate();
              return null;
            })
        .when(roles)
        .unauthorized(anyString(), anySet());
    authorizationVersions = mock(ResourceAuthorizationVersionService.class);
    service =
        new RoleResourceConfigurationServiceImpl(em, roles, repository, authorizationVersions);
    service.setTenantResourceCatalog(
        new org.simplepoint.plugin.rbac.tenant.service.impl.TenantResourceCatalogImpl(
            em,
            mock(org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository.class),
            mock(
                org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceProvisioningService
                    .class)));
    mvc =
        MockMvcBuilders.standaloneSetup(new RoleResourceConfigurationController(service))
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(
                    tools.jackson.databind.json.JsonMapper.builder()
                        .addModule(new FieldScopeJackson3Module())
                        .build()))
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
  }

  TenantResource resource() {
    var row = new TenantResource();
    row.setId(UUID.randomUUID().toString());
    row.setTenantId("tenant-a");
    row.setPlatformResourceId(row.getId());
    row.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
    row.setBaselineEntitled(true);
    row.setEnabled(true);
    row.setGrantable(true);
    row.setType(ResourceType.PAGE);
    row.setCreatedAt(Instant.now());
    row.setUpdatedAt(Instant.now());
    return row;
  }

  TenantResource findResource(String id) {
    return em.find(TenantResource.class, new TenantResourceKey("tenant-a", id));
  }

  TenantResourceEntitlement entitlement(TenantResource row) {
    final var balance = new TenantResourceEntitlement();
    balance.setTenantId(row.getTenantId());
    balance.setId(row.getId());
    balance.setUpdatedAt(Instant.now());
    balance.setStartsAt(Instant.now().minusSeconds(60));
    balance.setExpiresAt(Instant.now().plusSeconds(60));
    em.persist(balance);
    return balance;
  }

  RoleResourceGrant grant(TenantResource resource, String tenant) {
    final var grant = new RoleResourceGrant();
    grant.setTenantId(tenant);
    grant.setRoleId(role.getId());
    grant.setResourceCode(resource.getCode());
    em.persist(grant);
    return grant;
  }

  String endpoint() {
    return "/role-resource-configuration/roles/" + role.getId();
  }

  List<Row> list(String keyword, String state) {
    return service
        .list(role.getId(), keyword, state, null, null, false, false, PageRequest.of(0, 100))
        .getContent();
  }

  Change change(Row row, boolean granted) {
    return new Change(row.id(), row.code(), row.revision(), granted);
  }

  @AfterEach
  void close() {
    if (em != null) {
      if (em.getTransaction().isActive()) {
        em.getTransaction().rollback();
      }
      em.close();
    }
    if (factory != null) {
      factory.close();
    }
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void expiryBetweenPreviewAndSaveRejectsTheWholePatchWithoutRevivingOldGrants() {
    var paid = findResource(resources.get(1).getId());
    paid.setBaselineEntitled(false);
    final var balance = entitlement(paid);
    em.flush();
    final Row adding = list(paid.getCode(), "all").getFirst();
    final Row removing = list("resource-000", "all").getFirst();
    balance.setExpiresAt(Instant.now().minusSeconds(1));
    em.flush();
    assertTrue(list(paid.getCode(), "all").isEmpty());
    assertEquals(
        409,
        assertThrows(
                ResponseStatusException.class,
                () ->
                    service.save(
                        role.getId(),
                        new Patch(List.of(change(removing, false), change(adding, true)))))
            .getStatusCode()
            .value());
    verify(roles, never()).authorize(any());
    verify(roles, never()).unauthorized(anyString(), anySet());
    assertTrue(list("resource-000", "all").getFirst().granted());
  }

  @Test
  void missingDirectoryOrPlatformScopeNeverFallsBackToPlatformResources() {
    service.setTenantResourceCatalog(null);
    assertThrows(AccessDeniedException.class, () -> list("", "all"));
    service.setTenantResourceCatalog(
        new org.simplepoint.plugin.rbac.tenant.service.impl.TenantResourceCatalogImpl(
            em,
            mock(org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository.class),
            mock(
                org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceProvisioningService
                    .class)));
    context.replaceScopeType(AuthorizationScopeType.PLATFORM);
    assertThrows(AccessDeniedException.class, () -> list("", "all"));
  }

  @Test
  void lifecycleAndMissingAncestorsConstrainCountsChildrenAndSubtreeBeforePaging() {
    var parent = findResource(resources.get(1).getId());
    final var child = findResource(resources.get(2).getId());
    parent.setType(ResourceType.MODULE);
    parent.setGrantable(false);
    findResource(resources.get(4).getId()).setFormallyOffline(true);
    child.setManualDisabled(true);
    em.flush();
    assertTrue(list(parent.getCode(), "all").isEmpty());
    assertThrows(
        AccessDeniedException.class,
        () -> service.previewSubtree(role.getId(), parent.getId(), true));
    child.setManualDisabled(false);
    em.flush();
    var page =
        service.list(
            role.getId(), "", "all", null, parent.getId(), false, false, PageRequest.of(0, 1));
    assertEquals(1, page.getTotalElements());
    assertEquals(child.getCode(), page.getContent().getFirst().code());
    assertEquals(
        Set.of(parent.getCode(), child.getCode()),
        service.subtreeRows(role.getId(), parent.getId()).stream()
            .map(Row::code)
            .collect(java.util.stream.Collectors.toSet()));
    parent.setParentId("missing-parent");
    em.flush();
    assertTrue(service.contextRows(role.getId(), List.of(child.getId())).isEmpty());
  }

  @Test
  void dataAssetsAreNotAdvertisedBeforeTenantPageCoverageExists() {
    TenantResource page = resource();
    page.setCode("dna.data-assets.view");
    page.setName("Data assets");
    page.setType(ResourceType.PAGE);
    page.setGrantable(true);
    page.setEnabled(true);
    page.setPublicAccess(true);
    page.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
    em.persist(page);
    DataScope allowed = new DataScope();
    allowed.setTenantId("tenant-a");
    allowed.setName("Only my assets");
    allowed.setType(DataScopeType.SELF);
    em.persist(allowed);
    em.flush();
    em.clear();

    Row row =
        list("dna.data-assets", "all").stream()
            .filter(item -> item.code().equals(page.getCode()))
            .findFirst()
            .orElseThrow();
    assertFalse(row.dataPermission().supported());
    assertNull(row.dataPermission().policyId());
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.save(
                role.getId(),
                new Patch(
                    List.of(
                        new Change(
                            row.id(),
                            row.code(),
                            row.revision(),
                            true,
                            new PolicyUpdate(allowed.getId()),
                            null)))));
  }

  @Test
  void organizationDataRuleCanBeListedBoundAndClearedOnlyWithinTenant() {
    TenantResource page = resource();
    page.setCode("organizations.view");
    page.setName("Organizations");
    page.setPermissionMetadata(
        new org.simplepoint.core.permission.PermissionPageDescriptor(
                List.of(
                    org.simplepoint.core.permission.PermissionModelDescriptor.describe(
                        Organization.class)))
            .toJson());
    page.setType(ResourceType.PAGE);
    page.setGrantable(true);
    page.setEnabled(true);
    page.setPublicAccess(true);
    page.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
    em.persist(page);
    final RoleResourceGrant grant = grant(page, "tenant-a");
    DataScope allowed = new DataScope();
    allowed.setTenantId("tenant-a");
    allowed.setName("Own organization");
    allowed.setType(DataScopeType.DEPT);
    em.persist(allowed);
    DataScope foreignScope = new DataScope();
    foreignScope.setTenantId("tenant-b");
    foreignScope.setName("Foreign organization");
    foreignScope.setType(DataScopeType.ALL);
    em.persist(foreignScope);
    em.flush();
    em.clear();

    var options =
        service.policies(role.getId(), "organizations.view", "data", "", PageRequest.of(0, 10));
    assertEquals(1, options.getTotalElements());
    assertEquals(allowed.getId(), options.getContent().getFirst().id());
    Row row =
        list("organizations.view", "all").stream()
            .filter(item -> item.code().equals("organizations.view"))
            .findFirst()
            .orElseThrow();
    assertTrue(row.dataPermission().supported());
    assertThrows(
        AccessDeniedException.class,
        () ->
            service.save(
                role.getId(),
                new Patch(
                    List.of(
                        new Change(
                            row.id(),
                            row.code(),
                            row.revision(),
                            true,
                            new PolicyUpdate(foreignScope.getId()),
                            null)))));
    service.save(
        role.getId(),
        new Patch(
            List.of(
                new Change(
                    row.id(),
                    row.code(),
                    row.revision(),
                    true,
                    new PolicyUpdate(allowed.getId()),
                    null))));
    em.flush();
    em.clear();
    assertEquals(allowed.getId(), em.find(RoleResourceGrant.class, grant.getId()).getDataScopeId());
    Row assigned =
        list("organizations.view", "all").stream()
            .filter(item -> item.code().equals("organizations.view"))
            .findFirst()
            .orElseThrow();
    service.save(
        role.getId(),
        new Patch(
            List.of(
                new Change(
                    assigned.id(),
                    assigned.code(),
                    assigned.revision(),
                    true,
                    new PolicyUpdate(null),
                    null))));
    em.flush();
    em.clear();
    assertNull(em.find(RoleResourceGrant.class, grant.getId()).getDataScopeId());
  }

  @Test
  void fieldPolicyOptionsAndAssignmentStayWithinThePageModel() {
    TenantResource page = resource();
    page.setCode("users.view");
    page.setName("Members");
    page.setPermissionMetadata(
        new org.simplepoint.core.permission.PermissionPageDescriptor(
                List.of(
                    org.simplepoint.core.permission.PermissionModelDescriptor.describe(User.class)))
            .toJson());
    page.setType(ResourceType.PAGE);
    page.setGrantable(true);
    page.setEnabled(true);
    page.setPublicAccess(true);
    page.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
    em.persist(page);
    final RoleResourceGrant grant = grant(page, "tenant-a");
    FieldScope allowed = new FieldScope();
    allowed.setTenantId("tenant-a");
    allowed.setName("Hide email");
    em.persist(allowed);
    FieldScopeEntry entry = new FieldScopeEntry();
    entry.setTenantId("tenant-a");
    entry.setFieldScopeId(allowed.getId());
    entry.setResource(User.class.getName());
    entry.setField("email");
    entry.setAccess(FieldAccessType.HIDDEN);
    em.persist(entry);
    FieldScope unrelated = new FieldScope();
    unrelated.setTenantId("tenant-a");
    unrelated.setName("Other model");
    em.persist(unrelated);
    FieldScopeEntry unrelatedEntry = new FieldScopeEntry();
    unrelatedEntry.setTenantId("tenant-a");
    unrelatedEntry.setFieldScopeId(unrelated.getId());
    unrelatedEntry.setResource(Organization.class.getName());
    unrelatedEntry.setField("name");
    unrelatedEntry.setAccess(FieldAccessType.HIDDEN);
    em.persist(unrelatedEntry);
    em.flush();
    em.clear();

    var options = service.policies(role.getId(), "users.view", "field", "", PageRequest.of(0, 10));
    assertEquals(1, options.getTotalElements());
    assertEquals(allowed.getId(), options.getContent().getFirst().id());
    assertEquals(
        allowed.getId(),
        service
            .policies(
                role.getId(), "users.view", "field", "", PageRequest.of(0, 1), allowed.getId())
            .getContent()
            .getFirst()
            .id());
    assertEquals(
        0,
        service
            .policies(
                role.getId(), "users.view", "field", "", PageRequest.of(0, 1), unrelated.getId())
            .getTotalElements());
    Row row =
        list("users.view", "all").stream()
            .filter(item -> item.code().equals("users.view"))
            .findFirst()
            .orElseThrow();
    assertTrue(row.fieldPermission().supported());
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.save(
                role.getId(),
                new Patch(
                    List.of(
                        new Change(
                            row.id(),
                            row.code(),
                            row.revision(),
                            true,
                            null,
                            new PolicyUpdate(unrelated.getId()))))));
    service.save(
        role.getId(),
        new Patch(
            List.of(
                new Change(
                    row.id(),
                    row.code(),
                    row.revision(),
                    true,
                    null,
                    new PolicyUpdate(allowed.getId())))));
    em.flush();
    em.clear();
    assertEquals(
        allowed.getId(), em.find(RoleResourceGrant.class, grant.getId()).getFieldScopeId());
  }

  @Test
  void policyDirectoryPaginatesBeyondFiveHundredAndHydratesOnlyAnEligibleExactId()
      throws Exception {
    TenantResource page = resource();
    page.setCode("organizations.view");
    page.setName("Organizations");
    page.setPermissionMetadata(
        new org.simplepoint.core.permission.PermissionPageDescriptor(
                List.of(
                    org.simplepoint.core.permission.PermissionModelDescriptor.describe(
                        Organization.class)))
            .toJson());
    page.setType(ResourceType.PAGE);
    page.setGrantable(true);
    page.setEnabled(true);
    page.setPublicAccess(true);
    page.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
    em.persist(page);
    DataScope last = null;
    for (int i = 0; i < 521; i++) {
      DataScope item = new DataScope();
      item.setTenantId("tenant-a");
      item.setName("Policy" + String.format("%03d", i));
      item.setType(DataScopeType.DEPT);
      em.persist(item);
      last = item;
    }
    DataScope foreignRule = new DataScope();
    foreignRule.setTenantId("tenant-b");
    foreignRule.setName("Foreign");
    foreignRule.setType(DataScopeType.ALL);
    em.persist(foreignRule);
    DataScope deleted = new DataScope();
    deleted.setTenantId("tenant-a");
    deleted.setName("Deleted");
    deleted.setType(DataScopeType.ALL);
    deleted.setDeletedAt(Instant.now());
    em.persist(deleted);
    em.flush();
    em.clear();
    var beyond =
        service.policies(role.getId(), "organizations.view", "data", "", PageRequest.of(26, 20));
    assertEquals(521, beyond.getTotalElements());
    assertEquals(1, beyond.getContent().size());
    assertEquals(last.getId(), beyond.getContent().getFirst().id());
    factory.getStatistics().clear();
    var selected =
        service.policies(
            role.getId(), "organizations.view", "data", "", PageRequest.of(0, 1), last.getId());
    assertEquals(1, selected.getTotalElements());
    assertEquals("Policy520", selected.getContent().getFirst().name());
    assertTrue(
        factory.getStatistics().getEntityLoadCount() <= 2,
        "Selected hydration must not materialize the policy catalog");
    for (String id : List.of(foreignRule.getId(), deleted.getId(), "' OR '1'='1")) {
      assertEquals(
          0,
          service
              .policies(role.getId(), "organizations.view", "data", "", PageRequest.of(0, 1), id)
              .getTotalElements());
    }
    mvc.perform(
            get("/role-resource-configuration/roles/" + role.getId() + "/policy-options")
                .param("resourceCode", "organizations.view")
                .param("kind", "data")
                .param("selectedId", last.getId())
                .param("page", "0")
                .param("size", "1")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Policy520"));
    mvc.perform(
            get("/role-resource-configuration/roles/" + role.getId() + "/policy-options")
                .param("resourceCode", "organizations.view")
                .param("kind", "data")
                .param("selectedId", "x".repeat(65))
                .param("page", "0")
                .param("size", "1")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest());
  }

  @Test
  void tenantCatalogIsTheOnlySourceForRoleEditorAndSave() {
    TenantResource copy = findResource(resources.get(1).getId());
    copy.setName("Workspace page");
    copy.setType(ResourceType.PAGE);
    TenantResource foreignCopy = resource();
    foreignCopy.setTenantId("tenant-b");
    foreignCopy.setCode("foreign.only");
    foreignCopy.setName("Foreign page");
    em.persist(foreignCopy);
    em.flush();
    var page =
        service.list(
            role.getId(), "Workspace page", "all", null, null, false, false, PageRequest.of(0, 20));
    assertEquals(1, page.getTotalElements());
    assertEquals(copy.getId(), page.getContent().getFirst().id());
    assertEquals(
        List.of(copy.getId()),
        service.contextRows(role.getId(), List.of(copy.getId(), foreignCopy.getId())).stream()
            .map(Row::id)
            .toList());
    Row row = page.getContent().getFirst();
    service.save(role.getId(), new Patch(List.of(change(row, true))));
    verify(roles).authorize(argThat(dto -> dto.getResourceCodes().contains(copy.getCode())));
  }

  @Test
  void tenantCatalogIncludesAndSavesManagedPageResources() {
    TenantResource parent = resource();
    parent.setCode("app.platform.view");
    parent.setName("Application platform");
    parent.setType(ResourceType.MODULE);
    parent.setGrantable(false);
    parent.setEnabled(true);
    parent.setPublicAccess(true);
    parent.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
    em.persist(parent);
    TenantResource page =
        managedResource(
            "tenant-a",
            "managed-page",
            "app.runtime.page.one",
            "Orders",
            ResourceType.PAGE,
            parent.getId());
    TenantResource create =
        managedResource(
            "tenant-a",
            "managed-create",
            "app.runtime.page.one.records.create",
            "Create",
            ResourceType.ACTION,
            page.getId());
    TenantResource foreign =
        managedResource(
            "tenant-b",
            "managed-foreign",
            "app.runtime.page.foreign",
            "Foreign",
            ResourceType.PAGE,
            parent.getId());
    em.persist(page);
    em.persist(create);
    em.persist(foreign);
    em.flush();
    em.clear();

    var pageRows =
        service.list(
            role.getId(), "", "all", null, parent.getId(), false, true, PageRequest.of(0, 100));
    assertEquals(1, pageRows.getTotalElements());
    Row pageRow = pageRows.getContent().getFirst();
    assertEquals("app.runtime.page.one", pageRow.code());
    assertTrue(pageRow.grantable());
    assertFalse(pageRow.granted());

    List<Row> subtree = service.subtreeRows(role.getId(), page.getId());
    assertEquals(
        Set.of("app.runtime.page.one", "app.runtime.page.one.records.create"),
        subtree.stream().map(Row::code).collect(java.util.stream.Collectors.toSet()));
    service.save(role.getId(), new Patch(List.of(change(pageRow, true))));
    verify(roles)
        .authorize(argThat(dto -> dto.getResourceCodes().equals(Set.of("app.runtime.page.one"))));
  }

  TenantResource managedResource(
      String tenant, String id, String code, String name, ResourceType type, String parentId) {
    TenantResource resource = resource();
    resource.setTenantId(tenant);
    resource.setId(id);
    resource.setPlatformResourceId(null);
    resource.setManagedOwner("app");
    resource.setManagedKey("page:" + id);
    resource.setCode(code);
    resource.setName(name);
    resource.setType(type);
    resource.setParentId(parentId);
    resource.setGrantable(true);
    resource.setEnabled(true);
    resource.setUpdatedAt(Instant.parse("2026-10-13T00:00:00Z"));
    return resource;
  }

  @Test
  void previewContextIsBoundedAndPreservesVisibilityAndRoleIsolation() throws Exception {
    final TenantResource child = resources.get(2);
    final TenantResource parent = resources.get(1);
    var contextRows =
        service.contextRows(
            role.getId(), List.of(child.getId(), child.getId(), resources.get(242).getId()));
    assertEquals(
        Set.of(child.getId(), parent.getId()),
        contextRows.stream().map(Row::id).collect(java.util.stream.Collectors.toSet()));
    mvc.perform(
            post(endpoint() + "/resource-context")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[\"" + child.getId() + "\"]")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
    assertThrows(
        AccessDeniedException.class,
        () -> service.contextRows(foreign.getId(), List.of(child.getId())));
    assertThrows(
        ResponseStatusException.class,
        () -> service.contextRows(role.getId(), Collections.nCopies(201, child.getId())));
    assertThrows(
        ResponseStatusException.class,
        () -> service.contextRows(role.getId(), Arrays.asList((String) null)));
    // Invalid parent cycles cannot be exposed as authorized directory context.
    findResource(parent.getId()).setParentId(child.getId());
    em.flush();
    assertTrue(service.contextRows(role.getId(), List.of(child.getId())).isEmpty());
    context.setFieldPermissions(Map.of(TenantResource.class.getName() + "#parentId", "HIDDEN"));
    assertThrows(
        AccessDeniedException.class,
        () -> service.contextRows(role.getId(), List.of(child.getId())));
  }

  @Test
  void paginatesBeforeLoadingAndProjectsReservedSlots() throws Exception {
    factory.getStatistics().clear();
    mvc.perform(
            get(endpoint() + "/resources")
                .param("page", "11")
                .param("size", "20")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(20))
        .andExpect(jsonPath("$.totalElements").value(240))
        .andExpect(jsonPath("$.content[0].code").value("resource-220"))
        .andExpect(jsonPath("$.content[0].granted").value(true))
        .andExpect(jsonPath("$.content[1].granted").value(false))
        .andExpect(jsonPath("$.content[0].dataPermission.supported").value(false))
        .andExpect(jsonPath("$.content[0].fieldPermission.supported").value(false));
    assertTrue(
        factory.getStatistics().getPrepareStatementCount() <= 10,
        "includes tenant readiness and bounded scalar eligibility reads, never whole-directory"
            + " entities");
    assertTrue(
        factory.getStatistics().getEntityLoadCount() <= 31,
        "only page resources and this page's role grants may be loaded");
  }

  @Test
  void directoryCheckboxCanStageEveryUnloadedDescendantAndSaveAtomically() throws Exception {
    String rootId = resources.getFirst().getId();
    for (int i = 1; i < 240; i++) {
      findResource(resources.get(i).getId()).setParentId(rootId);
    }
    em.flush();
    var subtree = service.subtreeRows(role.getId(), rootId);
    assertEquals(240, subtree.size());
    assertTrue(subtree.stream().anyMatch(row -> row.code().equals("resource-239")));
    mvc.perform(
            get(endpoint() + "/resource-subtree/resources")
                .param("resourceId", rootId)
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(240));
    var changes =
        subtree.stream()
            .filter(row -> !row.granted())
            .map(row -> new Change(row.id(), row.code(), row.revision(), true))
            .toList();
    assertEquals(120, changes.size());
    service.save(role.getId(), new Patch(changes));
    em.flush();
    assertEquals(
        240,
        service
            .list(role.getId(), "", "granted", null, null, false, false, PageRequest.of(0, 20))
            .getTotalElements());
    var revoke =
        service.subtreeRows(role.getId(), rootId).stream()
            .map(row -> new Change(row.id(), row.code(), row.revision(), false))
            .toList();
    assertEquals(240, revoke.size());
    service.save(role.getId(), new Patch(revoke));
    em.flush();
    assertEquals(
        0,
        service
            .list(role.getId(), "", "granted", null, null, false, false, PageRequest.of(0, 20))
            .getTotalElements());
  }

  @Test
  void filtersInSqlAndKeepsTenantAndRoleBoundary() {
    var assigned =
        service.list(role.getId(), "", "granted", null, null, false, false, PageRequest.of(5, 20));
    assertEquals(120, assigned.getTotalElements());
    assertEquals("resource-200", assigned.getContent().getFirst().code());
    assertEquals(
        120,
        service
            .list(role.getId(), "", "ungranted", null, null, false, false, PageRequest.of(0, 20))
            .getTotalElements());
    assertEquals(
        2,
        service
            .list(
                role.getId(),
                "",
                "all",
                null,
                resources.get(1).getId(),
                false,
                false,
                PageRequest.of(0, 20))
            .getTotalElements());
    assertTrue(list("%", "all").isEmpty(), "wildcards are literal search text");
    assertThrows(
        AccessDeniedException.class,
        () ->
            service.list(
                foreign.getId(), "", "all", null, null, false, false, PageRequest.of(0, 20)));
    assertThrows(
        AccessDeniedException.class, () -> service.save(foreign.getId(), new Patch(List.of())));
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.list(
                role.getId(), "", "all", null, null, false, false, PageRequest.of(0, 501)));
  }

  @Test
  void loadsOnlyOneTreeLevelAndProjectsResourceIcon() throws Exception {
    TenantResource parent = findResource(resources.get(1).getId());
    parent.setIcon("AppstoreOutlined");
    em.flush();
    mvc.perform(
            get(endpoint() + "/resources")
                .param("rootsOnly", "true")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(238));
    mvc.perform(
            get(endpoint() + "/resources")
                .param("parentId", parent.getId())
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
    Row row =
        service
            .list(
                role.getId(),
                parent.getCode(),
                "all",
                null,
                null,
                false,
                false,
                PageRequest.of(0, 20))
            .getContent()
            .getFirst();
    assertEquals("AppstoreOutlined", row.icon());
    assertTrue(row.hasChildren());
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.list(
                role.getId(), "", "all", null, parent.getId(), true, false, PageRequest.of(0, 20)));
  }

  @Test
  void navigationTreeExcludesActionsAndApisWithoutHidingTheirGrants() throws Exception {
    TenantResource parent = findResource(resources.get(1).getId());
    TenantResource api = findResource(resources.get(4).getId());
    parent.setType(ResourceType.PAGE);
    api.setType(ResourceType.API);
    em.flush();
    Row navigationParent =
        service
            .list(
                role.getId(),
                parent.getCode(),
                "all",
                null,
                null,
                false,
                true,
                PageRequest.of(0, 20))
            .getContent()
            .getFirst();
    assertTrue(navigationParent.hasChildren(), "operations remain available in the detail panel");
    assertFalse(
        navigationParent.hasNavigationChildren(), "operation-only children do not expand the tree");
    mvc.perform(
            get(endpoint() + "/resources")
                .param("parentId", parent.getId())
                .param("navigationOnly", "true")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    assertEquals(
        2,
        service
            .list(
                role.getId(), "", "all", null, parent.getId(), false, false, PageRequest.of(0, 20))
            .getTotalElements());
    api.setType(ResourceType.PAGE);
    em.flush();
    assertTrue(
        service
            .list(
                role.getId(),
                parent.getCode(),
                "all",
                null,
                null,
                false,
                true,
                PageRequest.of(0, 20))
            .getContent()
            .getFirst()
            .hasNavigationChildren());
    mvc.perform(
            get(endpoint() + "/resources")
                .param("parentId", parent.getId())
                .param("navigationOnly", "true")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].code").value(api.getCode()));
  }

  @Test
  void tenantEntitlementsAndActualTenantScopeAreFilteredBeforeCounting() {
    TenantResource row = findResource(resources.get(1).getId());
    row.setBaselineEntitled(false);
    em.flush();
    assertTrue(list(row.getCode(), "all").isEmpty());
    final var balance = entitlement(row);
    em.flush();
    assertEquals(1, list(row.getCode(), "all").size());
    row.setScopeTypes(EnumSet.of(ResourceScopeType.PERSONAL));
    em.flush();
    assertTrue(list(row.getCode(), "all").isEmpty());
    em.find(Tenant.class, "tenant-a").setTenantType(TenantType.PERSONAL);
    context.replaceScopeType(AuthorizationScopeType.PERSONAL);
    em.flush();
    assertEquals(
        1,
        service
            .list(role.getId(), "", "all", null, null, false, false, PageRequest.of(0, 20))
            .getTotalElements());
    balance.setExpiresAt(Instant.now().minusSeconds(1));
    em.flush();
    assertTrue(list(row.getCode(), "all").isEmpty());
  }

  @Test
  void packagePagesKeepUnpackagedDirectoriesNavigableWithoutExpandingGrants() throws Exception {
    TenantResource root = catalogResource("workspace", ResourceType.MODULE, null, false);
    TenantResource group = catalogResource("workspace.security", ResourceType.GROUP, root, false);
    TenantResource page =
        catalogResource("workspace.security.roles", ResourceType.PAGE, group, true);
    final TenantResource unlicensed =
        catalogResource("workspace.security.other", ResourceType.PAGE, group, true);
    final TenantResource empty =
        catalogResource("workspace.empty", ResourceType.GROUP, root, false);
    final var balance = entitlement(page);
    em.flush();

    mvc.perform(
            get(endpoint() + "/resources")
                .param("rootsOnly", "true")
                .param("navigationOnly", "true")
                .param("size", "1")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].code").value(root.getCode()))
        .andExpect(jsonPath("$.content[0].grantable").value(false))
        .andExpect(jsonPath("$.content[0].hasNavigationChildren").value(true));
    assertEquals(
        List.of(group.getCode()),
        service
            .list(role.getId(), "", "all", null, root.getId(), false, true, PageRequest.of(0, 1))
            .map(Row::code)
            .getContent());
    assertEquals(
        List.of(page.getCode()),
        service
            .list(role.getId(), "", "all", null, group.getId(), false, true, PageRequest.of(0, 1))
            .map(Row::code)
            .getContent());
    assertTrue(list(empty.getCode(), "all").isEmpty());
    assertTrue(list(unlicensed.getCode(), "all").isEmpty());

    // Both directory selection paths must authorize only the licensed page.
    var subtree = service.subtreeRows(role.getId(), root.getId());
    assertEquals(
        Set.of(root.getCode(), group.getCode(), page.getCode()),
        subtree.stream().map(Row::code).collect(java.util.stream.Collectors.toSet()));
    var changes = subtree.stream().filter(Row::grantable).map(row -> change(row, true)).toList();
    service.save(role.getId(), new Patch(changes));
    em.flush();
    var revoke = service.previewSubtree(role.getId(), root.getId(), false);
    assertEquals(1, revoke.total());
    assertEquals(1, revoke.changed());
    service.saveSubtree(role.getId(), new BulkCommand(root.getId(), false, revoke.revision()));
    var add = service.previewSubtree(role.getId(), root.getId(), true);
    service.saveSubtree(role.getId(), new BulkCommand(root.getId(), true, add.revision()));
    em.flush();
    assertEquals(
        List.of(page.getCode()),
        em.createQuery(
                "select g.resourceCode from RoleResourceGrant g where g.tenantId = 'tenant-a' and"
                    + " g.roleId = :role and g.resourceCode like 'workspace%' and g.deletedAt is"
                    + " null",
                String.class)
            .setParameter("role", role.getId())
            .getResultList());
    Row directory =
        list(root.getCode(), "all").stream()
            .filter(row -> row.id().equals(root.getId()))
            .findFirst()
            .orElseThrow();
    assertThrows(
        ResponseStatusException.class,
        () -> service.save(role.getId(), new Patch(List.of(change(directory, true)))));

    context.replaceScopeType(AuthorizationScopeType.PERSONAL);
    em.find(Tenant.class, "tenant-a").setTenantType(TenantType.PERSONAL);
    em.flush();
    assertEquals(3, list("workspace", "all").size());
    // Neither inactive nor out-of-scope descendants can expose a directory.
    page.setEnabled(false);
    em.flush();
    assertTrue(list("workspace", "all").isEmpty());
    page.setEnabled(true);
    page.setSourceAvailable(false);
    em.flush();
    assertTrue(list("workspace", "all").isEmpty());
    page.setSourceAvailable(true);
    page.setScopeTypes(EnumSet.of(ResourceScopeType.PLATFORM));
    em.flush();
    assertTrue(list("workspace", "all").isEmpty());
    page.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT, ResourceScopeType.PERSONAL));
    root.setScopeTypes(EnumSet.of(ResourceScopeType.PLATFORM));
    em.flush();
    assertFalse(list("workspace", "all").stream().anyMatch(row -> row.id().equals(root.getId())));
    root.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT, ResourceScopeType.PERSONAL));
    // A grantable parent still requires its own package entitlement.
    root.setGrantable(true);
    em.flush();
    assertFalse(list("workspace", "all").stream().anyMatch(row -> row.id().equals(root.getId())));
    root.setGrantable(false);
    balance.setExpiresAt(Instant.now().minusSeconds(1));
    em.flush();
    assertTrue(list("workspace", "all").isEmpty());
    balance.setExpiresAt(Instant.now().plusSeconds(60));
    page.setManualDisabled(true);
    em.flush();
    assertTrue(list("workspace", "all").isEmpty());
  }

  private TenantResource catalogResource(
      String code, ResourceType type, TenantResource parent, boolean grantable) {
    TenantResource resource = resource();
    resource.setCode(code);
    resource.setName(code);
    resource.setType(type);
    resource.setGrantable(grantable);
    resource.setEnabled(true);
    resource.setPublicAccess(false);
    resource.setBaselineEntitled(false);
    resource.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT, ResourceScopeType.PERSONAL));
    resource.setParentId(parent == null ? null : parent.getId());
    em.persist(resource);
    return resource;
  }

  @Test
  void savesOnlyExplicitChangesAndRejectsConflictBeforeAnyWrite() {
    Row remove = list("resource-000", "all").getFirst();
    Row add = list("resource-001", "all").getFirst();
    grant(resources.get(1), "tenant-a");
    em.flush();
    ResponseStatusException failure =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.save(
                    role.getId(), new Patch(List.of(change(remove, false), change(add, true)))));
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
    legacy.setDataScopeId("legacy-data");
    legacy.setFieldScopeId("legacy-fields");
    em.flush();
    Row row = list("resource-000", "all").getFirst();
    ResponseStatusException failure =
        assertThrows(
            ResponseStatusException.class,
            () -> service.save(role.getId(), new Patch(List.of(change(row, false)))));
    assertTrue(failure.getReason().contains("迁移旧策略"));
    verify(roles, never()).unauthorized(anyString(), anySet());
    RoleScopeBinding binding = new RoleScopeBinding();
    binding.setTenantId("tenant-a");
    binding.setRoleId(role.getId());
    binding.setDataScopeId("legacy-data");
    binding.setFieldScopeId("legacy-fields");
    em.persist(binding);
    em.flush();
    assertEquals(
        1L,
        em.createQuery(
                "select count(b) from RoleScopeBinding b where b.tenantId = :tenant and b.roleId ="
                    + " :role and b.deletedAt is null",
                Long.class)
            .setParameter("tenant", "tenant-a")
            .setParameter("role", role.getId())
            .getSingleResult());
    service.save(role.getId(), new Patch(List.of(change(row, false))));
    em.flush();
    em.clear();
    assertEquals("legacy-data", em.find(RoleScopeBinding.class, binding.getId()).getDataScopeId());
    assertFalse(list("resource-000", "all").getFirst().granted());
  }

  @Test
  void omittedGrantDecisionAndDuplicateOrOversizedChangesCannotRevokeAnything() {
    Row row = list("resource-000", "all").getFirst();
    for (Patch patch :
        List.of(
            new Patch(List.of(new Change(row.id(), row.code(), row.revision(), null))),
            new Patch(List.of(change(row, false), change(row, false))),
            new Patch(Collections.nCopies(201, change(row, false))))) {
      assertEquals(
          400,
          assertThrows(ResponseStatusException.class, () -> service.save(role.getId(), patch))
              .getStatusCode()
              .value());
    }
    verify(roles, never()).unauthorized(anyString(), anySet());
    verify(roles, never()).authorize(any());
  }

  @Test
  void managementRevocationStopsBothReadsAndSaves() {
    when(roles.requireManagementTenant()).thenThrow(new AccessDeniedException("owner required"));
    assertThrows(AccessDeniedException.class, () -> list("", "all"));
    assertThrows(
        AccessDeniedException.class, () -> service.save(role.getId(), new Patch(List.of())));
    verify(repository, never()).lockForAuthorization(anyString(), anyString());
  }

  @Test
  void hiddenGrantStateCannotBeInferredThroughCountsOrWrites() {
    context.setFieldPermissions(
        Map.of(RoleResourceGrant.class.getName() + "#resourceCode", "HIDDEN"));
    factory.getStatistics().clear();
    assertThrows(AccessDeniedException.class, () -> list("", "granted"));
    assertThrows(
        AccessDeniedException.class, () -> service.save(role.getId(), new Patch(List.of())));
    assertEquals(0, factory.getStatistics().getPrepareStatementCount());
  }

  @Test
  void hiddenResourceFieldsAreExcludedFromHttpAndSearch() throws Exception {
    context.setFieldPermissions(Map.of(TenantResource.class.getName() + "#name", "HIDDEN"));
    assertTrue(list("TenantResource 1", "all").isEmpty());
    mvc.perform(
            get(endpoint() + "/resources")
                .param("size", "1")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].label").doesNotExist());
  }

  @Test
  void httpPatchUsesRevisionAndDoesNotAcceptFullReplacement() throws Exception {
    Row row = list("resource-001", "all").getFirst();
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    mvc.perform(
            put(endpoint() + "/resource-changes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new Patch(List.of(change(row, true)))))
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isNoContent());
    mvc.perform(
            put(endpoint() + "/resource-changes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new Patch(List.of(change(row, true)))))
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isConflict());
    mvc.perform(
            put(endpoint() + "/resource-changes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resourceCodes\":[]}")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isBadRequest());
  }

  @Test
  void subtreeChangeIncludesUnloadedDescendantsAndRejectsStalePreview() throws Exception {
    TenantResource root = findResource(resources.get(0).getId());
    root.setType(ResourceType.GROUP);
    root.setGrantable(false);
    for (int i = 1; i < 240; i++) {
      TenantResource child = findResource(resources.get(i).getId());
      if (i != 2 && i != 4) {
        child.setParentId(root.getId());
      }
    }
    em.flush();
    BulkPreview preview = service.previewSubtree(role.getId(), root.getId(), true);
    assertEquals(239, preview.total());
    assertEquals(120, preview.changed());
    mvc.perform(
            get(endpoint() + "/resource-subtree")
                .param("resourceId", root.getId())
                .param("granted", "true")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(239))
        .andExpect(jsonPath("$.changed").value(120));
    grant(resources.get(1), "tenant-a");
    em.flush();
    assertEquals(
        409,
        assertThrows(
                ResponseStatusException.class,
                () ->
                    service.saveSubtree(
                        role.getId(), new BulkCommand(root.getId(), true, preview.revision())))
            .getStatusCode()
            .value());
    verify(roles, never()).authorize(any());
    BulkPreview fresh = service.previewSubtree(role.getId(), root.getId(), true);
    assertEquals(119, fresh.changed());
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    mvc.perform(
            put(endpoint() + "/resource-subtree")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new BulkCommand(root.getId(), true, fresh.revision())))
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isNoContent());
    em.flush();
    assertEquals(0, service.previewSubtree(role.getId(), root.getId(), true).changed());
    assertTrue(list("resource-239", "all").getFirst().granted());
    assertFalse(
        list("resource-242", "all").stream().anyMatch(Row::granted),
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
    TenantResource parent = findResource(resources.get(1).getId());
    TenantResource child = findResource(resources.get(2).getId());
    RoleResourceGrant legacy = grant(child, "tenant-a");
    legacy.setDataScopeId("legacy-data");
    em.flush();
    BulkPreview preview = service.previewSubtree(role.getId(), parent.getId(), false);
    assertEquals(3, preview.total());
    assertEquals(2, preview.changed());
    ResponseStatusException failure =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.saveSubtree(
                    role.getId(), new BulkCommand(parent.getId(), false, preview.revision())));
    assertEquals(409, failure.getStatusCode().value());
    verify(roles, never()).unauthorized(anyString(), anySet());
  }
}
