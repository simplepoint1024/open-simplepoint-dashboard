package org.simplepoint.plugin.app.form.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingWork;
import org.simplepoint.plugin.rbac.tenant.api.model.ManagedTenantResourceCommand;
import org.simplepoint.plugin.rbac.tenant.api.model.ManagedTenantResourceView;
import org.simplepoint.plugin.rbac.tenant.api.service.ManagedTenantResourceService;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.stereotype.Service;

/** Idempotently prepares and activates the managed RBAC subtree for one APP page. */
@Service
public class FormPageResourceCoordinator {

  static final String RUNTIME_HOST_CODE = "app.runtime.host";
  static final String PLATFORM_PARENT_CODE = "app.platform.view";
  private static final List<ActionDeclaration> ACTIONS = List.of(
      new ActionDeclaration("records.create", "新增数据", 0),
      new ActionDeclaration("records.edit", "修改数据", 10),
      new ActionDeclaration("records.delete", "删除数据", 20),
      new ActionDeclaration("process.start", "发起流程", 30)
  );

  private final ManagedTenantResourceService resources;
  private final FormPermissionDescriptorService permissions;

  /** Creates the coordinator over the routed managed-resource contract. */
  public FormPageResourceCoordinator(final ManagedTenantResourceService resources,
      final FormPermissionDescriptorService permissions) {
    this.resources = resources;
    this.permissions = permissions;
  }

  /** Prepares a disabled page and all standard runtime actions. */
  public PreparedPageResource prepare(final BindingWork work) {
    String suffix = stableSuffix(work.pageId());
    String code = "app.runtime.page." + suffix;
    String path = "/app/runtime/" + work.applicationId() + "/" + work.pageId();
    try {
      String parent = prepareDirectory(work);
      ManagedTenantResourceView page = resources.prepare(new ManagedTenantResourceCommand(
          work.tenantId(),
          work.managedKey(),
          code,
          work.pageName(),
          ResourceType.PAGE,
          parent,
          parent == null ? PLATFORM_PARENT_CODE : null,
          RUNTIME_HOST_CODE,
          path,
          "AppstoreOutlined",
          work.sortOrder(),
          work.pageDescription()
      ));
      requirePage(work, page, code, path);
      for (ActionDeclaration action : ACTIONS) {
        ManagedTenantResourceView child = resources.prepare(new ManagedTenantResourceCommand(
            work.tenantId(),
            work.managedKey() + ":" + action.key(),
            code + "." + action.key(),
            action.name(),
            ResourceType.ACTION,
            work.managedKey(),
            null,
            null,
            null,
            null,
            action.sort(),
            null
        ));
        requireAction(work, child, code + "." + action.key());
      }
      return prepared(page);
    } catch (ApplicationFormException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_FAILED,
          "Page resource preparation failed",
          exception
      );
    }
  }

  private String prepareDirectory(BindingWork work) {
    if (work.navigationGroupName() == null) {
      return null;
    }
    String key = "application:" + work.applicationId();
    ManagedTenantResourceView directory = resources.prepare(new ManagedTenantResourceCommand(work.tenantId(), key,
        "app.runtime.group." + stableSuffix(work.applicationId()), work.navigationGroupName(),
        ResourceType.GROUP, null, null, null, null, "TeamOutlined", 100, null));
    // The directory is activated before its first page is prepared. Never activate the
    // directory again during later page publications: that would affect sibling pages.
    if (!directory.enabled()) {
      resources.activate(work.tenantId(), key);
    }
    return key;
  }

  /** Activates the subtree and permission catalog; uncertain responses retry the same fenced command. */
  public PreparedPageResource activate(final BindingWork work) {
    var descriptor = permissions.describe(work);
    try {
      ManagedTenantResourceView page = resources.activateWithPermissions(
          work.tenantId(), work.managedKey(), work.generation(), descriptor);
      PreparedPageResource activated = prepared(page);
      if (!activated.enabled()) {
        throw new IllegalStateException("Managed page resource remained disabled");
      }
      return activated;
    } catch (RuntimeException activationFailure) {
      try {
        PreparedPageResource recovered = prepared(
            resources.activateWithPermissions(work.tenantId(), work.managedKey(), work.generation(), descriptor)
        );
        if (recovered.enabled()) {
          return recovered;
        }
      } catch (RuntimeException recoveryFailure) {
        activationFailure.addSuppressed(recoveryFailure);
      }
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_FAILED,
          "Page resource activation failed",
          activationFailure
      );
    }
  }

  /** Backfills the trusted catalog for an existing active publication without changing visibility. */
  public void refreshPermissions(final BindingWork work) {
    try {
      resources.refreshPermissions(work.tenantId(), work.managedKey(), work.generation(), permissions.describe(work));
    } catch (RuntimeException failure) {
      throw new ApplicationFormException(ApplicationFormErrorCode.PUBLICATION_FAILED,
          "Page permission metadata refresh failed", failure);
    }
  }

  private static void requirePage(
      final BindingWork work,
      final ManagedTenantResourceView page,
      final String code,
      final String path
  ) {
    if (page == null
        || !work.tenantId().equals(page.tenantId())
        || !work.managedKey().equals(page.managedKey())
        || !code.equals(page.resourceCode())
        || page.type() != ResourceType.PAGE
        || !path.equals(page.path())
        || page.component() == null || page.component().isBlank()) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PAGE_BINDING_CONFLICT,
          "Managed page resource returned an inconsistent identity"
      );
    }
  }

  private static void requireAction(
      final BindingWork work,
      final ManagedTenantResourceView action,
      final String code
  ) {
    if (action == null
        || !work.tenantId().equals(action.tenantId())
        || !code.equals(action.resourceCode())
        || action.type() != ResourceType.ACTION) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PAGE_BINDING_CONFLICT,
          "Managed page action returned an inconsistent identity"
      );
    }
  }

  private static PreparedPageResource prepared(final ManagedTenantResourceView resource) {
    return new PreparedPageResource(
        resource.tenantId(),
        resource.managedKey(),
        resource.id(),
        resource.resourceCode(),
        resource.path(),
        resource.enabled()
    );
  }

  private static String stableSuffix(final String pageId) {
    return UUID.nameUUIDFromBytes(pageId.getBytes(StandardCharsets.UTF_8))
        .toString()
        .replace("-", "");
  }

  /** Verified managed page identity persisted by the APP state machine. */
  public record PreparedPageResource(
      String tenantId,
      String managedKey,
      String resourceId,
      String resourceCode,
      String runtimePath,
      boolean enabled
  ) {
  }

  private record ActionDeclaration(String key, String name, int sort) {
  }
}
