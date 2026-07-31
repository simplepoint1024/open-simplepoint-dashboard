package org.simplepoint.plugin.ai.catalog.api.model;

/**
 * Synchronization lifecycle.
 */
public enum CatalogSyncStatus {
  NEVER,
  RUNNING,
  SUCCEEDED,
  PARTIAL,
  FAILED
}
