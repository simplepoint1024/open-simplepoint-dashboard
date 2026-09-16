package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * Sanitized MCP protocol admission result returned by a Tool Runtime node.
 *
 * <p>The report contains no process output, environment values, or secrets.</p>
 */
public record RuntimeMcpProbeReport(
    String protocolVersion,
    boolean toolsSupported,
    int toolCount,
    boolean resourcesSupported,
    int resourceCount,
    boolean promptsSupported,
    int promptCount
) {
}
