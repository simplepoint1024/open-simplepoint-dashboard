package org.simplepoint.plugin.ai.runtime.api.model;

import java.util.List;

/**
 * Dynamic runtime-node state renewed within the control-plane heartbeat lease.
 *
 * @param instanceId current runtime process generation
 * @param engineApiVersion negotiated Docker Engine API version
 * @param engineOsType Docker Engine operating-system type
 * @param cpuCores current logical CPU capacity
 * @param memoryBytes current total memory capacity
 * @param maxWorkloads node-local workload concurrency limit
 * @param runningWorkloads currently running owned workloads
 * @param cachedImageDigests content digests already present on the node
 * @param maxWorkloadMemoryBytes maximum memory accepted for one workload
 * @param maxWorkloadNanoCpus maximum CPU quota accepted for one workload
 * @param maxWorkloadPidsLimit maximum process limit accepted for one workload
 * @param healthy whether the Docker Engine boundary is healthy
 * @param error sanitized node error when unhealthy
 */
public record RuntimeNodeHeartbeat(
    String instanceId,
    String engineApiVersion,
    String engineOsType,
    int cpuCores,
    long memoryBytes,
    int maxWorkloads,
    int runningWorkloads,
    List<String> cachedImageDigests,
    long maxWorkloadMemoryBytes,
    long maxWorkloadNanoCpus,
    long maxWorkloadPidsLimit,
    boolean healthy,
    String error
) {
}
