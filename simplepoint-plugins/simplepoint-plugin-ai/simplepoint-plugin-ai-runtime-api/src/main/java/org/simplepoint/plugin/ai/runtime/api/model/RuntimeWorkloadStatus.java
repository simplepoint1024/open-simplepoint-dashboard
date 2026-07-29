package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * Persisted lifecycle state of one scheduled OCI MCP workload.
 */
public enum RuntimeWorkloadStatus {
  /**
   * Waiting for a scheduler decision.
   */
  PENDING,

  /**
   * Bound to a node and protected by an active lease.
   */
  ASSIGNED,

  /**
   * The selected runtime node is creating the container.
   */
  STARTING,

  /**
   * The workload is running.
   */
  RUNNING,

  /**
   * A stop request is in progress.
   */
  STOPPING,

  /**
   * The workload completed successfully.
   */
  SUCCEEDED,

  /**
   * The workload failed.
   */
  FAILED,

  /**
   * The workload lost its node or lease.
   */
  LOST,

  /**
   * The workload was cancelled before successful completion.
   */
  CANCELLED
}
