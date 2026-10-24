package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.simplepoint.events.jdbc.EventCodec;
import org.simplepoint.events.jdbc.EventDispatcher;
import org.simplepoint.events.rabbit.EventInfrastructureConfiguration;
import org.simplepoint.events.rabbit.RabbitEventTransport;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Real PostgreSQL and preprovisioned quorum queue with independent producer/consumer broker ACLs.
 */
@EnabledIfEnvironmentVariable(named = "SP_MIGRATIONS_POSTGRES_URL", matches = "jdbc:postgresql:.*")
@EnabledIfEnvironmentVariable(named = "SP_LABEL_EVENT_RABBIT_PORT", matches = "[0-9]+")
class MemberLabelBrokerPostgresTest {
  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void offlineReceiptDuplicatesAndTransportOutagePreserveDurableRefreshAndBrokerIsolation()
      throws Exception {
    try (var fixture = new MemberLabelEventFixture(true, 3)) {
      var producer = connection("common", "SP_LABEL_EVENT_COMMON_PASSWORD");
      var consumer = connection("workflow", "SP_LABEL_EVENT_WORKFLOW_PASSWORD");
      var codec = new EventCodec();
      var listeners =
          EventInfrastructureConfiguration.listeners(
              consumer, fixture.receiver, codec, List.of(fixture.handler), "workflow", false);
      try {
        fixture.labels.store.track("tenant");
        fixture.refresh(8).runBatch();
        fixture
            .labels
            .identity
            .jdbc()
            .update("update simpoint_ac_users set name='消息更新' where id='u0000'");
        fixture.observation.observe();
        var transport =
            new RabbitEventTransport(new RabbitTemplate(producer), codec, Duration.ofSeconds(2));
        var job = fixture.sender.claim("OUTBOX");
        transport.send(job.event());
        fixture.sender.sent(job);
        // Broker confirmation while the receiver is offline is not business completion.
        assertEquals(0, fixture.receiver.count("INBOX", "DONE"));
        assertEquals("成员0000", fixture.labels.activeName("u0000"));
        listeners.start();
        await(() -> fixture.receiver.count("INBOX", "PENDING") == 1);
        var inbox = new EventDispatcher(fixture.receiver, transport, List.of(fixture.handler));
        assertEquals(1, inbox.dispatch("INBOX", 10));
        fixture.refresh(8).runBatch();
        assertEquals("消息更新", fixture.labels.activeName("u0000"));
        transport.send(job.event());
        transport.send(job.event());

        producer.setAddresses("127.0.0.1:9");
        producer.resetConnection();
        fixture
            .labels
            .identity
            .jdbc()
            .update("update simpoint_ac_users set name='恢复更新' where id='u0000'");
        assertEquals(1, fixture.observation.observe());
        var outbox = new EventDispatcher(fixture.sender, transport, List.of());
        assertEquals(1, outbox.dispatch("OUTBOX", 10));
        assertEquals(1, fixture.sender.count("OUTBOX", "PENDING"));
        assertEquals("消息更新", fixture.labels.activeName("u0000"));
        producer.setAddresses("127.0.0.1:" + System.getenv("SP_LABEL_EVENT_RABBIT_PORT"));
        producer.resetConnection();
        fixture
            .labels
            .identity
            .jdbc()
            .update(
                "update sp_event_jobs_v1 set next_at=0 where owner='common' and direction='OUTBOX'"
                    + " and state='PENDING'");
        assertEquals(1, outbox.dispatch("OUTBOX", 10));
        await(() -> fixture.receiver.count("INBOX", "PENDING") == 1);
        assertEquals(1, inbox.dispatch("INBOX", 10));
        fixture.refresh(8).runBatch();
        assertEquals("恢复更新", fixture.labels.activeName("u0000"));
        assertEquals(2, fixture.receiver.count("INBOX", "DONE"));
        assertEquals(2, fixture.sender.count("OUTBOX", "DONE"));
        assertEquals(0, fixture.sender.count("OUTBOX", "DEAD"));
        assertThrows(
            AmqpException.class,
            () -> new RabbitTemplate(producer).receive("sp.events.workflow.member-labels.refresh"));
        var forged =
            new RabbitEventTransport(new RabbitTemplate(consumer), codec, Duration.ofSeconds(2));
        assertThrows(RuntimeException.class, () -> forged.send(job.event()));
      } finally {
        listeners.stop();
        producer.destroy();
        consumer.destroy();
      }
    }
  }

  private static CachingConnectionFactory connection(String user, String passwordVariable) {
    var connection =
        new CachingConnectionFactory(
            "127.0.0.1", Integer.parseInt(System.getenv("SP_LABEL_EVENT_RABBIT_PORT")));
    connection.setVirtualHost("/label-refresh");
    connection.setUsername(user);
    connection.setPassword(System.getenv(passwordVariable));
    connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    connection.setPublisherReturns(true);
    connection.getRabbitConnectionFactory().setConnectionTimeout(1000);
    return connection;
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(condition.getAsBoolean());
  }
}
