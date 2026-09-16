package org.simplepoint.plugin.ai.runtime.api.service;

import org.simplepoint.plugin.ai.runtime.api.model.RuntimeImageObservation;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProbeReport;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadDispatchRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadObservation;

/**
 * Fenced control-plane operations against one independent Tool Runtime node.
 */
public interface AiRuntimeNodeOperations {

  /**
   * Pulls and verifies one content-addressed image without creating a workload.
   */
  RuntimeImageObservation prepare(String advertiseUrl, String image);

  /**
   * Starts a disposable workload and verifies its MCP protocol capabilities.
   */
  RuntimeMcpProbeReport probe(
      String advertiseUrl,
      RuntimeWorkloadDispatchRequest request
  );

  /**
   * Creates or idempotently observes one fenced workload.
   */
  RuntimeWorkloadObservation start(
      String advertiseUrl,
      RuntimeWorkloadDispatchRequest request
  );

  /**
   * Observes one workload only when the lease fence still matches.
   */
  RuntimeWorkloadObservation status(
      String advertiseUrl,
      String workloadId,
      String leaseId,
      long fencingToken
  );

  /**
   * Stops one workload only when the lease fence still matches.
   */
  RuntimeWorkloadObservation stop(
      String advertiseUrl,
      String workloadId,
      String leaseId,
      long fencingToken
  );

  /**
   * Deletes one stopped workload only when the lease fence still matches.
   */
  void delete(
      String advertiseUrl,
      String workloadId,
      String leaseId,
      long fencingToken
  );
}
