package org.simplepoint.plugin.ai.runtime.api.model;

import java.time.Instant;

/**
 * Registration or heartbeat acknowledgement returned to a runtime node.
 *
 * @param nodeId stable node identifier
 * @param instanceId accepted runtime process generation
 * @param generation monotonically increasing control-plane node generation
 * @param status authoritative node state
 * @param acceptedAt control-plane acceptance time
 * @param heartbeatIntervalSeconds requested heartbeat interval
 * @param heartbeatTimeoutSeconds heartbeat expiry window
 */
public record RuntimeNodeControlResponse(
    String nodeId,
    String instanceId,
    long generation,
    RuntimeNodeStatus status,
    Instant acceptedAt,
    long heartbeatIntervalSeconds,
    long heartbeatTimeoutSeconds
) {
}
