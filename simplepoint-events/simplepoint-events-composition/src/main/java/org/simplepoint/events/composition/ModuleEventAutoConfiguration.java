package org.simplepoint.events.composition;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.rabbit.EventInfrastructureConfiguration;
import org.simplepoint.events.rabbit.EventInfrastructureProperties;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.amqp.autoconfigure.RabbitConnectionDetails;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.Environment;

/** Automatically assembles selected module channels instead of a host-wide event publisher. */
@AutoConfiguration(
    after = RabbitAutoConfiguration.class,
    before = EventInfrastructureConfiguration.class)
@ConditionalOnBean(ModulePlan.class)
@EnableConfigurationProperties({ModuleEventProperties.class, EventInfrastructureProperties.class})
public class ModuleEventAutoConfiguration {
  @Bean
  ModuleEventPlan moduleEventPlan(ModulePlan modules, Environment environment) {
    var plan = ModuleEventPlan.from(modules);
    if (!plan.channels().isEmpty()
        && !environment.getProperty("simplepoint.events.enabled", Boolean.class, true)) {
      throw new ModuleCompositionException("MODULE_EVENTS_REQUIRED", "");
    }
    return plan;
  }

  @Bean
  @ConditionalOnMissingBean(ModuleBrokerConnections.class)
  ModuleBrokerConnections moduleBrokerConnections(
      ConnectionFactory base, RabbitProperties properties, RabbitConnectionDetails details) {
    return new DefaultModuleBrokerConnections(base, properties, details);
  }

  @Bean
  ModuleEventRegistry moduleEventRegistry(
      ModulePlan modules,
      ModuleEventPlan plan,
      ModuleEventProperties bindings,
      EventInfrastructureProperties properties,
      ConfigurableListableBeanFactory beans,
      ModuleBrokerConnections connections) {
    return new ModuleEventRegistry(modules, plan, bindings, properties, beans, connections);
  }

  @Bean
  @Scope("prototype")
  EventPublisher moduleEventPublisher(InjectionPoint point, ModuleEventRegistry registry) {
    return registry.publisher(point);
  }

  @Bean
  ModuleEventRuntime moduleEventRuntime(
      ModulePlan modules,
      ModuleEventRegistry registry,
      ConfigurableListableBeanFactory beans,
      EventInfrastructureProperties properties) {
    return new ModuleEventRuntime(modules, registry, beans, properties);
  }

  @Bean
  MeterBinder moduleEventMetrics(ModuleEventRegistry registry) {
    return meters ->
        registry
            .channels()
            .values()
            .forEach(
                channel -> {
                  for (String direction : List.of("INBOX", "OUTBOX")) {
                    for (String state : List.of("PENDING", "PROCESSING", "DEAD")) {
                      Gauge.builder(
                              "simplepoint.events.jobs",
                              channel.store(),
                              store -> store.count(direction, state))
                          .tag("module", channel.manifest().module())
                          .tag("owner", channel.manifest().identity())
                          .tag("direction", direction)
                          .tag("state", state)
                          .register(meters);
                    }
                  }
                });
  }

  @Bean
  HealthIndicator moduleEventTransportHealthIndicator(ModuleEventRegistry registry) {
    return () -> {
      var health = Health.up();
      for (var channel : registry.channels().values()) {
        try (var connection = channel.connection().createConnection()) {
          if (!connection.isOpen()) {
            throw new IllegalStateException("Event connection unavailable");
          }
          health.withDetail(channel.manifest().module(), "UP");
        } catch (RuntimeException failure) {
          health.down().withDetail(channel.manifest().module(), "DOWN");
        }
      }
      return health.build();
    };
  }

  @Bean
  HealthIndicator moduleEventStorageHealthIndicator(ModuleEventRegistry registry) {
    return () -> {
      var health = Health.up();
      for (var channel : registry.channels().values()) {
        try {
          channel.store().count("INBOX", "PENDING");
          health.withDetail(channel.manifest().module(), "UP");
        } catch (RuntimeException failure) {
          health.down().withDetail(channel.manifest().module(), "DOWN");
        }
      }
      return health.build();
    };
  }
}
