package org.simplepoint.plugin.ai.runtime.api.model;

import java.util.List;
import java.util.Map;

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
 * @param transport authoritative Runtime Profile transport
 * @param entrypoint OCI entrypoint override
 * @param command OCI command override
 * @param arguments MCP server arguments
 * @param workingDirectory OCI working directory override
 * @param environment non-sensitive configuration environment
 * @param sessionMode workload session sharing strategy
 * @param maxSessions maximum sessions accepted by this workload
 * @param storage platform-resolved storage mounts
 * @param processUserMode controlled workload user selection
 * @param storageInitializationCommand optional first-create storage initializer
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
    List<RuntimeSecretFile> secrets,
    String transport,
    List<String> entrypoint,
    List<String> command,
    List<String> arguments,
    String workingDirectory,
    Map<String, String> environment,
    String sessionMode,
    int maxSessions,
    List<RuntimeStorageMount> storage,
    Integer containerPort,
    String transportPath,
    String sandboxProfile,
    String processUserMode,
    List<String> storageInitializationCommand
) {

  /** Preserves legacy dispatch construction for unbound Runtime Pools. */
  public RuntimeWorkloadDispatchRequest(
      final String workloadId,
      final String leaseId,
      final long fencingToken,
      final String executionId,
      final String tenantId,
      final String image,
      final long memoryBytes,
      final long nanoCpus,
      final long pidsLimit,
      final long timeoutSeconds,
      final String networkMode,
      final List<String> egressAllowlist,
      final List<RuntimeSecretFile> secrets
  ) {
    this(
        workloadId,
        leaseId,
        fencingToken,
        executionId,
        tenantId,
        image,
        memoryBytes,
        nanoCpus,
        pidsLimit,
        timeoutSeconds,
        networkMode,
        egressAllowlist,
        secrets,
        null,
        List.of(),
        List.of(),
        List.of(),
        null,
        Map.of(),
        null,
        1,
        List.of(),
        null,
        null,
        null,
        null,
        List.of()
    );
  }
}
