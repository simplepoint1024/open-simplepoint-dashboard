package org.simplepoint.plugin.ai.catalog.service.registry;

import java.time.Instant;

/**
 * Official MCP Registry v0.1 reader.
 */
public interface OfficialMcpRegistryClient {

  /**
   * Fetches one bounded official Registry cursor page.
   */
  OfficialRegistryPage fetch(
      String cursor,
      Instant updatedSince,
      boolean includeDeleted,
      int limit
  );
}
