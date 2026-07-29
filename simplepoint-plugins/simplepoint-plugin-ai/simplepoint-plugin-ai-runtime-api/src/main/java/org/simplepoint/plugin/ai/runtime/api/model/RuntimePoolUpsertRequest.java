package org.simplepoint.plugin.ai.runtime.api.model;

import java.util.List;

/**
 * Desired state for a horizontally scalable OCI MCP runtime pool.
 */
public record RuntimePoolUpsertRequest(
    String code,
    String name,
    String serverId,
    String imageReference,
    String imageDigest,
    long memoryBytes,
    long nanoCpus,
    long pidsLimit,
    String networkMode,
    List<String> egressAllowlist,
    List<String> secretIds,
    int minReplicas,
    int maxReplicas,
    int desiredReplicas,
    int activationReplicas,
    int prewarmNodes,
    long idleTimeoutSeconds,
    long replicaLifetimeSeconds
) {
}
