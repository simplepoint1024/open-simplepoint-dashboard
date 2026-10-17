package org.simplepoint.plugin.workflow.config;

import java.util.ArrayList;
import java.util.Map;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.simplepoint.plugin.workflow.service.WorkflowActivityBehaviorFactory;
import org.simplepoint.plugin.workflow.service.WorkflowEngineSignalListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Limits tenant-authored expressions to process variables, not the application's Spring beans. */
@Configuration(proxyBeanMethods = false)
public class WorkflowEngineConfiguration {

  /**
   * Flowable's Spring integration exposes all beans when this map is null; an empty map exposes
   * none.
   */
  @Bean
  public EngineConfigurationConfigurer<SpringProcessEngineConfiguration>
      workflowExpressionIsolation() {
    return configuration -> {
      configuration.setBeans(Map.of());
      var expressions = configuration.getExpressionManager();
      if (expressions == null) {
        expressions = new org.flowable.engine.impl.el.ProcessExpressionManager(Map.of());
        configuration.setExpressionManager(expressions);
      }
      if (!(expressions
          instanceof org.flowable.common.engine.impl.el.DefaultExpressionManager standard)) {
        throw new IllegalStateException("Workflow requires the platform exact expression factory");
      }
      standard.setExpressionFactory(
          new org.simplepoint.plugin.workflow.service.ExactWorkflowExpressionFactory());
      if (configuration.getActivityBehaviorFactory() != null
          && !(configuration.getActivityBehaviorFactory()
              instanceof WorkflowActivityBehaviorFactory)) {
        throw new IllegalStateException(
            "Workflow execution limits require the platform behavior factory");
      }
      configuration.setActivityBehaviorFactory(new WorkflowActivityBehaviorFactory());
    };
  }

  /**
   * Preserve other trusted engine listeners while installing the transactional notification bridge.
   */
  @Bean
  public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> workflowEngineSignals(
      WorkflowEngineSignalListener listener) {
    return configuration -> {
      var listeners =
          configuration.getEventListeners() == null
              ? new ArrayList<org.flowable.common.engine.api.delegate.event.FlowableEventListener>()
              : new ArrayList<>(configuration.getEventListeners());
      listeners.add(listener);
      configuration.setEventListeners(listeners);
    };
  }
}
