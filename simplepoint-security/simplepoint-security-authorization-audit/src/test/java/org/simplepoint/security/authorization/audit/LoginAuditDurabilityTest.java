package org.simplepoint.security.authorization.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.events.jdbc.EventCodec;
import org.simplepoint.events.jdbc.EventDispatcher;
import org.simplepoint.events.jdbc.EventRetryPolicy;
import org.simplepoint.events.jdbc.JdbcEventStore;
import org.simplepoint.plugin.auditing.logging.api.event.LoginAuditContract;
import org.simplepoint.security.authentication.event.LoginAuthenticationSuccessEvent;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

class LoginAuditDurabilityTest {
  @Test
  void brokerOutageRestartDuplicateDeliveryAndConsumerRollbackPreserveExactlyOneLog() {
    var producer = new Store("authorization");
    var consumer = new Store("auditing");
    consumer.jdbc.execute("create table observed_log (id varchar(36) primary key)");
    var target = new LoginAuthenticationAuditEventListener(producer.store);
    var factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(producer.manager, new AnnotationTransactionAttributeSource()));
    var listener = (LoginAuthenticationAuditEventListener) factory.getProxy();
    var event =
        new LoginAuthenticationSuccessEvent(
            "alice",
            "alice",
            "Alice",
            "forged-tenant",
            "forged-context",
            null,
            "127.0.0.1",
            "forged-ip",
            "test-agent",
            "/login",
            "PASSWORD",
            Instant.now());
    // Login audit survives an unrelated caller rollback because its own transaction has committed.
    assertThatThrownBy(
            () ->
                producer.transaction.executeWithoutResult(
                    status -> {
                      listener.onLoginSuccess(event);
                      throw new IllegalStateException("outer rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(producer.store.count("OUTBOX", "PENDING")).isEqualTo(1);
    var offline =
        new EventDispatcher(
            producer.store,
            message -> {
              throw new IllegalStateException("broker down");
            },
            List.of());
    offline.dispatch("OUTBOX", 1);
    assertThat(producer.store.count("OUTBOX", "PENDING")).isEqualTo(1);
    producer.jdbc.update("update sp_event_jobs_v1 set next_at=0");
    // A new dispatcher instance models process restart; duplicate receipts share an inbox identity.
    var online =
        new EventDispatcher(
            producer.store,
            message -> {
              var payload = LoginAuditContract.decode(message.payload());
              assertThat(payload.getTenantId()).isNull();
              assertThat(payload.getContextId()).isNull();
              assertThat(payload.getClientIp()).isEqualTo("127.0.0.1");
              consumer.store.receive(LoginAuditContract.SUBSCRIPTION, message);
              consumer.store.receive(LoginAuditContract.SUBSCRIPTION, message);
            },
            List.of());
    online.dispatch("OUTBOX", 1);
    assertThat(producer.store.count("OUTBOX", "DONE")).isEqualTo(1);
    AtomicBoolean fail = new AtomicBoolean(true);
    AtomicInteger calls = new AtomicInteger();
    EventHandler handler =
        new EventHandler() {
          public EventSubscription subscription() {
            return LoginAuditContract.SUBSCRIPTION;
          }

          public void handle(EventMessage message) {
            calls.incrementAndGet();
            consumer.jdbc.update(
                "insert into observed_log values (?)", message.eventId().toString());
            if (fail.get()) {
              throw new IllegalStateException("consumer failure after insert");
            }
          }
        };
    var worker = new EventDispatcher(consumer.store, message -> {}, List.of(handler));
    worker.dispatch("INBOX", 1);
    assertThat(consumer.jdbc.queryForObject("select count(*) from observed_log", Integer.class))
        .isZero();
    assertThat(consumer.store.count("INBOX", "PENDING")).isEqualTo(1);
    fail.set(false);
    consumer.jdbc.update("update sp_event_jobs_v1 set next_at=0");
    worker.dispatch("INBOX", 1);
    worker.dispatch("INBOX", 1);
    assertThat(consumer.jdbc.queryForObject("select count(*) from observed_log", Integer.class))
        .isEqualTo(1);
    assertThat(consumer.store.count("INBOX", "DONE")).isEqualTo(1);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void outboxUnavailableIsPropagatedInsteadOfSilentlyDroppingAudit() {
    EventPublisher broken =
        message -> {
          throw new IllegalStateException("database unavailable");
        };
    var listener = new LoginAuthenticationAuditEventListener(broken);
    assertThatThrownBy(
            () ->
                listener.onLoginSuccess(
                    new LoginAuthenticationSuccessEvent(
                        "alice",
                        "alice",
                        "Alice",
                        null,
                        null,
                        null,
                        "127.0.0.1",
                        null,
                        null,
                        "/login",
                        "PASSWORD",
                        Instant.now())))
        .isInstanceOf(IllegalStateException.class);
  }

  static class Store {
    final JdbcTemplate jdbc;
    final DataSourceTransactionManager manager;
    final TransactionTemplate transaction;
    final JdbcEventStore store;

    Store(String owner) {
      var ds =
          new DriverManagerDataSource(
              "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      jdbc = new JdbcTemplate(ds);
      manager = new DataSourceTransactionManager(ds);
      transaction = new TransactionTemplate(manager);
      new ResourceDatabasePopulator(
              new ClassPathResource("META-INF/simplepoint/events/schema-v1.sql"))
          .execute(ds);
      store =
          new JdbcEventStore(
              ds,
              manager,
              new EventCodec(),
              owner,
              Clock.systemUTC(),
              new EventRetryPolicy(
                  3, Duration.ofSeconds(60), Duration.ofSeconds(1), Duration.ofSeconds(5)));
    }
  }
}
