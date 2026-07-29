package org.simplepoint.plugin.ai.runtime.api.model;

import java.util.List;

/**
 * Scope-neutral request to submit one digest-pinned OCI MCP workload.
 *
 * @param executionId caller idempotency key; generated when omitted
 * @param serverId owning MCP server definition
 * @param imageReference immutable image repository and optional tag
 * @param imageDigest sha256 content digest
 * @param memoryBytes requested memory limit, or zero for the platform default
 * @param nanoCpus requested CPU quota, or zero for the platform default
 * @param pidsLimit requested process limit, or zero for the platform default
 * @param timeoutSeconds total queue and execution deadline
 * @param networkMode isolated network mode: none, bridge, or policy egress
 * @param egressAllowlist exact or wildcard DNS hosts for egress mode
 * @param secretIds encrypted Runtime secret references in the current scope
 */
public record RuntimeWorkloadSubmitRequest(
    String executionId,
    String serverId,
    String imageReference,
    String imageDigest,
    long memoryBytes,
    long nanoCpus,
    long pidsLimit,
    long timeoutSeconds,
    String networkMode,
    List<String> egressAllowlist,
    List<String> secretIds
) {
}
