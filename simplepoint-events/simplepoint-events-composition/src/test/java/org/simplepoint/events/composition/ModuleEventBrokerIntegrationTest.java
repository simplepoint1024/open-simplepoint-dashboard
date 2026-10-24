package org.simplepoint.events.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.simplepoint.events.fixture.one.OneModule;
import org.simplepoint.events.fixture.two.TwoModule;
import org.simplepoint.events.jdbc.EventCodec;
import org.simplepoint.events.rabbit.RabbitEventTransport;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitConnectionDetails;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses a disposable, preprovisioned broker with two independent users and strict ACLs. */
@EnabledIfEnvironmentVariable(named = "SP_MODULE_EVENT_RABBIT_PORT", matches = "[0-9]+")
class ModuleEventBrokerIntegrationTest {
  @Test
  void realChannelsExchangeDurableFactsAndKeepBusinessWritesAvailableDuringTransportOutage() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ModuleEventAutoConfiguration.class))
        .withUserConfiguration(Infrastructure.class, OneModule.class, TwoModule.class)
        .withBean(
            ModulePlan.class,
            () ->
                new ModuleCatalog(List.of(OneModule.class, TwoModule.class))
                    .plan(List.of("one", "two")))
        .withPropertyValues(
            "simplepoint.events.modules.one.password="
                + System.getenv("SP_MODULE_EVENT_ONE_PASSWORD"),
            "simplepoint.events.modules.two.password="
                + System.getenv("SP_MODULE_EVENT_TWO_PASSWORD"),
            "simplepoint.events.dispatch-delay=100ms",
            "simplepoint.events.initial-retry-delay=100ms",
            "simplepoint.events.confirm-timeout=1s",
            "simplepoint.events.lease=5s",
            "simplepoint.events.max-attempts=100")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var runtime = context.getBean(ModuleEventRuntime.class);
              assertThat(runtime.isRunning()).isTrue();
              var channels = context.getBean(ModuleEventRegistry.class).channels();
              var one = channels.get("one");
              var two = channels.get("two");
              var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
              var publisher = context.getBean("onePublisher", FixturePublisher.class);
              tx.executeWithoutResult(status -> publisher.publish());
              await(
                  () ->
                      one.store().count("INBOX", "DONE") == 1
                          && two.store().count("INBOX", "DONE") == 1
                          && one.store().count("OUTBOX", "DONE") == 1
                          && two.store().count("OUTBOX", "DONE") == 1);
              assertThat(
                      context
                          .getBean(JdbcTemplate.class)
                          .queryForObject("select count(*) from effects", Integer.class))
                  .isEqualTo(2);
              assertThat(one.store().count("OUTBOX", "DONE")).isEqualTo(1);
              assertThat(two.store().count("OUTBOX", "DONE")).isEqualTo(1);

              var template = new RabbitTemplate(one.connection());
              assertThatThrownBy(() -> template.receive("sp.events.legacy-two.two.receive"))
                  .isInstanceOf(org.springframework.amqp.AmqpException.class);
              var transport =
                  new RabbitEventTransport(template, new EventCodec(), Duration.ofSeconds(1));
              assertThatThrownBy(
                      () -> transport.send(FixturePublisher.event("legacy-two", "fixture.two")))
                  .isInstanceOf(RuntimeException.class);

              one.connection().setAddresses("127.0.0.1:9");
              one.connection().resetConnection();
              tx.executeWithoutResult(status -> publisher.publish());
              var storage =
                  context.getBean("moduleEventStorageHealthIndicator", HealthIndicator.class);
              var broker =
                  context.getBean("moduleEventTransportHealthIndicator", HealthIndicator.class);
              assertThat(storage.health().getStatus()).isEqualTo(Status.UP);
              assertThat(broker.health().getStatus()).isEqualTo(Status.DOWN);
              assertThat(
                      context
                          .getBean(JdbcTemplate.class)
                          .queryForObject(
                              "select count(*) from sp_event_jobs_v1 where owner='legacy-one' "
                                  + "and direction='OUTBOX' and state in ('PENDING','PROCESSING')",
                              Long.class))
                  .isEqualTo(1);

              one.connection()
                  .setAddresses("127.0.0.1:" + System.getenv("SP_MODULE_EVENT_RABBIT_PORT"));
              one.connection().resetConnection();
              await(
                  () ->
                      one.store().count("INBOX", "DONE") == 2
                          && two.store().count("INBOX", "DONE") == 2);
              assertThat(one.store().count("OUTBOX", "DEAD")).isZero();
              runtime.stop();
              assertThat(runtime.isRunning()).isFalse();
            });
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  @Configuration(proxyBeanMethods = false)
  static class Infrastructure {
    @Bean
    DataSource dataSource() {
      return ModuleEventAssemblyTest.Storage.source();
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource source) {
      return new DataSourceTransactionManager(source);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource source) {
      return new JdbcTemplate(source);
    }

    @Bean
    CachingConnectionFactory base() {
      var connection =
          new CachingConnectionFactory(
              "127.0.0.1", Integer.parseInt(System.getenv("SP_MODULE_EVENT_RABBIT_PORT")));
      connection.getRabbitConnectionFactory().setConnectionTimeout(1000);
      connection.setVirtualHost("/module-verification");
      return connection;
    }

    @Bean
    ModuleBrokerConnections moduleBrokerConnections(CachingConnectionFactory base) {
      var details =
          new RabbitConnectionDetails() {
            public List<Address> getAddresses() {
              return List.of(
                  new Address(
                      "127.0.0.1", Integer.parseInt(System.getenv("SP_MODULE_EVENT_RABBIT_PORT"))));
            }

            public String getVirtualHost() {
              return "/module-verification";
            }
          };
      return new DefaultModuleBrokerConnections(base, new RabbitProperties(), details);
    }
  }
}
