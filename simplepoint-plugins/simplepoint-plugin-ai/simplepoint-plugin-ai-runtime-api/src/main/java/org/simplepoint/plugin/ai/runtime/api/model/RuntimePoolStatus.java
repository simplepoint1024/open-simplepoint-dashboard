package org.simplepoint.plugin.ai.runtime.api.model;

/**
 * Desired/observed lifecycle of one reusable OCI MCP runtime pool.
 */
public enum RuntimePoolStatus {
  SCALING,
  READY,
  IDLE,
  ERROR,
  DISABLED
}
