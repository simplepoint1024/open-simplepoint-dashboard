package org.simplepoint.events.composition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.events.jdbc.EventCodec;
import org.simplepoint.events.jdbc.EventDispatcher;
import org.simplepoint.events.rabbit.EventInfrastructureConfiguration;
import org.simplepoint.events.rabbit.EventInfrastructureProperties;
import org.simplepoint.events.rabbit.EventListenerGroup;
import org.simplepoint.events.rabbit.EventWorkers;
import org.simplepoint.events.rabbit.RabbitEventTransport;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.SmartLifecycle;

/** Validates every handler before starting independently authenticated channel workers. */
public final class ModuleEventRuntime implements SmartInitializingSingleton, SmartLifecycle {
  private final ModulePlan modules;
  private final ModuleEventRegistry registry;
  private final ConfigurableListableBeanFactory beans;
  private final EventInfrastructureProperties properties;
  private final List<EventListenerGroup> listeners = new ArrayList<>();
  private final List<EventWorkers> workers = new ArrayList<>();
  private volatile boolean running;
  private boolean prepared;

  /** Separates handler loading from publisher creation to avoid a factory/dependency cycle. */
  public ModuleEventRuntime(
      ModulePlan modules,
      ModuleEventRegistry registry,
      ConfigurableListableBeanFactory beans,
      EventInfrastructureProperties properties) {
    this.modules = modules;
    this.registry = registry;
    this.beans = beans;
    this.properties = properties;
  }

  @Override
  public void afterSingletonsInstantiated() {
    var owned = new LinkedHashMap<String, Map<String, EventHandler>>();
    for (String name : beans.getBeanNamesForType(EventHandler.class, true, true)) {
      Class<?> source = factoryOrComponentType(name);
      var owner =
          modules
              .ownerOf(source)
              .filter(module -> module.configuration().getClassLoader() == source.getClassLoader())
              .orElseThrow(() -> failure("UNOWNED_MODULE_EVENT_HANDLER", name));
      var handler = beans.getBean(name, EventHandler.class);
      var subscription = handler.subscription();
      var channel = registry.channels().get(owner.id());
      if (channel == null || !channel.manifest().subscriptions().contains(subscription)) {
        throw failure("UNDECLARED_MODULE_EVENT_HANDLER", name);
      }
      var handlers = owned.computeIfAbsent(owner.id(), ignored -> new LinkedHashMap<>());
      if (handlers.putIfAbsent(subscription.id(), fixedHandler(subscription, handler)) != null) {
        throw failure("DUPLICATE_MODULE_EVENT_HANDLER", owner.id() + ":" + subscription.id());
      }
    }
    for (var channel : registry.channels().values()) {
      var handlers = owned.getOrDefault(channel.manifest().module(), Map.of());
      for (var subscription : channel.manifest().subscriptions()) {
        if (!handlers.containsKey(subscription.id())) {
          throw failure(
              "MISSING_MODULE_EVENT_HANDLER",
              channel.manifest().module() + ":" + subscription.id());
        }
      }
    }
    for (var channel : registry.channels().values()) {
      var handlers =
          List.copyOf(owned.getOrDefault(channel.manifest().module(), Map.of()).values());
      var codec = new EventCodec();
      var transport =
          new RabbitEventTransport(
              new org.springframework.amqp.rabbit.core.RabbitTemplate(channel.connection()),
              codec,
              properties.getConfirmTimeout());
      var dispatcher = new EventDispatcher(channel.store(), transport, handlers);
      listeners.add(
          EventInfrastructureConfiguration.listeners(
              channel.connection(),
              channel.store(),
              codec,
              handlers,
              channel.manifest().identity(),
              false));
      workers.add(new EventWorkers(dispatcher, properties));
    }
    prepared = true;
  }

  @Override
  public synchronized void start() {
    if (!prepared) {
      throw failure("MODULE_EVENT_RUNTIME_NOT_PREPARED", "");
    }
    if (running) {
      return;
    }
    try {
      listeners.forEach(EventListenerGroup::start);
      workers.forEach(EventWorkers::start);
      running = true;
    } catch (RuntimeException failure) {
      stop();
      throw failure;
    }
  }

  @Override
  public synchronized void stop() {
    listeners.forEach(EventListenerGroup::stop);
    workers.forEach(EventWorkers::stop);
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private Class<?> factoryOrComponentType(String name) {
    if (beans.containsBeanDefinition(name)) {
      var definition = beans.getMergedBeanDefinition(name);
      if (definition.getFactoryBeanName() != null) {
        return AopUtils.getTargetClass(beans.getBean(definition.getFactoryBeanName()));
      }
      if (definition.getFactoryMethodName() != null && definition.getBeanClassName() != null) {
        return org.springframework.util.ClassUtils.resolveClassName(
            definition.getBeanClassName(), beans.getBeanClassLoader());
      }
    }
    return AopUtils.getTargetClass(beans.getBean(name));
  }

  private static EventHandler fixedHandler(EventSubscription subscription, EventHandler handler) {
    return new EventHandler() {
      @Override
      public EventSubscription subscription() {
        return subscription;
      }

      @Override
      public void handle(EventMessage event) {
        handler.handle(event);
      }
    };
  }

  private static ModuleCompositionException failure(String code, String detail) {
    return new ModuleCompositionException(code, detail);
  }
}
