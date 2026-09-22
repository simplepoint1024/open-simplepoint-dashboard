package org.simplepoint.plugin.rbac.core.api.service;

import java.util.List;
import org.simplepoint.core.annotation.PermissionFields;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Bounded resource editing, independent of the currently loaded page. */
public interface RoleResourceConfigurationService {
  /** Reserved resource-scoped extension; unsupported slots never imply an effective grant. */
  record PolicySlot(boolean supported, String policyId) {}

  /** Minimal resource projection. Revision covers the resource and this role's explicit grants. */
  record Row(
      @PermissionFields(resource = Resource.class, value = "id") String id,
      @PermissionFields(resource = Resource.class, value = "code") String code,
      @PermissionFields(resource = Resource.class, value = {"alias", "label", "name", "title", "code"}) String label,
      @PermissionFields(resource = Resource.class, value = "type") String type,
      @PermissionFields(resource = Resource.class, value = "path") String path,
      @PermissionFields(resource = Resource.class, value = "parentId") String parentId,
      @PermissionFields(resource = Resource.class, value = "icon") String icon,
      @PermissionFields(resource = Resource.class, value = "parentId") boolean hasChildren,
      @PermissionFields(resource = Resource.class, value = "parentId") boolean hasNavigationChildren,
      @PermissionFields(resource = Resource.class, value = "description") String description,
      @PermissionFields(resource = Resource.class, value = "grantable") boolean grantable,
      @PermissionFields(resource = RoleResourceGrant.class, value = {"roleId", "resourceCode"}) boolean granted,
      String revision, PolicySlot dataPermission, PolicySlot fieldPermission) {}

  /** Explicit changes only: an omitted resource is never revoked. */
  record Change(String resourceId, String resourceCode, String revision, Boolean granted) {}

  /** One atomic, bounded save. */
  record Patch(List<Change> changes) {}

  /** Server-calculated subtree impact and optimistic revision. */
  record BulkPreview(String resourceId, boolean granted, int total, int changed, String revision) {}

  /** Applies the exact subtree state that was previewed. */
  record BulkCommand(String resourceId, Boolean granted, String revision) {}

  /** Searches accessible resources in SQL; rootsOnly and parentId each load one tree level. */
  Page<Row> list(String roleId, String keyword, String state, String type, String parentId,
      boolean rootsOnly, boolean navigationOnly, Pageable page);

  /** Checks resource revisions under the role lock before applying any changes. */
  void save(String roleId, Patch patch);

  /** Includes the selected resource and every visible, grantable descendant. */
  BulkPreview previewSubtree(String roleId, String resourceId, boolean granted);

  /** Lists every visible resource in a subtree, including structural nodes for tree display. */
  List<Row> subtreeRows(String roleId, String resourceId);

  /** Atomically applies a previewed subtree change under the role lock. */
  void saveSubtree(String roleId, BulkCommand command);
}
