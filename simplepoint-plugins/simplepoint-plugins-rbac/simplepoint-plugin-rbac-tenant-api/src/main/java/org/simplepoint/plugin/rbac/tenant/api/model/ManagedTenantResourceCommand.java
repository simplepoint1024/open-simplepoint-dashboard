package org.simplepoint.plugin.rbac.tenant.api.model;

import org.simplepoint.security.entity.ResourceType;

/** Idempotent declaration of one service-owned tenant resource. */
public record ManagedTenantResourceCommand(
    String tenantId,
    String managedKey,
    String resourceCode,
    String name,
    ResourceType type,
    String parentManagedKey,
    String platformParentCode,
    String hostResourceCode,
    String path,
    String icon,
    Integer sort,
    String description,
    org.simplepoint.api.locale.LocalizedText nameLocalization,
    String navigationRootKey,
    Long navigationRevision
) {
  public ManagedTenantResourceCommand(String tenantId, String managedKey, String resourceCode, String name,
      ResourceType type, String parentManagedKey, String platformParentCode, String hostResourceCode,
      String path, String icon, Integer sort, String description) {
    this(tenantId, managedKey, resourceCode, name, type, parentManagedKey, platformParentCode,
        hostResourceCode, path, icon, sort, description, null, null, null);
  }
}
