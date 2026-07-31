package org.simplepoint.plugin.ai.catalog.api.service;

import org.simplepoint.plugin.ai.catalog.api.entity.AiCatalogSyncState;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogImportRequest;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogPage;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogSyncResult;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogPackageKind;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSource;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;

/**
 * Internal extension market and official MCP Registry facade.
 */
public interface AiCatalogService {

  /**
   * Pages a normalized mixed-source catalog in the current scope.
   */
  AiCatalogPage findAll(
      String query,
      CatalogSource source,
      CatalogPackageKind kind,
      int page,
      int size
  );

  /**
   * Returns the durable official Registry synchronization state.
   */
  AiCatalogSyncState getOfficialSyncState();

  /**
   * Performs one bounded official Registry synchronization pass.
   */
  AiCatalogSyncResult syncOfficialRegistry();

  /**
   * Imports one safe official MCP Server into the current scope.
   */
  AiMcpServerDefinition importOfficialMcpServer(
      String entryId,
      AiCatalogImportRequest request
  );
}
