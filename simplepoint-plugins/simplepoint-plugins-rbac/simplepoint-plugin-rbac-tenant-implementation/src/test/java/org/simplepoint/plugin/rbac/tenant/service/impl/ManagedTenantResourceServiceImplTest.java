package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.error.ManagedTenantResourceErrorCode;
import org.simplepoint.plugin.rbac.tenant.api.error.ManagedTenantResourceException;
import org.simplepoint.plugin.rbac.tenant.api.model.ManagedTenantResourceCommand;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;

class ManagedTenantResourceServiceImplTest {

  private SessionFactory factory;
  private Session session;
  private ResourceRepository platform;
  private ResourceAuthorizationVersionService versions;
  private ManagedTenantResourceCallerGuard callers;
  private ManagedTenantResourceServiceImpl service;
  private Tenant tenant;
  private Resource parent;
  private Resource host;

  @BeforeEach
  void setUp() {
    factory = new MetadataSources(new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", "jdbc:h2:mem:managed-resource-" + UUID.randomUUID())
        .applySetting("hibernate.hbm2ddl.auto", "create-drop")
        .build())
        .addAnnotatedClass(Tenant.class)
        .addAnnotatedClass(TenantResource.class)
        .addAnnotatedClass(org.simplepoint.security.entity.RoleResourceGrant.class)
        .buildMetadata()
        .buildSessionFactory();
    session = factory.openSession();
    session.beginTransaction();
    tenant = new Tenant();
    tenant.setName("Workspace");
    tenant.setOwnerId("owner");
    tenant.setTenantType(TenantType.ORGANIZATION);
    tenant.setAuthorizationVersion(0L);
    session.persist(tenant);
    session.flush();

    parent = platform("platform-parent", "app.platform.view", ResourceType.MODULE, null);
    host = platform("runtime-host", "app.runtime.host", ResourceType.PAGE, "app/FormRuntime");
    platform = mock(ResourceRepository.class);
    when(platform.findAllByCodes(anyCollection())).thenAnswer(invocation -> {
      List<String> codes = List.copyOf(invocation.getArgument(0));
      return List.of(parent, host).stream().filter(row -> codes.contains(row.getCode())).toList();
    });
    versions = mock(ResourceAuthorizationVersionService.class);
    callers = mock(ManagedTenantResourceCallerGuard.class);
    when(callers.requireOwner()).thenReturn("app");
    service = new ManagedTenantResourceServiceImpl(session, platform, versions, callers);
  }

  @AfterEach
  void tearDown() {
    if (session.getTransaction().isActive()) session.getTransaction().rollback();
    session.close();
    factory.close();
  }

  @Test
  void preparesDisabledStableTreeThenActivatesAndDeactivatesItAtomically() {
    var page = service.prepare(page("Application page"));
    var action = service.prepare(action());

    assertThat(page.enabled()).isFalse();
    assertThat(page.component()).isEqualTo("app/FormRuntime");
    assertThat(action.parentId()).isEqualTo(page.id());

    var active = service.activate(tenant.getId(), "page:page-1");
    assertThat(active.enabled()).isTrue();
    assertThat(service.get(tenant.getId(), "page:page-1:create").enabled()).isTrue();

    var replay = service.prepare(page("Renamed page"));
    assertThat(replay.id()).isEqualTo(page.id());
    assertThat(replay.name()).isEqualTo("Renamed page");
    assertThat(replay.enabled()).isTrue();

    service.deactivate(tenant.getId(), "page:page-1");
    assertThat(service.get(tenant.getId(), "page:page-1").enabled()).isFalse();
    assertThat(service.get(tenant.getId(), "page:page-1:create").enabled()).isFalse();
    verify(versions, atLeastOnce()).refreshTenant(tenant.getId());
  }

  @Test
  void rejectsStableIdentityRebindingAndAnotherOwnerCannotResolveIt() {
    service.prepare(page("Application page"));

    assertThatThrownBy(() -> service.prepare(new ManagedTenantResourceCommand(
        tenant.getId(),
        "page:page-1",
        "app.runtime.page.another",
        "Application page",
        ResourceType.PAGE,
        null,
        parent.getCode(),
        host.getCode(),
        "/app/runtime/application-1/page-1",
        null,
        10,
        null
    ))).isInstanceOfSatisfying(ManagedTenantResourceException.class, error ->
        assertThat(error.errorCode()).isEqualTo(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT));

    when(callers.requireOwner()).thenReturn("workflow");
    assertThatThrownBy(() -> service.get(tenant.getId(), "page:page-1"))
        .isInstanceOfSatisfying(ManagedTenantResourceException.class, error ->
            assertThat(error.errorCode()).isEqualTo(ManagedTenantResourceErrorCode.RESOURCE_NOT_FOUND));
  }

  @Test
  void rejectsCallerCodesOutsideItsNamespaceAndUntrustedHosts() {
    assertThatThrownBy(() -> service.prepare(new ManagedTenantResourceCommand(
        tenant.getId(), "page:page-1", "workflow.page.one", "Page", ResourceType.PAGE,
        null, parent.getCode(), host.getCode(), "/app/runtime/application-1/page-1",
        null, 10, null
    ))).isInstanceOfSatisfying(ManagedTenantResourceException.class, error ->
        assertThat(error.errorCode()).isEqualTo(ManagedTenantResourceErrorCode.INVALID_REQUEST));

    assertThatThrownBy(() -> service.prepare(new ManagedTenantResourceCommand(
        tenant.getId(), "page:page-1", "app.runtime.page.page-1", "Page", ResourceType.PAGE,
        null, parent.getCode(), "app.missing.host", "/app/runtime/application-1/page-1",
        null, 10, null
    ))).isInstanceOfSatisfying(ManagedTenantResourceException.class, error ->
        assertThat(error.errorCode()).isEqualTo(ManagedTenantResourceErrorCode.HOST_NOT_FOUND));
  }

  @Test
  void formalOfflineRevokesGrantsAndFencesOldEventsAcrossReonline() {
    var page = service.prepare(page("Application page"));
    var action = service.prepare(action());
    service.activateGeneration(tenant.getId(), "page:page-1", 1);
    var grant = new org.simplepoint.security.entity.RoleResourceGrant();
    grant.setTenantId(tenant.getId()); grant.setRoleId("role"); grant.setResourceCode(action.resourceCode());
    session.persist(grant); session.flush();
    var event = new org.simplepoint.events.api.EventMessage(UUID.randomUUID(), "resource.offline.requested", 1,
        "app", tenant.getId(), page.id(), 1, "operation", "trace", java.time.Instant.now(),
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("managedOwner", "app")
            .put("managedKey", "page:page-1").put("resourceCode", page.resourceCode()));
    service.offlineFromEvent(event);
    assertThat(service.get(tenant.getId(), "page:page-1").enabled()).isFalse();
    session.refresh(grant);
    assertThat(grant.getDeletedAt()).isNotNull();
    assertThatThrownBy(() -> service.activateGeneration(tenant.getId(), "page:page-1", 1))
        .isInstanceOf(ManagedTenantResourceException.class);
    assertThatThrownBy(() -> service.activate(tenant.getId(), "page:page-1"))
        .isInstanceOf(ManagedTenantResourceException.class);
    service.activateGeneration(tenant.getId(), "page:page-1", 2);
    service.offlineFromEvent(event);
    assertThat(service.get(tenant.getId(), "page:page-1").enabled()).isTrue();
    session.refresh(grant);
    assertThat(grant.getDeletedAt()).isNotNull();
  }

  @Test
  void permissionMetadataActivatesAtomicallyAndStaleGenerationsCannotReplaceIt() {
    var page = service.prepare(page("Permission page"));
    var metadata = metadata();
    assertThat(service.activateWithPermissions(tenant.getId(), "page:page-1", 2, metadata).enabled()).isTrue();
    var entity = session.find(TenantResource.class,
        new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey(tenant.getId(), page.id()));
    assertThat(entity.getPermissionMetadata()).isEqualTo(metadata.toJson());
    assertThatThrownBy(() -> service.activateWithPermissions(tenant.getId(), "page:page-1", 1,
        new org.simplepoint.core.permission.PermissionPageDescriptor(List.of())))
        .isInstanceOf(ManagedTenantResourceException.class);
    assertThat(entity.getPermissionMetadata()).isEqualTo(metadata.toJson());
    service.activateWithPermissions(tenant.getId(), "page:page-1", 2, metadata);
    assertThat(entity.getPermissionMetadata()).isEqualTo(metadata.toJson());
    service.deactivate(tenant.getId(), "page:page-1");
    assertThatThrownBy(() -> service.refreshPermissions(tenant.getId(), "page:page-1", 2, metadata))
        .isInstanceOf(ManagedTenantResourceException.class);
    assertThat(service.get(tenant.getId(), "page:page-1").enabled()).isFalse();
  }

  @Test
  void legacyCatalogRefreshAdoptsTheActiveGenerationOnceWithoutChangingVisibilityOrGrants() {
    var page = service.prepare(page("Legacy page"));
    var action = service.prepare(action());
    service.activate(tenant.getId(), "page:page-1");
    service.deactivate(tenant.getId(), "page:page-1:create");
    var revoked = new org.simplepoint.security.entity.RoleResourceGrant();
    revoked.setTenantId(tenant.getId()); revoked.setRoleId("role"); revoked.setResourceCode(action.resourceCode());
    revoked.setDeletedAt(java.time.Instant.EPOCH);
    session.persist(revoked); session.flush();
    service.refreshPermissions(tenant.getId(), "page:page-1", 3, metadata());
    var root = session.find(TenantResource.class,
        new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey(tenant.getId(), page.id()));
    var child = session.find(TenantResource.class,
        new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey(tenant.getId(), action.id()));
    assertThat(root.isEnabled()).isTrue();
    assertThat(child.isEnabled()).isFalse();
    assertThat(root.getLifecycleGeneration()).isEqualTo(3);
    assertThat(child.getLifecycleGeneration()).isEqualTo(3);
    assertThat(root.getPermissionMetadata()).isEqualTo(metadata().toJson());
    session.refresh(revoked);
    assertThat(revoked.getDeletedAt()).isEqualTo(java.time.Instant.EPOCH);
    service.refreshPermissions(tenant.getId(), "page:page-1", 3, metadata());
    for (long generation : new long[] {0, 2, 4}) {
      assertThatThrownBy(() -> service.refreshPermissions(tenant.getId(), "page:page-1", generation, metadata()))
          .isInstanceOf(ManagedTenantResourceException.class);
    }
  }

  @Test
  void legacyRefreshCannotMigrateADisabledRootOrAnAdvancedDescendant() {
    var page = service.prepare(page("Legacy page"));
    var action = service.prepare(action());
    assertThatThrownBy(() -> service.refreshPermissions(tenant.getId(), "page:page-1", 1, metadata()))
        .isInstanceOf(ManagedTenantResourceException.class);
    service.activate(tenant.getId(), "page:page-1");
    var child = session.find(TenantResource.class,
        new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey(tenant.getId(), action.id()));
    assertThatThrownBy(() -> service.refreshPermissions(tenant.getId(), "page:page-1", 0, metadata()))
        .isInstanceOf(ManagedTenantResourceException.class);
    child.setFormallyOffline(true);
    assertThatThrownBy(() -> service.refreshPermissions(tenant.getId(), "page:page-1", 3, metadata()))
        .isInstanceOf(ManagedTenantResourceException.class);
    child.setFormallyOffline(false);
    child.setLifecycleGeneration(2);
    assertThatThrownBy(() -> service.refreshPermissions(tenant.getId(), "page:page-1", 3, metadata()))
        .isInstanceOf(ManagedTenantResourceException.class);
    var root = session.find(TenantResource.class,
        new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey(tenant.getId(), page.id()));
    assertThat(root.getLifecycleGeneration()).isZero();
    assertThat(root.getPermissionMetadata()).isNull();
    assertThat(child.getLifecycleGeneration()).isEqualTo(2);
  }

  private static org.simplepoint.core.permission.PermissionPageDescriptor metadata() {
    var field = new org.simplepoint.core.permission.PermissionModelDescriptor.Field(
        "java.lang.String", "Creator", List.of("equals"), List.of(), true);
    return new org.simplepoint.core.permission.PermissionPageDescriptor(List.of(
        new org.simplepoint.core.permission.PermissionModelDescriptor("dna.runtime.record.test", true, true,
            "created_by", "created_org_dept_id", java.util.Map.of("created_by", field))));
  }

  private ManagedTenantResourceCommand page(final String name) {
    return new ManagedTenantResourceCommand(
        tenant.getId(),
        "page:page-1",
        "app.runtime.page.page-1",
        name,
        ResourceType.PAGE,
        null,
        parent.getCode(),
        host.getCode(),
        "/app/runtime/application-1/page-1",
        "FormOutlined",
        10,
        "Published form"
    );
  }

  private ManagedTenantResourceCommand action() {
    return new ManagedTenantResourceCommand(
        tenant.getId(),
        "page:page-1:create",
        "app.runtime.page.page-1.records.create",
        "Create record",
        ResourceType.ACTION,
        "page:page-1",
        null,
        null,
        null,
        "PlusOutlined",
        10,
        null
    );
  }

  private static Resource platform(
      final String id,
      final String code,
      final ResourceType type,
      final String component
  ) {
    Resource resource = new Resource();
    resource.setId(id);
    resource.setCode(code);
    resource.setName(code);
    resource.setType(type);
    resource.setComponent(component);
    resource.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
    resource.setDisabled(false);
    resource.setDeletedAt(null);
    return resource;
  }
}
