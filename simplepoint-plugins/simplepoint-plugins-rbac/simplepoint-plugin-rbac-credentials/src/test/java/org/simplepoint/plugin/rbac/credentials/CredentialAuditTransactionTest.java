package org.simplepoint.plugin.rbac.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.jdbc.EventCodec;
import org.simplepoint.events.jdbc.EventRetryPolicy;
import org.simplepoint.events.jdbc.JdbcEventStore;
import org.simplepoint.plugin.auditing.logging.api.event.CredentialAuditContract;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

class CredentialAuditTransactionTest {
  @Test
  void auditCommitsToIdentityOutboxDespiteCallerRollbackAndInvalidAuditNeverCommits() {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(
            new ClassPathResource("META-INF/simplepoint/events/schema-v1.sql"))
        .execute(dataSource);
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("create table caller_effect (id integer)");
    var manager = new DataSourceTransactionManager(dataSource);
    var store =
        new JdbcEventStore(
            dataSource,
            manager,
            new EventCodec(),
            CredentialAuditContract.PRODUCER,
            Clock.systemUTC(),
            new EventRetryPolicy(
                3, Duration.ofSeconds(2), Duration.ofMillis(10), Duration.ofSeconds(1)));
    try (var context = new AnnotationConfigApplicationContext()) {
      context.register(Transactions.class);
      context.registerBean(DataSource.class, () -> dataSource);
      context.registerBean(PlatformTransactionManager.class, () -> manager);
      context.registerBean(EventPublisher.class, () -> store);
      context.registerBean(CredentialAudit.class);
      context.refresh();
      var audit = context.getBean(CredentialAudit.class);
      var outcome =
          new CredentialAuditContract.Outcome(
              "dna", "alice", "a".repeat(64), "SUCCESS", "VERIFIED", Instant.now().toEpochMilli());
      assertThatThrownBy(
              () ->
                  new TransactionTemplate(manager)
                      .executeWithoutResult(
                          status -> {
                            jdbc.update("insert into caller_effect values (1)");
                            audit.record(outcome);
                            throw new IllegalStateException("caller rollback");
                          }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(jdbc.queryForObject("select count(*) from caller_effect", Integer.class)).isZero();
      assertThat(store.count("OUTBOX", "PENDING")).isEqualTo(1);
      var event = store.claim("OUTBOX").event();
      CredentialAuditContract.SUBSCRIPTION.validate(event);
      assertThat(CredentialAuditContract.decode(event.payload())).isEqualTo(outcome);
      assertThat(event.tenantId()).isEqualTo("platform");
      assertThat(event.resourceId()).isEqualTo("credential-authentication");
      assertThatThrownBy(
              () ->
                  audit.record(
                      new CredentialAuditContract.Outcome(
                          "dna",
                          null,
                          "a".repeat(64),
                          null,
                          "INVALID",
                          Instant.now().toEpochMilli())))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(store.count("OUTBOX", "PENDING")).isZero();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  static class Transactions {}
}
