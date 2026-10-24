package org.simplepoint.events.composition;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.jdbc.EventCodec;
import org.simplepoint.events.jdbc.EventRetryPolicy;
import org.simplepoint.events.jdbc.JdbcEventStore;
import org.simplepoint.events.rabbit.EventInfrastructureProperties;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates distinct, immutable module channels without loading event handlers or opening Rabbit. */
public final class ModuleEventRegistry implements DisposableBean {
  private final ModulePlan modules;
  private final Map<String, Channel> channels = new LinkedHashMap<>();

  /** Validates all declarations and required secrets before creating owned infrastructure. */
  public ModuleEventRegistry(
      ModulePlan modules,
      ModuleEventPlan plan,
      ModuleEventProperties bindings,
      EventInfrastructureProperties workers,
      ConfigurableListableBeanFactory beans,
      ModuleBrokerConnections connections) {
    this.modules = modules;
    if (workers.isDeclareTopology()) {
      throw new ModuleCompositionException("MODULE_EVENT_TOPOLOGY_REQUIRES_PROVISIONING", "");
    }
    validateWorkers(workers);
    for (String configured : bindings.getModules().keySet()) {
      if (!plan.channels().containsKey(configured)) {
        throw new ModuleCompositionException("UNKNOWN_EVENT_MODULE_BINDING", configured);
      }
    }
    for (String module : plan.channels().keySet()) {
      var binding = bindings.getModules().get(module);
      if (binding == null || binding.getPassword() == null || binding.getPassword().isBlank()) {
        throw new ModuleCompositionException("MISSING_MODULE_EVENT_CREDENTIAL", module);
      }
    }
    var storageBindings = beans.getBeanProvider(ModuleStorageBindings.class).getIfAvailable();
    try {
      for (var manifest : plan.channels().values()) {
        var binding = bindings.getModules().get(manifest.module());
        var owned = storageBindings == null ? null : storageBindings.require(manifest.module());
        var source =
            bean(
                beans,
                owned == null ? binding.getDataSource() : owned.dataSource(),
                DataSource.class);
        var manager =
            bean(
                beans,
                owned == null ? binding.getTransactionManager() : owned.transactionManager(),
                PlatformTransactionManager.class);
        if (owned != null
            && ((binding.getDataSource() != null
                    && !binding.getDataSource().isBlank()
                    && bean(beans, binding.getDataSource(), DataSource.class) != source)
                || (binding.getTransactionManager() != null
                    && !binding.getTransactionManager().isBlank()
                    && bean(
                            beans,
                            binding.getTransactionManager(),
                            PlatformTransactionManager.class)
                        != manager))) {
          throw new ModuleCompositionException("FOREIGN_MODULE_EVENT_STORAGE", manifest.module());
        }
        verifyTransactionBinding(source, manager);
        var store =
            new JdbcEventStore(
                source,
                manager,
                new EventCodec(),
                manifest.identity(),
                Clock.systemUTC(),
                new EventRetryPolicy(
                    workers.getMaxAttempts(),
                    workers.getLease(),
                    workers.getInitialRetryDelay(),
                    workers.getMaxRetryDelay()));
        store.count("INBOX", "PENDING");
        var connection = connections.create(manifest.identity(), binding.getPassword());
        if (channels.values().stream().anyMatch(channel -> channel.connection() == connection)) {
          throw new ModuleCompositionException("SHARED_MODULE_EVENT_CONNECTION", manifest.module());
        }
        if (!manifest.identity().equals(connection.getUsername())
            || !connection.isPublisherConfirms()
            || !connection.isPublisherReturns()) {
          connection.destroy();
          throw new ModuleCompositionException(
              "INVALID_MODULE_EVENT_CONNECTION", manifest.module());
        }
        channels.put(manifest.module(), new Channel(manifest, store, connection));
      }
    } catch (RuntimeException failure) {
      destroy();
      throw failure;
    }
  }

  EventPublisher publisher(InjectionPoint point) {
    Class<?> source =
        point.getMethodParameter() == null
            ? point.getMember().getDeclaringClass()
            : point.getMethodParameter().getContainingClass();
    var owner =
        modules
            .ownerOf(source)
            .filter(module -> module.configuration().getClassLoader() == source.getClassLoader())
            .orElseThrow(
                () -> new ModuleCompositionException("UNOWNED_EVENT_PUBLISHER", source.getName()));
    var channel = channels.get(owner.id());
    if (channel == null || channel.manifest().publications().isEmpty()) {
      throw new ModuleCompositionException("UNDECLARED_MODULE_EVENT_PUBLISHER", owner.id());
    }
    return event -> {
      if (!channel.manifest().identity().equals(event.producer())
          || channel.manifest().publications().stream().noneMatch(item -> item.accepts(event))) {
        throw new ModuleCompositionException("UNDECLARED_MODULE_EVENT_PUBLICATION", owner.id());
      }
      channel.store().publish(event);
    };
  }

  Map<String, Channel> channels() {
    return java.util.Collections.unmodifiableMap(channels);
  }

  @Override
  public void destroy() {
    channels.values().forEach(channel -> channel.connection().destroy());
    channels.clear();
  }

  private static <T> T bean(ConfigurableListableBeanFactory beans, String name, Class<T> type) {
    return name == null || name.isBlank() ? beans.getBean(type) : beans.getBean(name, type);
  }

  private static void verifyTransactionBinding(
      DataSource source, PlatformTransactionManager manager) {
    var transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.executeWithoutResult(
        status -> {
          var connection = DataSourceUtils.getConnection(source);
          try {
            if (!DataSourceUtils.isConnectionTransactional(connection, source)
                || connection.getAutoCommit()) {
              throw new ModuleCompositionException("EVENT_STORAGE_TRANSACTION_MISMATCH", "");
            }
          } catch (java.sql.SQLException ex) {
            throw new ModuleCompositionException("EVENT_STORAGE_TRANSACTION_UNAVAILABLE", "");
          } finally {
            DataSourceUtils.releaseConnection(connection, source);
            status.setRollbackOnly();
          }
        });
  }

  private static void validateWorkers(EventInfrastructureProperties properties) {
    if (properties.getBatchSize() < 1
        || properties.getBatchSize() > 100
        || properties.getDispatchDelay().toMillis() < 100
        || properties.getConfirmTimeout().toMillis() < 100
        || properties.getConfirmTimeout().toSeconds() > 30
        || properties.getLease().compareTo(properties.getConfirmTimeout().multipliedBy(2)) < 0) {
      throw new IllegalArgumentException("Invalid event worker bounds");
    }
  }

  record Channel(
      ModuleEventPlan.Channel manifest,
      JdbcEventStore store,
      CachingConnectionFactory connection) {}
}
