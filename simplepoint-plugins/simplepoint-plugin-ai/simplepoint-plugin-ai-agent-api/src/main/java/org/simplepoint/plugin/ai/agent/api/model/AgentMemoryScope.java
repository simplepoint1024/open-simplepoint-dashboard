package org.simplepoint.plugin.ai.agent.api.model;

/**
 * Isolation boundary for reusable Agent memories.
 */
public enum AgentMemoryScope {

  /**
   * Memories are shared only across executions by the same authenticated subject.
   */
  SUBJECT
}
