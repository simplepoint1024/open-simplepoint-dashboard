package org.simplepoint.plugin.ai.runtime.api.model;

import java.time.Instant;

/**
 * Sanitized workload state returned by an independent Tool Runtime node.
 *
 * @param workloadId durable workload identifier
 * @param leaseId active assignment lease identifier
 * @param fencingToken assignment fence accepted by the node
 * @param executionId caller execution identifier
 * @param tenantId owning tenant
 * @param containerId OCI container identifier
 * @param image digest-pinned OCI image
 * @param state engine lifecycle state
 * @param health optional container health state
 * @param startedAt engine start time
 * @param finishedAt engine finish time
 * @param exitCode engine exit code
 * @param deadline runtime-enforced deadline
 * @param runtimeNode stable runtime node identifier
 */
public record RuntimeWorkloadObservation(
    String workloadId,
    String leaseId,
    long fencingToken,
    String executionId,
    String tenantId,
    String containerId,
    String image,
    String state,
    String health,
    Instant startedAt,
    Instant finishedAt,
    int exitCode,
    Instant deadline,
    String runtimeNode
) {
}
