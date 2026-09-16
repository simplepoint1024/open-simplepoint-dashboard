package org.simplepoint.plugin.ai.runtime.service.support;

import java.time.Duration;
import java.util.Optional;

/**
 * Cluster-wide directory for managed MCP session assignments and failed replicas.
 */
public interface ManagedMcpSessionDirectory {

  /**
   * Builds a Redis-safe key from a one-way hash of the external MCP session ID.
   */
  String sessionKey(
      String serverId,
      String scope,
      String tenantId,
      String sessionId
  );

  /**
   * Returns the current assignment when one exists.
   */
  Optional<Assignment> find(String sessionKey);

  /**
   * Atomically creates an assignment or returns the assignment created by a peer.
   */
  Assignment claim(String sessionKey, Assignment candidate);

  /**
   * Atomically claims one workload slot, or returns empty when its declared
   * session capacity is full.
   */
  default Optional<Assignment> claimAvailable(
      final String sessionKey,
      final Assignment candidate,
      final int maximumSessions
  ) {
    if (maximumSessions <= 0) {
      throw new IllegalArgumentException("MCP session capacity is invalid");
    }
    return Optional.of(claim(sessionKey, candidate));
  }

  /**
   * Extends the TTL only while the stored assignment still matches.
   */
  void touch(String sessionKey, Assignment assignment);

  /**
   * Deletes an assignment only while it still matches.
   */
  void invalidate(String sessionKey, Assignment assignment);

  /**
   * Deletes every session assignment still owned by one fenced workload.
   */
  void invalidateWorkload(Assignment assignment);

  /**
   * Temporarily removes a failed replica from new selection decisions.
   */
  void quarantine(String serverId, Assignment assignment);

  /**
   * Returns whether a replica is still inside its failure quarantine window.
   */
  boolean isQuarantined(String serverId, Assignment assignment);

  /**
   * Returns the validated assignment TTL.
   */
  Duration assignmentTtl();

  /**
   * Fenced managed Runtime assignment stored without an external session ID.
   */
  record Assignment(
      String workloadId,
      String leaseId,
      long fencingToken
  ) {
  }
}
