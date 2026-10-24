package org.simplepoint.plugin.rbac.tenant.api.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Tenant resource catalog and entitlement boundary. */
public interface TenantResourceCatalog {
  /**
   * Tenant-local permissions and the exclusive deadline for re-evaluating time-based eligibility.
   */
  record AuthorizationSelection(Set<String> resourceCodes, Instant validUntil) {
    public AuthorizationSelection {
      resourceCodes = Set.copyOf(resourceCodes);
    }
  }

  /**
   * Resolves role grants, eligible public entries and aliases from a ready tenant directory. The
   * caller must establish ownership or explicit administrator policy before requesting all entries.
   * A null deadline means no scheduled entitlement transition; lifecycle changes still invalidate
   * caches.
   */
  default AuthorizationSelection authorization(
      String tenantId, Collection<String> roleCodes, boolean allEntitled) {
    return authorization(tenantId, roleCodes, allEntitled, null);
  }

  /** Actor identity comes from authentication; ownership is rechecked against the tenant row. */
  AuthorizationSelection authorization(
      String tenantId, Collection<String> roleCodes, boolean allEntitled, String actorId);

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
      Boolean enabled,
      @org.simplepoint.api.schema.LocalizedField(maxLength = 100)
      org.simplepoint.api.locale.LocalizedText nameLocalization) {
    /** Older callers keep the scalar contract; an absent localized value never deletes translations. */
    public Mutation(String parentId, String name, ResourceType type, String path, String targetResourceId,
        String icon, Integer sort, String description, Boolean enabled) {
      this(parentId, name, type, path, targetResourceId, icon, sort, description, enabled, null);
    }
  }

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

  /** Eligible executable entries and their structural ancestors for the tenant role editor. */
  Set<String> roleResourceIds(String tenantId);

  /**
   * Intersects requested executable codes with current tenant eligibility, including public
   * non-grantable entries. Does not add permissions or authorize the caller; the consumer must
   * supply only codes held by its verified caller.
   */
  Set<String> eligiblePermissionCodes(String tenantId, Collection<String> requested);

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

  /**
   * Eligible role-grant codes from the ready tenant directory, current time balances and resource
   * lifecycle.
   */
  Set<String> grantableCodes(String tenantId, Collection<String> requested);

  /**
   * Platform-origin permission codes resolved from tenant copies and eligible page aliases, without
   * platform reads.
   */
  Set<String> effectivePlatformCodes(String tenantId, Collection<String> requested);

  /**
   * Active service-managed permission codes with the same tenant readiness, lifecycle and hierarchy
   * checks.
   */
  Set<String> managedGrantableCodes(String tenantId, Collection<String> requested);

  /**
   * Internal enumeration of eligible managed codes; calling domains still decide whether the actor
   * may hold them.
   */
  Set<String> activeManagedGrantableCodes(String tenantId);

  /** Held managed navigation nodes and their eligible tenant-side ancestors. */
  List<TenantResource> managedNavigationResources(String tenantId, Collection<String> heldCodes);
}
