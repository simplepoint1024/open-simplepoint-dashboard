package org.simplepoint.plugin.ai.runtime.api.model;

import java.util.List;
import java.util.Map;

/**
 * Full node identity and capacity sent when a runtime process starts.
 *
 * @param instanceId unique identifier of this runtime process generation
 * @param displayName human-readable Docker Engine node name
 * @param advertiseUrl scheduler-to-node private API base URL
 * @param runtimeVersion runtime binary version
 * @param engineApiVersion Docker Engine API version
 * @param engineOsType Docker Engine operating-system type
 * @param cpuCores total logical CPU capacity
 * @param memoryBytes total memory capacity
 * @param maxWorkloads node-local workload concurrency limit
 * @param runningWorkloads currently running owned workloads
 * @param cachedImageDigests content digests already present on the node
 * @param maxWorkloadMemoryBytes maximum memory accepted for one workload
 * @param maxWorkloadNanoCpus maximum CPU quota accepted for one workload
 * @param maxWorkloadPidsLimit maximum process limit accepted for one workload
 * @param requireImageDigest whether mutable image references are rejected
 * @param requireMcpLabels whether MCP package labels are required
 * @param allowBridgeNetwork whether bridge networking can be requested
 * @param allowEgressNetwork whether policy-bound egress can be requested
 * @param requireSupplyChainAdmission whether OCI signature, SBOM, and vulnerability
 *     admission is enforced before pull
 * @param seccompEnforced whether the node injects a validated seccomp profile
 * @param seccompProfileHash immutable hash of the injected seccomp profile
 * @param appArmorEnforced whether an explicit AppArmor profile is enforced
 * @param appArmorProfile AppArmor profile name
 * @param labels scheduler-visible node labels
 */
public record RuntimeNodeRegistration(
    String instanceId,
    String displayName,
    String advertiseUrl,
    String runtimeVersion,
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
    boolean requireImageDigest,
    boolean requireMcpLabels,
    boolean allowBridgeNetwork,
    boolean allowEgressNetwork,
    boolean requireSupplyChainAdmission,
    boolean seccompEnforced,
    String seccompProfileHash,
    boolean appArmorEnforced,
    String appArmorProfile,
    Map<String, String> labels
) {

  /**
   * Compatibility constructor for callers that predate environment policy reporting.
   */
  public RuntimeNodeRegistration(
      final String instanceId,
      final String displayName,
      final String advertiseUrl,
      final String runtimeVersion,
      final String engineApiVersion,
      final String engineOsType,
      final int cpuCores,
      final long memoryBytes,
      final int maxWorkloads,
      final int runningWorkloads,
      final List<String> cachedImageDigests,
      final long maxWorkloadMemoryBytes,
      final long maxWorkloadNanoCpus,
      final long maxWorkloadPidsLimit,
      final boolean requireImageDigest,
      final boolean requireMcpLabels,
      final boolean allowBridgeNetwork,
      final boolean allowEgressNetwork,
      final boolean requireSupplyChainAdmission,
      final Map<String, String> labels
  ) {
    this(
        instanceId,
        displayName,
        advertiseUrl,
        runtimeVersion,
        engineApiVersion,
        engineOsType,
        cpuCores,
        memoryBytes,
        maxWorkloads,
        runningWorkloads,
        cachedImageDigests,
        maxWorkloadMemoryBytes,
        maxWorkloadNanoCpus,
        maxWorkloadPidsLimit,
        requireImageDigest,
        requireMcpLabels,
        allowBridgeNetwork,
        allowEgressNetwork,
        requireSupplyChainAdmission,
        false,
        null,
        false,
        null,
        labels
    );
  }
}
