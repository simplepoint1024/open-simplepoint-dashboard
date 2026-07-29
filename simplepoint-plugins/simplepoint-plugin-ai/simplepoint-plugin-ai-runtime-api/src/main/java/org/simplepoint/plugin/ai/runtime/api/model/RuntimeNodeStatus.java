package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * Authoritative control-plane state of one OCI runtime node.
 */
public enum RuntimeNodeStatus {
  /**
   * The node is establishing its current process generation.
   */
  REGISTERING,

  /**
   * The node is healthy and eligible for scheduling.
   */
  READY,

  /**
   * The node remains alive but must not receive new workloads.
   */
  DRAINING,

  /**
   * The node reported an engine or runtime failure.
   */
  ERROR,

  /**
   * The heartbeat lease expired or the node shut down.
   */
  OFFLINE
}
