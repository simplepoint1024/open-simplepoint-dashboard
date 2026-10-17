package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingWork;
import org.simplepoint.plugin.rbac.tenant.api.model.ManagedTenantResourceCommand;
import org.simplepoint.plugin.rbac.tenant.api.model.ManagedTenantResourceView;
import org.simplepoint.plugin.rbac.tenant.api.service.ManagedTenantResourceService;
import org.simplepoint.security.entity.ResourceType;

class FormPageResourceCoordinatorTest {

  @Test
  void catalogRefreshReportsRemoteFailureThroughThePublicationErrorContract() {
    var resources = mock(ManagedTenantResourceService.class);
    when(resources.refreshPermissions(org.mockito.ArgumentMatchers.eq("tenant-a"),
        org.mockito.ArgumentMatchers.eq("page:page-a"), org.mockito.ArgumentMatchers.eq(1L), any()))
        .thenThrow(new IllegalStateException("remote conflict"));
    assertThatThrownBy(() -> new FormPageResourceCoordinator(resources, permissions()).refreshPermissions(work()))
        .isInstanceOfSatisfying(org.simplepoint.plugin.app.form.api.error.ApplicationFormException.class,
            error -> assertThat(error.errorCode()).isEqualTo(
                org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode.PUBLICATION_FAILED));
  }

  @Test
  void preparesTheHostedPageAndItsStandardActionSubtree() {
    ManagedTenantResourceService resources = mock(ManagedTenantResourceService.class);
    List<ManagedTenantResourceCommand> commands = new ArrayList<>();
    when(resources.prepare(any())).thenAnswer(invocation -> {
      ManagedTenantResourceCommand command = invocation.getArgument(0);
      commands.add(command);
      return view(command, false);
    });
    FormPageResourceCoordinator coordinator = new FormPageResourceCoordinator(resources, permissions());

    var prepared = coordinator.prepare(work());

    assertThat(prepared.managedKey()).isEqualTo("page:page-a");
    assertThat(prepared.enabled()).isFalse();
    assertThat(commands).hasSize(5);
    ManagedTenantResourceCommand page = commands.getFirst();
    assertThat(page.type()).isEqualTo(ResourceType.PAGE);
    assertThat(page.hostResourceCode()).isEqualTo(FormPageResourceCoordinator.RUNTIME_HOST_CODE);
    assertThat(page.platformParentCode())
        .isEqualTo(FormPageResourceCoordinator.PLATFORM_PARENT_CODE);
    assertThat(page.path()).isEqualTo("/app/runtime/app-a/page-a");
    assertThat(commands.subList(1, commands.size()))
        .allSatisfy(action -> {
          assertThat(action.type()).isEqualTo(ResourceType.ACTION);
          assertThat(action.parentManagedKey()).isEqualTo("page:page-a");
          assertThat(action.path()).isNull();
          assertThat(action.hostResourceCode()).isNull();
        });
  }

  @Test
  void recoversAnUncertainActivationFromTheAuthoritativeResourceState() {
    ManagedTenantResourceService resources = mock(ManagedTenantResourceService.class);
    when(resources.activateWithPermissions(org.mockito.ArgumentMatchers.eq("tenant-a"),
        org.mockito.ArgumentMatchers.eq("page:page-a"), org.mockito.ArgumentMatchers.eq(1L), any()))
        .thenThrow(new IllegalStateException("response lost")).thenReturn(new ManagedTenantResourceView(
        "resource-a",
        "tenant-a",
        "app",
        "page:page-a",
        "app.runtime.page.a",
        "Orders",
        ResourceType.PAGE,
        "parent-a",
        "/app/runtime/app-a/page-a",
        "app/FormRuntime",
        "item",
        true,
        true,
        Instant.EPOCH
    ));

    var active = new FormPageResourceCoordinator(resources, permissions()).activate(work());

    assertThat(active.enabled()).isTrue();
    assertThat(active.resourceId()).isEqualTo("resource-a");
  }

  @Test
  void placesOptInPagesUnderStableDirectoryWithoutReactivatingSiblings() {
    var resources = mock(ManagedTenantResourceService.class);
    var commands = new ArrayList<ManagedTenantResourceCommand>();
    when(resources.prepare(any())).thenAnswer(invocation -> {
      ManagedTenantResourceCommand command = invocation.getArgument(0);
      commands.add(command);
      return view(command, command.type() == ResourceType.GROUP);
    });
    var work = new BindingWork("binding-a", "token-a", "tenant-a", "app-a", "page-a",
        "form-a", 1, "model-a", "page:page-a", "报销", null, 0, 1, 1, "人力办公");
    new FormPageResourceCoordinator(resources, permissions()).prepare(work);
    assertThat(commands.getFirst().type()).isEqualTo(ResourceType.GROUP);
    assertThat(commands.get(1).parentManagedKey()).isEqualTo("application:app-a");
    assertThat(commands.get(1).platformParentCode()).isNull();
    org.mockito.Mockito.verify(resources, org.mockito.Mockito.never()).activate(any(), any());
  }

  private static FormPermissionDescriptorService permissions() {
    var service = mock(FormPermissionDescriptorService.class);
    when(service.describe(any())).thenReturn(new org.simplepoint.core.permission.PermissionPageDescriptor(List.of()));
    return service;
  }

  private static BindingWork work() {
    return new BindingWork(
        "binding-a",
        "token-a",
        "tenant-a",
        "app-a",
        "page-a",
        "form-a",
        1,
        "model-a",
        "page:page-a",
        "Orders",
        "Order entry",
        10, 1
    );
  }

  private static ManagedTenantResourceView view(
      final ManagedTenantResourceCommand command,
      final boolean enabled
  ) {
    return new ManagedTenantResourceView(
        "resource-" + command.managedKey(),
        command.tenantId(),
        "app",
        command.managedKey(),
        command.resourceCode(),
        command.name(),
        command.type(),
        "parent-a",
        command.path(),
        command.type() == ResourceType.PAGE ? "app/FormRuntime" : null,
        command.type() == ResourceType.PAGE ? "item" : null,
        enabled,
        true,
        Instant.EPOCH
    );
  }
}
