package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.resource.ApplicationCatalogRemovalEvents;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.error.ManagedTenantResourceErrorCode;
import org.simplepoint.plugin.rbac.tenant.api.error.ManagedTenantResourceException;
import org.simplepoint.plugin.rbac.tenant.api.model.ManagedTenantResourceCommand;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;

class ManagedTenantResourceServiceImplTest {

  private SessionFactory factory;
  private TenantEntitlementDatabase database;
  private Session session;
  private ResourceAuthorizationVersionService versions;
  private ManagedTenantResourceCallerGuard callers;
  private ManagedTenantResourceServiceImpl service;
  private Tenant tenant;
  private TenantResource parent;
  private TenantResource host;

  @BeforeEach
  void setUp() throws Exception {
    database = new TenantEntitlementDatabase();
    factory =
        database.create(
            Tenant.class,
            TenantResource.class,
            org.simplepoint.plugin.rbac.tenant.api.entity.RetiredApplicationResource.class,
            org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup.class,
            org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceEntitlement.class,
            org.simplepoint.security.entity.RoleResourceGrant.class);
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
    host.setGrantable(false);
    var setup = new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup();
    setup.setTenantId(tenant.getId());
    setup.setState(org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup.State.READY);
    setup.setInitializedAt(java.time.Instant.now());
    session.persist(setup);
    session.persist(parent);
    session.persist(host);
    session.flush();
    versions = mock(ResourceAuthorizationVersionService.class);
    callers = mock(ManagedTenantResourceCallerGuard.class);
    when(callers.requireOwner()).thenReturn("app");
    service = new ManagedTenantResourceServiceImpl(session, versions, callers);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (session.getTransaction().isActive()) {
      session.getTransaction().rollback();
    }
    session.close();
    database.close();
  }

  @Test
  void removesOnlyExactOfflinePageCatalogAndFencesDelayedRegistration() {
    String application = UUID.randomUUID().toString();
    String pageId = UUID.randomUUID().toString();
    String managedKey = ApplicationCatalogRemovalEvents.pageKey(pageId);
    String code = "app.runtime.page." + pageId;
    var declaration = new ManagedTenantResourceCommand(tenant.getId(), managedKey, code, "Owned page",
        ResourceType.PAGE, null, parent.getCode(), host.getCode(),
        "/app/runtime/" + application + "/" + pageId, null, 10, null);
    var page = service.prepare(declaration);
    var unrelated = service.prepare(page("Keep this page"));
    service.activateGeneration(tenant.getId(), managedKey, 1);
    var request = ApplicationCatalogRemovalEvents.requested(tenant.getId(), application, 0,
        UUID.randomUUID(), java.util.Set.of(pageId));
    assertThatThrownBy(() -> service.removeApplicationCatalog(request))
        .isInstanceOf(ManagedTenantResourceException.class);
    service.offlineFromEvent(new org.simplepoint.events.api.EventMessage(UUID.randomUUID(),
        "resource.offline.requested", 1, "app", tenant.getId(), page.id(), 1, "offline",
        "trace", java.time.Instant.now(), com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            .put("managedOwner", "app").put("managedKey", managedKey).put("resourceCode", code)));
    service.removeApplicationCatalog(request);
    service.removeApplicationCatalog(request);
    assertThatThrownBy(() -> service.get(tenant.getId(), managedKey))
        .isInstanceOf(ManagedTenantResourceException.class);
    assertThatThrownBy(() -> service.prepare(declaration)).isInstanceOf(ManagedTenantResourceException.class);
    assertThat(service.get(tenant.getId(), "page:page-1").id()).isEqualTo(unrelated.id());
  }

  @Test
  void preparesDisabledStableTreeThenActivatesAndDeactivatesItAtomically() {
    final var page = service.prepare(page("Application page"));
    final var action = service.prepare(action());

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

    assertThatThrownBy(
            () ->
                service.prepare(
                    new ManagedTenantResourceCommand(
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
                        null)))
        .isInstanceOfSatisfying(
            ManagedTenantResourceException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(ManagedTenantResourceErrorCode.RESOURCE_CONFLICT));

    when(callers.requireOwner()).thenReturn("workflow");
    assertThatThrownBy(() -> service.get(tenant.getId(), "page:page-1"))
        .isInstanceOfSatisfying(
            ManagedTenantResourceException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(ManagedTenantResourceErrorCode.RESOURCE_NOT_FOUND));
  }

  @Test
  void rejectsCallerCodesOutsideItsNamespaceAndUntrustedHosts() {
    assertThatThrownBy(
            () ->
                service.prepare(
                    new ManagedTenantResourceCommand(
                        tenant.getId(),
                        "page:page-1",
                        "workflow.page.one",
                        "Page",
                        ResourceType.PAGE,
                        null,
                        parent.getCode(),
                        host.getCode(),
                        "/app/runtime/application-1/page-1",
                        null,
                        10,
                        null)))
        .isInstanceOfSatisfying(
            ManagedTenantResourceException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(ManagedTenantResourceErrorCode.INVALID_REQUEST));

    assertThatThrownBy(
            () ->
                service.prepare(
                    new ManagedTenantResourceCommand(
                        tenant.getId(),
                        "page:page-1",
                        "app.runtime.page.page-1",
                        "Page",
                        ResourceType.PAGE,
                        null,
                        parent.getCode(),
                        "app.missing.host",
                        "/app/runtime/application-1/page-1",
                        null,
                        10,
                        null)))
        .isInstanceOfSatisfying(
            ManagedTenantResourceException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(ManagedTenantResourceErrorCode.HOST_NOT_FOUND));
  }

  @Test
  void formalOfflineRevokesGrantsAndFencesOldEventsAcrossReonline() {
    final var page = service.prepare(page("Application page"));
    final var action = service.prepare(action());
    service.activateGeneration(tenant.getId(), "page:page-1", 1);
    var grant = new org.simplepoint.security.entity.RoleResourceGrant();
    grant.setTenantId(tenant.getId());
    grant.setRoleId("role");
    grant.setResourceCode(action.resourceCode());
    session.persist(grant);
    session.flush();
    var event =
        new org.simplepoint.events.api.EventMessage(
            UUID.randomUUID(),
            "resource.offline.requested",
            1,
            "app",
            tenant.getId(),
            page.id(),
            1,
            "operation",
            "trace",
            java.time.Instant.now(),
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .objectNode()
                .put("managedOwner", "app")
                .put("managedKey", "page:page-1")
                .put("resourceCode", page.resourceCode()));
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
    final var page = service.prepare(page("Permission page"));
    var metadata = metadata();
    assertThat(
            service.activateWithPermissions(tenant.getId(), "page:page-1", 2, metadata).enabled())
        .isTrue();
    var entity =
        session.find(
            TenantResource.class,
            new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey(
                tenant.getId(), page.id()));
    assertThat(entity.getPermissionMetadata()).isEqualTo(metadata.toJson());
    assertThatThrownBy(
            () ->
                service.activateWithPermissions(
                    tenant.getId(),
                    "page:page-1",
                    1,
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
    final var page = service.prepare(page("Legacy page"));
    final var action = service.prepare(action());
    service.activate(tenant.getId(), "page:page-1");
    service.deactivate(tenant.getId(), "page:page-1:create");
    var revoked = new org.simplepoint.security.entity.RoleResourceGrant();
    revoked.setTenantId(tenant.getId());
    revoked.setRoleId("role");
    revoked.setResourceCode(action.resourceCode());
    revoked.setDeletedAt(java.time.Instant.EPOCH);
    session.persist(revoked);
    session.flush();
    service.refreshPermissions(tenant.getId(), "page:page-1", 3, metadata());
    var root =
        session.find(
            TenantResource.class,
            new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey(
                tenant.getId(), page.id()));
    var child =
        session.find(
            TenantResource.class,
            new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey(
                tenant.getId(), action.id()));
    assertThat(root.isEnabled()).isTrue();
    assertThat(child.isEnabled()).isFalse();
    assertThat(root.getLifecycleGeneration()).isEqualTo(3);
    assertThat(child.getLifecycleGeneration()).isEqualTo(3);
    assertThat(root.getPermissionMetadata()).isEqualTo(metadata().toJson());
    session.refresh(revoked);
    assertThat(revoked.getDeletedAt()).isEqualTo(java.time.Instant.EPOCH);
    service.refreshPermissions(tenant.getId(), "page:page-1", 3, metadata());
    for (long generation : new long[] {0, 2, 4}) {
      assertThatThrownBy(
              () ->
                  service.refreshPermissions(tenant.getId(), "page:page-1", generation, metadata()))
          .isInstanceOf(ManagedTenantResourceException.class);
    }
  }

  @Test
  void legacyRefreshCannotMigrateDisabledRootOrAdvancedDescendant() {
    final var page = service.prepare(page("Legacy page"));
    final var action = service.prepare(action());
    assertThatThrownBy(
            () -> service.refreshPermissions(tenant.getId(), "page:page-1", 1, metadata()))
        .isInstanceOf(ManagedTenantResourceException.class);
    service.activate(tenant.getId(), "page:page-1");
    var child =
        session.find(
            TenantResource.class,
            new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey(
                tenant.getId(), action.id()));
    assertThatThrownBy(
            () -> service.refreshPermissions(tenant.getId(), "page:page-1", 0, metadata()))
        .isInstanceOf(ManagedTenantResourceException.class);
    child.setFormallyOffline(true);
    assertThatThrownBy(
            () -> service.refreshPermissions(tenant.getId(), "page:page-1", 3, metadata()))
        .isInstanceOf(ManagedTenantResourceException.class);
    child.setFormallyOffline(false);
    child.setLifecycleGeneration(2);
    assertThatThrownBy(
            () -> service.refreshPermissions(tenant.getId(), "page:page-1", 3, metadata()))
        .isInstanceOf(ManagedTenantResourceException.class);
    var root =
        session.find(
            TenantResource.class,
            new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey(
                tenant.getId(), page.id()));
    assertThat(root.getLifecycleGeneration()).isZero();
    assertThat(root.getPermissionMetadata()).isNull();
    assertThat(child.getLifecycleGeneration()).isEqualTo(2);
  }

  @Test
  void personalWorkspaceUsesItsOwnHostAndReportsItsOwnSource() {
    tenant.setTenantType(TenantType.PERSONAL);
    parent.setScopeTypes(EnumSet.of(ResourceScopeType.PERSONAL));
    host.setScopeTypes(EnumSet.of(ResourceScopeType.PERSONAL));
    when(callers.requireOwner()).thenReturn("report");
    var page =
        service.prepare(
            new ManagedTenantResourceCommand(
                tenant.getId(),
                "report:one",
                "report.page.one",
                "Report",
                ResourceType.PAGE,
                null,
                parent.getCode(),
                host.getCode(),
                "/report/one",
                null,
                1,
                null));
    assertThat(service.activateGeneration(tenant.getId(), "report:one", 1).enabled()).isTrue();
    var row =
        session.find(
            TenantResource.class,
            new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey(
                tenant.getId(), page.id()));
    assertThat(row.getOrigin())
        .isEqualTo(org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceOrigin.REPORT);
    assertThat(row.getManagedHostResourceId()).isEqualTo(host.getId());
  }

  @Test
  void anotherTenantHostCannotSatisfyThisTenantDeclaration() {
    session.remove(host);
    session.flush();
    var foreign = platform("runtime-host", host.getCode(), ResourceType.PAGE, "foreign/Host");
    foreign.setTenantId("foreign-tenant");
    session.persist(foreign);
    session.flush();
    assertThatThrownBy(() -> service.prepare(page("Page")))
        .isInstanceOfSatisfying(
            ManagedTenantResourceException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(ManagedTenantResourceErrorCode.HOST_NOT_FOUND));
  }

  @Test
  void preparedPageCannotActivateAfterItsHostEntitlementExpires() {
    service.prepare(page("Page"));
    host.setBaselineEntitled(false);
    session.flush();
    assertThatThrownBy(() -> service.activateGeneration(tenant.getId(), "page:page-1", 1))
        .isInstanceOfSatisfying(
            ManagedTenantResourceException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(ManagedTenantResourceErrorCode.HOST_NOT_FOUND));
    assertThat(service.get(tenant.getId(), "page:page-1").enabled()).isFalse();
  }

  @Test
  void hostLossRemovesPageAndActionsFromAuthorizationWithoutAddingHostGrants() {
    final var page = service.prepare(page("Page"));
    final var action = service.prepare(action());
    service.activateGeneration(tenant.getId(), "page:page-1", 1);
    var requested = List.of(page.resourceCode(), action.resourceCode());
    assertThat(
            new TenantResourceReadQuery(session, tenant.getId())
                .authorization(requested, false)
                .resourceCodes())
        .containsExactlyInAnyOrderElementsOf(requested);
    host.setManualDisabled(true);
    session.flush();
    var query = new TenantResourceReadQuery(session, tenant.getId());
    assertThat(query.authorization(requested, false).resourceCodes()).isEmpty();
    assertThat(query.navigationResources(requested)).isEmpty();
    assertThat(query.eligiblePermissionCodes(requested)).isEmpty();
    assertThat(query.roleResourceIds()).doesNotContain(page.id(), action.id());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"expired", "scope", "type", "component", "source", "offline"})
  void hostChangesInvalidateAlreadyPublishedPagesAndRejectReactivation(String reason) {
    final var page = service.prepare(page("Page"));
    service.activateGeneration(tenant.getId(), "page:page-1", 1);
    switch (reason) {
      case "expired" -> host.setBaselineEntitled(false);
      case "scope" -> host.setScopeTypes(EnumSet.of(ResourceScopeType.PLATFORM));
      case "type" -> host.setType(ResourceType.MODULE);
      case "component" -> host.setComponent(" ");
      case "source" -> host.setSourceAvailable(false);
      case "offline" -> host.setFormallyOffline(true);
      default -> throw new AssertionError(reason);
    }
    session.flush();
    var query = new TenantResourceReadQuery(session, tenant.getId());
    assertThat(query.authorization(List.of(page.resourceCode()), false).resourceCodes()).isEmpty();
    assertThat(query.navigationResources(List.of(page.resourceCode()))).isEmpty();
    assertThatThrownBy(() -> service.activateGeneration(tenant.getId(), "page:page-1", 2))
        .isInstanceOfSatisfying(
            ManagedTenantResourceException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(ManagedTenantResourceErrorCode.HOST_NOT_FOUND));
  }

  @Test
  void navigationUsesUpdatedTenantHostWithoutRewritingManagedPublication() {
    final var page = service.prepare(page("Page"));
    service.activateGeneration(tenant.getId(), "page:page-1", 1);
    host.setComponent("app/UpdatedFormRuntime");
    session.flush();
    var projected =
        new TenantResourceReadQuery(session, tenant.getId())
            .navigationResources(List.of(page.resourceCode()));
    assertThat(
            projected.stream()
                .filter(row -> row.getId().equals(page.id()))
                .findFirst()
                .orElseThrow()
                .getComponent())
        .isEqualTo("app/UpdatedFormRuntime");
    assertThat(service.get(tenant.getId(), "page:page-1").component()).isEqualTo("app/FormRuntime");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"disabled", "manual", "offline", "source", "scope"})
  void unavailableTenantParentCannotBeUsed(String reason) {
    switch (reason) {
      case "disabled" -> parent.setEnabled(false);
      case "manual" -> parent.setManualDisabled(true);
      case "offline" -> parent.setFormallyOffline(true);
      case "source" -> parent.setSourceAvailable(false);
      case "scope" -> parent.setScopeTypes(EnumSet.of(ResourceScopeType.PLATFORM));
      default -> throw new AssertionError(reason);
    }
    session.flush();
    assertThatThrownBy(() -> service.prepare(page("Page")))
        .isInstanceOfSatisfying(
            ManagedTenantResourceException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(ManagedTenantResourceErrorCode.PARENT_NOT_FOUND));
  }

  private static org.simplepoint.core.permission.PermissionPageDescriptor metadata() {
    var field =
        new org.simplepoint.core.permission.PermissionModelDescriptor.Field(
            "java.lang.String", "Creator", List.of("equals"), List.of(), true);
    return new org.simplepoint.core.permission.PermissionPageDescriptor(
        List.of(
            new org.simplepoint.core.permission.PermissionModelDescriptor(
                "dna.runtime.record.test",
                true,
                true,
                "created_by",
                "created_org_dept_id",
                java.util.Map.of("created_by", field))));
  }


  @Test
  void navigationMovesAnOfflinePageWithoutChangingItsIdentityLifecycleOrGrantsAndFencesStalePublication() {
    var page = service.prepare(page("Orders")); service.prepare(action());
    var resource = session.find(TenantResource.class, new org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey(tenant.getId(), page.id()));
    resource.setFormallyOffline(true); resource.setLifecycleGeneration(4);
    var command = navigation(1, "Orders");
    assertThat(service.applyNavigation(command)).isEqualTo(1);
    assertThat(service.applyNavigation(command)).isEqualTo(1);
    var changed = service.get(tenant.getId(), page.managedKey());
    assertThat(changed.id()).isEqualTo(page.id()); assertThat(changed.path()).isEqualTo(page.path());
    assertThat(changed.enabled()).isFalse(); assertThat(resource.isFormallyOffline()).isTrue();
    assertThat(resource.getLifecycleGeneration()).isEqualTo(4);
    assertThat(changed.parentId()).isEqualTo(service.get(tenant.getId(), "application:app:directory:child").id());
    assertThatThrownBy(() -> service.prepare(page("Old publication"))).isInstanceOf(ManagedTenantResourceException.class);
    assertThatThrownBy(() -> service.applyNavigation(navigation(1, "Changed payload"))).isInstanceOf(ManagedTenantResourceException.class);
    assertThat(service.cancelNavigation(command)).isFalse();
  }

  @Test
  void cancellationFencesDelayedWritesAndAllowsNextRevisionWithoutDisablingExistingResources() {
    var command = navigation(1, "Orders");
    assertThat(service.cancelNavigation(command)).isTrue();
    assertThat(service.cancelNavigation(command)).isTrue();
    assertThatThrownBy(() -> service.applyNavigation(command)).isInstanceOf(ManagedTenantResourceException.class);
    assertThat(service.applyNavigation(navigation(2, "Orders"))).isEqualTo(2);
    assertThat(service.get(tenant.getId(), "application:app").enabled()).isTrue();
    var next = navigation(3, "Renamed");
    assertThat(service.cancelNavigation(next)).isTrue();
    assertThat(service.get(tenant.getId(), "application:app").enabled()).isTrue();
    assertThat(service.applyNavigation(navigation(4, "Orders"))).isEqualTo(4);
  }

  @Test
  void rejectsCyclicExternalMountAndForeignTenantParent() {
    service.applyNavigation(navigation(1, "Orders"));
    var command = navigation(2, "Orders");
    var cycle = new org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationCommand(command.tenantId(), command.rootKey(), 2,
        "app.runtime.group.child", command.directories(), command.pages());
    assertThatThrownBy(() -> service.applyNavigation(cycle)).isInstanceOf(ManagedTenantResourceException.class);
    var missing = new org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationCommand(command.tenantId(), command.rootKey(), 2,
        "foreign.directory", command.directories(), command.pages());
    assertThatThrownBy(() -> service.applyNavigation(missing)).isInstanceOf(ManagedTenantResourceException.class);
    assertThat(service.get(tenant.getId(), "application:app").parentId()).isEqualTo(parent.getId());
  }

  private org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationCommand navigation(long revision, String label) {
    var name = new org.simplepoint.api.locale.LocalizedText(label, "en-US", java.util.Map.of("ja", "注文"));
    return new org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationCommand(tenant.getId(), "application:app", revision, parent.getCode(), List.of(
        new org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationCommand.Directory("application:app", "app.runtime.group.app", null, label, name, "FolderOutlined", 0),
        new org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationCommand.Directory("application:app:directory:child", "app.runtime.group.child", "application:app", label, name, "FolderOutlined", 0)),
        List.of(new org.simplepoint.plugin.rbac.tenant.api.model.ManagedNavigationCommand.Page("page:page-1", "application:app:directory:child", label, name)));
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
        "Published form");
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
        null);
  }

  private TenantResource platform(
      final String id, final String code, final ResourceType type, final String component) {
    TenantResource resource = new TenantResource();
    resource.setTenantId(tenant.getId());
    resource.setPlatformResourceId(id);
    resource.setBaselineEntitled(true);
    resource.setId(id);
    resource.setCode(code);
    resource.setName(code);
    resource.setType(type);
    resource.setGrantable(type == ResourceType.PAGE);
    resource.setComponent(component);
    resource.setScopeTypes(EnumSet.of(ResourceScopeType.TENANT));
    resource.setEnabled(true);
    return resource;
  }
}
