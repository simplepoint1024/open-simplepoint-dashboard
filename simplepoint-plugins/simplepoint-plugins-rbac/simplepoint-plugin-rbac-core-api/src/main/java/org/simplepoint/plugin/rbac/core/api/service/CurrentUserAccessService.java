package org.simplepoint.plugin.rbac.core.api.service;

import java.util.List;
import org.simplepoint.core.annotation.PermissionFields;
import org.simplepoint.security.entity.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Read-only access and organization information for the signed-in user's profile. */
public interface CurrentUserAccessService {
  /** The active tenant's organization membership, without cross-tenant data. */
  record OrganizationInfo(String id, String name, List<String> path) {}

  /** A flat permission row; effective and per-role grants are distinct views. */
  record ResourceAccess(
      String code,
      @PermissionFields(resource = Resource.class, value = {"alias", "name", "label", "title", "code"}) String label,
      @PermissionFields(resource = Resource.class, value = "type") String type,
      @PermissionFields(resource = Resource.class, value = "path") String path,
      @PermissionFields(resource = Resource.class, value = "icon") String icon,
      boolean effective,
      boolean available,
      @PermissionFields(resource = Resource.class, value = {"alias", "name", "label", "title", "code"}) List<String> directory) {}

  /** Details are loaded only when a page card is opened. Policy slots are reserved for future rules. */
  record PageAccessDetail(ResourceAccess page, List<ResourceAccess> operations,
      boolean dataPermissionSupported, boolean fieldPermissionSupported) {}

  /** Pages the current context's effective permissions or one of this user's own roles. */
  Page<ResourceAccess> resources(String roleId, String keyword, String type, Pageable pageable);

  PageAccessDetail pageDetail(String roleId, String code);

  /** Returns this user's assigned organization in the active tenant. */
  OrganizationInfo organization();
}
