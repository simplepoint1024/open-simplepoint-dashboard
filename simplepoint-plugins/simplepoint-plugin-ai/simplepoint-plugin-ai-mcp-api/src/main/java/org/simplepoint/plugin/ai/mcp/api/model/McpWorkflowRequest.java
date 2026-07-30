package org.simplepoint.plugin.ai.mcp.api.model;

import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Common trusted context for one immutable Skill-to-MCP operation.
 */
public interface McpWorkflowRequest {

  /**
   * Returns the scope in which the Skill execution was submitted.
   */
  AiResourceScope invocationScope();

  /**
   * Returns the tenant identifier for a tenant-scoped invocation.
   */
  String invocationTenantId();

  /**
   * Returns the exact MCP Server identifier.
   */
  String serverId();

  /**
   * Returns the immutable MCP capability snapshot identifier.
   */
  String snapshotId();

  /**
   * Returns the Skill definition identifier.
   */
  String skillId();

  /**
   * Returns the immutable Skill version identifier.
   */
  String skillVersionId();

  /**
   * Returns the durable Skill execution identifier.
   */
  String executionId();

  /**
   * Returns the declarative workflow step identifier.
   */
  String stepId();

  /**
   * Returns the authenticated subject that submitted the execution.
   */
  String subjectId();

  /**
   * Returns the short-lived, single-use capability token.
   */
  String capabilityToken();
}
