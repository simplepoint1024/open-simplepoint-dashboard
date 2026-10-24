package org.simplepoint.plugin.workflow.config;

import org.flowable.spring.SpringProcessEngineConfiguration;

/** Configures the owned workflow engine without depending on a host's Boot auto-configuration. */
@FunctionalInterface
public interface WorkflowEngineCustomizer {
  /**
   * Applies trusted engine policy before the engine starts. Storage and safety are verified last.
   */
  void configure(SpringProcessEngineConfiguration configuration);
}
