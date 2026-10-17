package org.simplepoint.plugin.app.form.configuration;

import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.platform.resource.ResourceLifecycleEvents;
import org.simplepoint.plugin.app.form.service.ApplicationResourceLifecycleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fixed required subscriptions survive service restarts and do not depend on Consul presence. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "simplepoint.events", name = "enabled", havingValue = "true")
public class ResourceLifecycleSubscriptions {
  @Bean
  EventHandler authorizationResourceOfflineCompletion(ApplicationResourceLifecycleService service) {
    return completion("common", service);
  }

  @Bean
  EventHandler dataResourceOfflineCompletion(ApplicationResourceLifecycleService service) {
    return completion("dna", service);
  }

  private EventHandler completion(String producer, ApplicationResourceLifecycleService service) {
    return new EventHandler() {
      @Override
      public EventSubscription subscription() {
        return new EventSubscription("resource-offline." + producer, producer,
            ResourceLifecycleEvents.OFFLINE_COMPLETED, 1);
      }

      @Override
      public void handle(EventMessage event) {
        service.completed(event);
      }
    };
  }
}
