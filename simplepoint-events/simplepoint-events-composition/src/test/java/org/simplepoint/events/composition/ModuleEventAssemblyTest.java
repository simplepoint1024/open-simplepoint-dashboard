package org.simplepoint.events.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.events.fixture.one.OneModule;
import org.simplepoint.events.fixture.two.TwoModule;
import org.simplepoint.events.jdbc.EventDispatcher;
import org.simplepoint.events.rabbit.EventInfrastructureConfiguration;
import org.simplepoint.events.rabbit.EventWorkers;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ModuleEventAssemblyTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ModuleEventAutoConfiguration.class, EventInfrastructureConfiguration.class))
          .withUserConfiguration(Storage.class)
          .withBean(
              ModulePlan.class,
              () ->
                  new ModuleCatalog(List.of(OneModule.class, TwoModule.class))
                      .plan(List.of("one", "two")))
          .withPropertyValues(
              "simplepoint.events.enabled=true",
              "simplepoint.events.service=physical-host",
              "simplepoint.events.modules.one.password=test-only-one",
              "simplepoint.events.modules.two.password=test-only-two");

  @Test
  void factoryInjectionBindsDistinctPublishersAndPreservesRollbackOnSharedStorage() {
    complete()
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(EventWorkers.class);
              assertThat(context.containsBean("platformEventStore")).isFalse();
              var registry = context.getBean(ModuleEventRegistry.class);
              var one = context.getBean("onePublisher", FixturePublisher.class);
              var two = context.getBean("twoPublisher", FixturePublisher.class);
              assertThat(one.publisher()).isNotSameAs(two.publisher());
              assertThat(registry.channels().get("one").connection())
                  .isNotSameAs(registry.channels().get("two").connection());
              assertThat(registry.channels().get("one").connection().getUsername())
                  .isEqualTo("legacy-one");
              var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
              var jdbc = context.getBean(JdbcTemplate.class);
              tx.executeWithoutResult(
                  status -> {
                    jdbc.update("insert into effects values ('business', 'committed')");
                    one.publish();
                  });
              assertThatThrownBy(
                      () ->
                          tx.executeWithoutResult(
                              status -> {
                                jdbc.update(
                                    "insert into effects values ('business', 'rolled-back')");
                                two.publish();
                                throw new IllegalStateException("rollback");
                              }))
                  .isInstanceOf(IllegalStateException.class);
              assertThat(registry.channels().get("one").store().count("OUTBOX", "PENDING"))
                  .isEqualTo(1);
              assertThat(registry.channels().get("two").store().count("OUTBOX", "PENDING"))
                  .isZero();
              assertThat(jdbc.queryForObject("select count(*) from effects", Integer.class))
                  .isEqualTo(1);
              assertThatThrownBy(one::publish)
                  .hasMessageContaining("writable business transaction");
              assertThatThrownBy(
                      () ->
                          tx.executeWithoutResult(
                              status ->
                                  one.publisher()
                                      .publish(
                                          FixturePublisher.event("legacy-two", "fixture.two"))))
                  .hasMessageContaining("UNDECLARED_MODULE_EVENT_PUBLICATION");
              assertThatThrownBy(
                      () ->
                          tx.executeWithoutResult(
                              status ->
                                  one.publisher()
                                      .publish(FixturePublisher.event("legacy-one", "undeclared"))))
                  .hasMessageContaining("UNDECLARED_MODULE_EVENT_PUBLICATION");
              assertThatThrownBy(() -> context.getBean(EventPublisher.class))
                  .hasStackTraceContaining("InjectionPoint");
            });
  }

  @Test
  void inboxBusinessEffectAndModuleCompletionOutboxCommitTogetherAfterRetry() {
    complete()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var registry = context.getBean(ModuleEventRegistry.class);
              final var one = registry.channels().get("one").store();
              var two = registry.channels().get("two").store();
              var event = FixturePublisher.event("legacy-one", "fixture.one");
              two.receive(TwoModule.SUBSCRIPTION, event);
              two.receive(TwoModule.SUBSCRIPTION, event);
              var delegate = context.getBean("twoHandler", EventHandler.class);
              var fail = new AtomicBoolean(true);
              EventHandler handler =
                  new EventHandler() {
                    public EventSubscription subscription() {
                      return delegate.subscription();
                    }

                    public void handle(EventMessage value) {
                      delegate.handle(value);
                      if (fail.get()) {
                        throw new IllegalStateException("after local effect and outbox");
                      }
                    }
                  };
              var dispatcher = new EventDispatcher(two, value -> {}, List.of(handler));
              assertThat(dispatcher.dispatch("INBOX", 10)).isEqualTo(1);
              var jdbc = context.getBean(JdbcTemplate.class);
              assertThat(jdbc.queryForObject("select count(*) from effects", Integer.class))
                  .isZero();
              assertThat(two.count("OUTBOX", "PENDING")).isZero();
              assertThat(two.count("INBOX", "PENDING")).isEqualTo(1);
              jdbc.update("update sp_event_jobs_v1 set next_at=0");
              fail.set(false);
              assertThat(dispatcher.dispatch("INBOX", 10)).isEqualTo(1);
              assertThat(two.count("INBOX", "DONE")).isEqualTo(1);
              assertThat(two.count("OUTBOX", "PENDING")).isEqualTo(1);
              assertThat(one.count("OUTBOX", "PENDING")).isZero();
              assertThat(jdbc.queryForObject("select count(*) from effects", Integer.class))
                  .isEqualTo(1);
            });
  }

  @Test
  void missingCredentialsAreRejectedInsteadOfUsingTheHostPassword() {
    complete()
        .withPropertyValues(
            "simplepoint.events.modules.two.password=", "spring.rabbitmq.password=must-not-be-used")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("MISSING_MODULE_EVENT_CREDENTIAL"));
  }

  @Test
  void missingHandlersAndUnknownModuleBindingsFailBeforeWorkersStart() {
    runner.run(
        context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("MISSING_MODULE_EVENT_HANDLER"));
    complete()
        .withPropertyValues("simplepoint.events.modules.typo.password=unused")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("UNKNOWN_EVENT_MODULE_BINDING"));
  }

  @Test
  void unownedPublisherAndHandlerFactoriesCannotBorrowModuleIdentity() {
    complete()
        .withUserConfiguration(UnownedPublisher.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("UNOWNED_EVENT_PUBLISHER"));
    complete()
        .withUserConfiguration(UnownedHandler.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("UNOWNED_MODULE_EVENT_HANDLER"));
  }

  @Test
  void storageAndTransactionManagerMustMatchBeforeAnyWorkerIsCreated() {
    complete()
        .withBean("unrelatedDataSource", DataSource.class, Storage::source)
        .withPropertyValues(
            "simplepoint.events.modules.one.data-source=unrelatedDataSource",
            "simplepoint.events.modules.one.transaction-manager=transactionManager")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("EVENT_STORAGE_TRANSACTION_MISMATCH"));
  }

  @Test
  void explicitModuleStorageBindingRejectsPublicationFromTheOtherDatabaseTransaction() {
    var other = Storage.source();
    var otherManager = new DataSourceTransactionManager(other);
    complete()
        .withBean("otherDataSource", DataSource.class, () -> other)
        .withBean("otherManager", PlatformTransactionManager.class, () -> otherManager)
        .withPropertyValues(
            "simplepoint.events.modules.one.data-source=dataSource",
            "simplepoint.events.modules.one.transaction-manager=transactionManager",
            "simplepoint.events.modules.two.data-source=otherDataSource",
            "simplepoint.events.modules.two.transaction-manager=otherManager")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var two = context.getBean("twoPublisher", FixturePublisher.class);
              new TransactionTemplate(otherManager).executeWithoutResult(status -> two.publish());
              var first = context.getBean("transactionManager", PlatformTransactionManager.class);
              assertThatThrownBy(
                      () ->
                          new TransactionTemplate(first)
                              .executeWithoutResult(status -> two.publish()))
                  .hasMessageContaining("must share a transaction");
              assertThat(
                      new JdbcTemplate(other)
                          .queryForObject(
                              "select count(*) from sp_event_jobs_v1 where direction='OUTBOX'",
                              Integer.class))
                  .isEqualTo(1);
            });
  }

  @Test
  void persistenceBindingsAutomaticallySelectEventStorageAndRejectForeignOverrides() {
    var other = Storage.source();
    var manager = new DataSourceTransactionManager(other);
    var configured =
        complete()
            .withBean("otherDataSource", DataSource.class, () -> other)
            .withBean("otherManager", PlatformTransactionManager.class, () -> manager)
            .withBean(
                ModuleStorageBindings.class,
                () ->
                    new ModuleStorageBindings(
                        Map.of(
                            "one", binding("default", "dataSource", "transactionManager"),
                            "two", binding("second", "otherDataSource", "otherManager"))));
    configured.run(
        context -> {
          assertThat(context).hasNotFailed();
          var two = context.getBean("twoPublisher", FixturePublisher.class);
          new TransactionTemplate(manager).executeWithoutResult(status -> two.publish());
          assertThat(
                  context
                      .getBean(ModuleEventRegistry.class)
                      .channels()
                      .get("two")
                      .store()
                      .count("OUTBOX", "PENDING"))
              .isEqualTo(1);
          assertThat(
                  context
                      .getBean(JdbcTemplate.class)
                      .queryForObject("select count(*) from sp_event_jobs_v1", Integer.class))
              .isZero();
          var first = context.getBean("transactionManager", PlatformTransactionManager.class);
          assertThatThrownBy(
                  () ->
                      new TransactionTemplate(first).executeWithoutResult(status -> two.publish()))
              .hasMessageContaining("must share a transaction");
        });
    configured
        .withPropertyValues("simplepoint.events.modules.two.data-source=dataSource")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("FOREIGN_MODULE_EVENT_STORAGE"));
    configured
        .withPropertyValues("simplepoint.events.modules.two.transaction-manager=transactionManager")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("FOREIGN_MODULE_EVENT_STORAGE"));
  }

  private static ModuleStorageBindings.Binding binding(
      String store, String source, String manager) {
    return new ModuleStorageBindings.Binding(
        store, source, store + "Emf", store + "Em", manager, store + "Jdbc");
  }

  private ApplicationContextRunner complete() {
    return runner.withUserConfiguration(OneModule.class, TwoModule.class);
  }

  @Configuration(proxyBeanMethods = false)
  static class Storage {
    @Bean
    DataSource dataSource() {
      return source();
    }

    static DataSource source() {
      var data =
          new DriverManagerDataSource(
              "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      new ResourceDatabasePopulator(
              new ClassPathResource("META-INF/simplepoint/events/schema-v1.sql"))
          .execute(data);
      new JdbcTemplate(data)
          .execute("create table effects (owner varchar(64), event_id varchar(64))");
      return data;
    }

    @Bean
    PlatformTransactionManager transactionManager(@Qualifier("dataSource") DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
      return new JdbcTemplate(dataSource);
    }

    @Bean
    ModuleBrokerConnections connections() {
      return (identity, password) -> {
        var connection = new CachingConnectionFactory("127.0.0.1", 9);
        connection.setUsername(identity);
        connection.setPassword(password);
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        return connection;
      };
    }

    @Bean
    DefaultLifecycleProcessor lifecycleProcessor() {
      return new DefaultLifecycleProcessor() {
        @Override
        public void onRefresh() {
          // This database/assembly fixture manually dispatches jobs without connecting Rabbit.
        }
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class UnownedPublisher {
    @Bean
    Object unauthorizedPublisher(EventPublisher publisher) {
      return new Object();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class UnownedHandler {
    @Bean
    EventHandler unauthorizedHandler() {
      return new EventHandler() {
        public EventSubscription subscription() {
          return OneModule.SUBSCRIPTION;
        }

        public void handle(EventMessage event) {}
      };
    }
  }
}
