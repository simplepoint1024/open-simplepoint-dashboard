package org.simplepoint.events.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventPublisher;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

class EventInfrastructureConfigurationTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(EventInfrastructureConfiguration.class))
      .withUserConfiguration(Database.class);

  @Test
  void explicitInfrastructureStartsWithPublisherOnlyAndStopsItsWorkers() {
    runner.withPropertyValues("simplepoint.events.enabled=true", "simplepoint.events.service=app")
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(EventPublisher.class);
          assertThat(context.getBean(EventWorkers.class).isRunning()).isTrue();
        });
  }

  @Test
  void disabledInfrastructureDoesNotStartWorkers() {
    runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(EventWorkers.class));
  }

  @Test
  void invalidPublisherIdentityFailsAtStartup() {
    runner.withPropertyValues("simplepoint.events.enabled=true", "simplepoint.events.service=other")
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  static class Database {
    @Bean
    DataSource dataSource() {
      var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      new ResourceDatabasePopulator(new ClassPathResource("META-INF/simplepoint/events/schema-v1.sql"))
          .execute(source);
      return source;
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    CachingConnectionFactory connectionFactory() {
      var connection = new CachingConnectionFactory("127.0.0.1", 35672);
      connection.setUsername("app");
      connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
      connection.setPublisherReturns(true);
      return connection;
    }
  }
}
