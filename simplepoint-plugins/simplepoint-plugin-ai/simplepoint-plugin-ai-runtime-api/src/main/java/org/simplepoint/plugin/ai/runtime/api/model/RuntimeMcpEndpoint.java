package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * Ephemeral connection material for a managed OCI MCP workload.
 */
public record RuntimeMcpEndpoint(
    String endpointUrl,
    String workloadId,
    String leaseId,
    long fencingToken
) {
}
