package org.simplepoint.plugin.ai.catalog.service.registry;

import java.util.List;

/**
 * One cursor page returned by the official MCP Registry.
 */
public record OfficialRegistryPage(
    List<OfficialRegistryEntry> entries,
    String nextCursor
) {
}
