package org.simplepoint.agent.runtime;

import org.simplepoint.boot.starter.Boot;
import org.simplepoint.data.jpa.base.repository.EnableRepository;

/**
 * Independent, horizontally scalable Agent execution runtime.
 */
@Boot
@EnableRepository
public class AgentRuntimeApplication {

  /**
   * Starts the Agent Runtime worker process.
   *
   * @param args application arguments
   */
  public static void main(final String[] args) {
    org.simplepoint.boot.starter.Application.run(
        AgentRuntimeApplication.class,
        args
    );
  }
}
