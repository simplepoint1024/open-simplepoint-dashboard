package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * State of a scheduler lease that owns one workload assignment.
 */
public enum RuntimeLeaseStatus {
  /**
   * The lease may still be renewed by its owning scheduler generation.
   */
  ACTIVE,

  /**
   * The owner released the lease normally.
   */
  RELEASED,

  /**
   * The lease reached its deadline without a valid renewal.
   */
  EXPIRED,

  /**
   * A newer fencing token invalidated the former owner.
   */
  FENCED
}
