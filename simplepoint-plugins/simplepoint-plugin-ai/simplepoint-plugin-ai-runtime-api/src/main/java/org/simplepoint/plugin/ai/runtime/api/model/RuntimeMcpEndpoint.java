package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * Ephemeral connection material for a managed OCI MCP workload.
 */
public record RuntimeMcpEndpoint(
    String endpointUrl,
    String workloadId,
    String leaseId,
    long fencingToken,
    String sessionAssignmentKey
) {

  /** Preserves construction for endpoints without a capacity reservation. */
  public RuntimeMcpEndpoint(
      final String endpointUrl,
      final String workloadId,
      final String leaseId,
      final long fencingToken
  ) {
    this(endpointUrl, workloadId, leaseId, fencingToken, null);
  }
}
