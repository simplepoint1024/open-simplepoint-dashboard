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
  /** Editable navigation metadata; ownership and stable codes remain service-controlled. */
  record Mutation(
      String parentId,
      String name,
      ResourceType type,
      String path,
      String targetResourceId,
      String icon,
      Integer sort,
      String description,
      Boolean enabled) {}

  /** Counts applied entitlement and navigation changes. */
  record SyncResult(int added, int refreshed, int deactivated) {}

  /** Identifies a resource in a synchronization preview. */
  record SyncItem(String id, String code, String name) {}

  /** Describes a bounded synchronization preview without changing resources. */
  record SyncPreview(
      int added,
      int refreshed,
      int deactivated,
      List<SyncItem> additions,
      List<SyncItem> deactivations,
      boolean overwrite) {}

  /** Supported parent and licensed-page picker domains. */
  enum PickerKind {
    PARENT,
    PAGE_TARGET
  }

  /** Serializes grant changes with managed resource lifecycle changes in the caller transaction. */
  default void lockForGrantModification(String tenantId) {
    throw new UnsupportedOperationException("Tenant grant serialization is required");
  }

  /** Returns the tenant's resource loading mode. */
  TenantResourceMode mode(String tenantId);

  /** Changes the authorized workspace's resource loading mode. */
  TenantResourceMode selectMode(TenantResourceMode mode);

  /** Reconciles entitlements and optionally replaces local presentation. */
  SyncResult synchronize(boolean overwrite);

  /** Previews synchronization without changing resources or grants. */
  SyncPreview previewSynchronization(boolean overwrite);

  /** Pages resources under a mandatory tenant and parent boundary. */
  Page<TenantResource> list(String keyword, String parentId, boolean rootsOnly, Pageable pageable);

  /** Compiles declared column filters inside the fixed tenant and parent boundary. */
  Page<TenantResource> query(
      java.util.Map<String, String> filters, String parentId, boolean rootsOnly, Pageable pageable);

  /** Returns the current resource-list query contract. */
  java.util.Map<String, Object> querySchema();

  /** Paged eligible form choices, with the same eligibility for bounded selected-ID lookup. */
  Page<TenantResource> picker(
      PickerKind kind,
      ResourceType childType,
      String excludedId,
      String keyword,
      String selectedId,
      Pageable pageable);

  /** Loads a resource owned by the selected workspace. */
  TenantResource get(String id);

  /** Creates a local navigation entry backed by an eligible target. */
  TenantResource create(Mutation mutation);

  /** Updates presentation while preserving immutable ownership. */
  TenantResource update(String id, Mutation mutation);

  /** Deletes an eligible local resource and its dependent grants. */
  void delete(String id);

  /** Active catalog entries; includes structural ancestors. */
  List<TenantResource> activeResources(String tenantId);

  /**
   * Held navigation nodes and required active ancestors, with backing pages suppressed for selected
   * aliases.
   */
  List<TenantResource> navigationResources(String tenantId, Collection<String> heldCodes);

  /**
   * Selected metadata with valid alias targets/ancestors; does not suppress backing pages for
   * non-navigation views.
   */
  List<TenantResource> selectedResources(String tenantId, Collection<String> codes);

  /** Codes which may be granted by roles in custom mode. */
  Set<String> grantableCodes(String tenantId, Collection<String> requested);

  /** Effective platform permission codes in custom mode, including licensed page aliases. */
  Set<String> effectivePlatformCodes(String tenantId, Collection<String> requested);

  /** Active service-managed permission codes, independent of presentation mode. */
  Set<String> managedGrantableCodes(String tenantId, Collection<String> requested);

  /** All active service-managed permission codes for tenant owner/administrator access. */
  Set<String> activeManagedGrantableCodes(String tenantId);

  /** Held managed navigation nodes and their managed ancestors. */
  List<TenantResource> managedNavigationResources(
      String tenantId,
      Collection<String> heldCodes
  );
}
