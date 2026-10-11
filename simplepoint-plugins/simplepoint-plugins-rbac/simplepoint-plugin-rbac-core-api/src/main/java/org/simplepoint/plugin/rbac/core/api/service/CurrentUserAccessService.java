package org.simplepoint.plugin.rbac.core.api.service;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Read-only access and organization information for the signed-in user's profile. */
public interface CurrentUserAccessService {
  /** Self-membership qualifies the raw identity; display IDs/names and path names are projected from Organization. */
  record OrganizationInfo(String id, String name, List<String> path) {}

  /** Actual source fields are projected before this DTO is built; platform and tenant models are distinct. */
  record ResourceAccess(
      String code,
      String label,
      String type,
      String path,
      String icon,
      boolean effective,
      boolean available,
      List<String> directory) {}

  /** Details are loaded only when a page card is opened. Policy slots are reserved for future rules. */
  record PageAccessDetail(ResourceAccess page, List<ResourceAccess> operations,
      boolean dataPermissionSupported, boolean fieldPermissionSupported) {}

  /** Pages the current context's effective permissions or one of this user's own roles. */
  Page<ResourceAccess> resources(String roleId, String keyword, String type, Pageable pageable);

  PageAccessDetail pageDetail(String roleId, String code);

  /** Returns this user's assigned organization in the active tenant. */
  OrganizationInfo organization();
}
