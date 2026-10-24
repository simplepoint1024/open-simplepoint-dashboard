package org.simplepoint.plugin.rbac.tenant.api.service;

import java.util.Collection;

/**
 * Identity-local catalog effects, invoked under the catalog write boundary in its transaction. This
 * port is not an RPC contract; cross-service business offline keeps the durable event protocol.
 */
public interface PlatformResourceCatalogEffects {
  /** Propagates current definitions to existing tenant mirrors, without granting time or roles. */
  void synchronize(Collection<String> changedResourceIds);

  /** Moves inherited placement while preserving tenant display overrides and custom placement. */
  default void synchronize(
      Collection<String> changedResourceIds, java.util.Map<String, ParentChange> parentChanges) {
    synchronize(changedResourceIds);
  }

  /** Both values are physical catalog IDs inside the same identity transaction; null means root. */
  record ParentChange(String previousParentId, String parentId) {}

  /**
   * Retains tenant rows and grant history while retiring the removed capabilities and dependents.
   */
  void retire(Collection<String> removedResourceIds, Collection<String> removedCodes);

  /**
   * Migrates platform-only policy references; tenant references follow their owned mirror update.
   */
  void renamePlatformCode(String oldCode, String newCode);
}
