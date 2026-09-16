package org.simplepoint.plugin.ai.catalog.api.model;

/**
 * Stable, language-neutral failure and warning codes for catalog sync.
 */
public enum AiCatalogSyncErrorCode {
  OFFICIAL_MCP_SYNC_LOCK_BUSY,
  OFFICIAL_MCP_SYNC_PAGE_BUDGET_REACHED,
  OFFICIAL_MCP_DESCRIPTOR_SERIALIZATION_FAILED,
  OFFICIAL_MCP_SYNC_FAILED
}
