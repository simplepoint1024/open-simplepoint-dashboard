package org.simplepoint.plugin.ai.mcp.api.model;

import java.time.Instant;
import org.simplepoint.core.query.TablePagination;

/** Safe workbench summary of one immutable MCP capability snapshot. */
@org.simplepoint.core.query.TableQuery(
    value = {
      @org.simplepoint.core.query.QueryField("discoveredAt"),
      @org.simplepoint.core.query.QueryField("remoteServerName"),
      @org.simplepoint.core.query.QueryField("remoteServerVersion"),
      @org.simplepoint.core.query.QueryField("protocolVersion"),
      @org.simplepoint.core.query.QueryField("schemaHash"),
      @org.simplepoint.core.query.QueryField("active")
    },
    maxPageSize = TablePagination.MAX_PAGE_SIZE)
public record McpCapabilitySnapshotSummary(
    String id,
    String serverId,
    String protocolVersion,
    String remoteServerName,
    String remoteServerVersion,
    String schemaHash,
    Instant discoveredAt,
    boolean active,
    int toolCount,
    int resourceCount,
    int resourceTemplateCount,
    int promptCount) {}
