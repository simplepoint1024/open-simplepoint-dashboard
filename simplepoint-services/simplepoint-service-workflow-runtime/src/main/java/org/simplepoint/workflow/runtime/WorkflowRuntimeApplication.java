package org.simplepoint.workflow.runtime;

import org.simplepoint.boot.starter.Boot;
import org.simplepoint.data.jpa.base.repository.EnableRepository;

/**
 * Independent, horizontally scalable Agent Workflow execution runtime.
 */
@Boot
@EnableRepository
public class WorkflowRuntimeApplication {

  /**
   * Starts the Workflow Runtime worker process.
   *
   * @param args application arguments
   */
  public static void main(final String[] args) {
    org.simplepoint.boot.starter.Application.run(
        WorkflowRuntimeApplication.class,
        args
    );
  }
}
