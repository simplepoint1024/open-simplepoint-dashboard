package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Tenant resource catalog and entitlement boundary. */
public interface TenantResourceCatalog {
  record Mutation(String parentId, String name, ResourceType type, String path,
      String targetResourceId, String icon, Integer sort, String description, Boolean enabled) {}

  record SyncResult(int added, int refreshed, int deactivated) {}

  record SyncItem(String id, String code, String name) {}

  record SyncPreview(int added, int refreshed, int deactivated,
      List<SyncItem> additions, List<SyncItem> deactivations, boolean overwrite) {}

  TenantResourceMode mode(String tenantId);

  TenantResourceMode selectMode(TenantResourceMode mode);

  SyncResult synchronize(boolean overwrite);

  SyncPreview previewSynchronization(boolean overwrite);

  Page<TenantResource> list(String keyword, String parentId, boolean rootsOnly, Pageable pageable);

  TenantResource get(String id);

  TenantResource create(Mutation mutation);

  TenantResource update(String id, Mutation mutation);

  void delete(String id);

  /** Active catalog entries; includes structural ancestors. */
  List<TenantResource> activeResources(String tenantId);

  /** Codes which may be granted by roles in custom mode. */
  Set<String> grantableCodes(String tenantId, Collection<String> requested);

  /** Effective platform permission codes in custom mode, including licensed page aliases. */
  Set<String> effectivePlatformCodes(String tenantId, Collection<String> requested);
}
