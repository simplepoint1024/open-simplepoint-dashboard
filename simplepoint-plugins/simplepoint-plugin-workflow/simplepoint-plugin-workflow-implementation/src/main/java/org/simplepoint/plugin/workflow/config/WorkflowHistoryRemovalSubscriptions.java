package org.simplepoint.plugin.workflow.config;

import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.plugin.workflow.api.event.WorkflowHistoryRemovalEvents;
import org.simplepoint.plugin.workflow.service.WorkflowHistoryRemovalService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Subscriptions for the owners of submission history and personal message copies. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "simplepoint.events", name = "enabled", havingValue = "true")
public class WorkflowHistoryRemovalSubscriptions {
  @Bean
  EventHandler appWorkflowHistoryRemoved(WorkflowHistoryRemovalService service) {
    return acknowledgement("app", service);
  }

  @Bean
  EventHandler personalWorkflowHistoryRemoved(WorkflowHistoryRemovalService service) {
    return acknowledgement("personal", service);
  }

  private static EventHandler acknowledgement(String owner, WorkflowHistoryRemovalService service) {
    return new EventHandler() {
      @Override
      public EventSubscription subscription() {
        return WorkflowHistoryRemovalEvents.acknowledgementSubscription(owner);
      }

      @Override
      public void handle(EventMessage event) {
        service.completed(event);
      }
    };
  }
}
