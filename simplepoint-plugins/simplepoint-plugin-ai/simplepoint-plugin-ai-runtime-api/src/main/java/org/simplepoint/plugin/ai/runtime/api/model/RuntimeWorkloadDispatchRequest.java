package org.simplepoint.plugin.ai.runtime.api.model;

import java.util.List;

/**
 * Immutable fenced workload envelope sent to one Tool Runtime node.
 *
 * @param workloadId durable workload identifier
 * @param leaseId active assignment lease identifier
 * @param fencingToken monotonically increasing assignment fence
 * @param executionId caller execution identifier
 * @param tenantId owning tenant, blank for platform workloads
 * @param image digest-pinned OCI image reference
 * @param memoryBytes memory limit
 * @param nanoCpus CPU quota
 * @param pidsLimit process limit
 * @param timeoutSeconds remaining execution deadline
 * @param networkMode isolated network mode
 * @param egressAllowlist signed DNS policy source for egress mode
 * @param secrets short-lived files resolved immediately before dispatch
 */
public record RuntimeWorkloadDispatchRequest(
    String workloadId,
    String leaseId,
    long fencingToken,
    String executionId,
    String tenantId,
    String image,
    long memoryBytes,
    long nanoCpus,
    long pidsLimit,
    long timeoutSeconds,
    String networkMode,
    List<String> egressAllowlist,
    List<RuntimeSecretFile> secrets
) {
}
