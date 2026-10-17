/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventSubscription;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcEventStoreTest {
  private JdbcTemplate jdbc;
  private JdbcEventStore store;
  private TransactionTemplate transaction;
  private final MutableClock clock = new MutableClock();
  private final EventSubscription subscription = new EventSubscription("rbac.offline", "app",
      "resource.offline.requested", 1);

  @BeforeEach
  void setUp() {
    var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
        + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
    String postgresPassword = System.getenv("SP_EVENT_TEST_POSTGRES_PASSWORD");
    if (postgresPassword != null) {
      dataSource = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:35432/event_verification",
          "postgres", postgresPassword);
      String schema = "test_" + UUID.randomUUID().toString().replace("-", "");
      new JdbcTemplate(dataSource).execute("create schema " + schema);
      dataSource.setUrl(dataSource.getUrl() + "?currentSchema=" + schema);
    }
    var manager = new DataSourceTransactionManager(dataSource);
    transaction = new TransactionTemplate(manager);
    jdbc = new JdbcTemplate(dataSource);
    new ResourceDatabasePopulator(new ClassPathResource(
        "META-INF/simplepoint/events/schema-v1.sql")).execute(dataSource);
    jdbc.execute("create table business (id varchar(36) primary key, value_int integer)");
    store = new JdbcEventStore(dataSource, manager, new EventCodec(), "app", clock,
        new EventRetryPolicy(3, Duration.ofSeconds(2), Duration.ofMillis(10), Duration.ofSeconds(1)));
  }

  @Test
  void businessAndPublicationCommitOrRollbackTogether() {
    EventMessage event = event();
    assertThrows(IllegalStateException.class, () -> store.publish(event));
    assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
      jdbc.update("insert into business values ('one', 1)");
      store.publish(event);
      throw new IllegalStateException("rollback");
    }));
    assertEquals(0, store.count("OUTBOX", "PENDING"));
    assertEquals(0, jdbc.queryForObject("select count(*) from business", Integer.class));
    transaction.executeWithoutResult(status -> store.publish(event));
    assertEquals(event, store.claim("OUTBOX").event());
  }

  @Test
  void readOnlyAndForeignProducerAreRejected() {
    transaction.setReadOnly(true);
    assertThrows(IllegalStateException.class,
        () -> transaction.executeWithoutResult(status -> store.publish(event())));
    transaction.setReadOnly(false);
    EventMessage original = event();
    var forged = new EventMessage(original.eventId(), original.eventType(), 1, "other", "tenant",
        "resource", 1, "op", "trace", original.occurredAt(), original.payload());
    assertThrows(IllegalArgumentException.class,
        () -> transaction.executeWithoutResult(status -> store.publish(forged)));
  }

  @Test
  void duplicateReceiveIsConcurrentAndIdentityCannotChange() throws Exception {
    EventMessage event = event();
    try (var executor = Executors.newFixedThreadPool(6)) {
      var tasks = java.util.stream.IntStream.range(0, 20)
          .mapToObj(index -> executor.submit(() -> store.receive(subscription, event))).toList();
      for (Future<?> result : tasks) {
        result.get();
      }
    }
    assertEquals(1, store.count("INBOX", "PENDING"));
    var changed = new EventMessage(event.eventId(), event.eventType(), 1, "app", "other-tenant",
        "resource", 1, "op", "trace", event.occurredAt(), event.payload());
    assertThrows(IllegalArgumentException.class, () -> store.receive(subscription, changed));
  }

  @Test
  void crashLeaseAndStaleWorkerCannotOverwriteNewOwner() {
    transaction.executeWithoutResult(status -> store.publish(event()));
    EventJob old = store.claim("OUTBOX");
    assertNull(store.claim("OUTBOX"));
    clock.advance(2100);
    EventJob recovered = store.claim("OUTBOX");
    assertEquals(old.id(), recovered.id());
    store.sent(old);
    assertEquals(1, store.count("OUTBOX", "PROCESSING"));
    store.failed(old, new IllegalStateException());
    assertEquals(1, store.count("OUTBOX", "PROCESSING"));
    store.sent(recovered);
    assertEquals(1, store.count("OUTBOX", "DONE"));
  }

  @Test
  void localEffectReceiptAndCompletionPublicationAreAtomic() {
    store.receive(subscription, event());
    EventJob job = store.claim("INBOX");
    assertThrows(IllegalStateException.class, () -> store.process(job, () -> {
      jdbc.update("insert into business values ('effect', 1)");
      store.publish(event());
      throw new IllegalStateException("after effects");
    }));
    assertEquals(0, jdbc.queryForObject("select count(*) from business", Integer.class));
    assertEquals(0, store.count("OUTBOX", "PENDING"));
    store.process(job, () -> {
      jdbc.update("insert into business values ('effect', 1)");
      store.publish(event());
    });
    store.process(job, () -> fail("Duplicate committed effect"));
    assertEquals(1, store.count("OUTBOX", "PENDING"));
    assertEquals(1, store.count("INBOX", "DONE"));
  }

  @Test
  void retriesBackoffThenDeadAndAuditedReplay() {
    transaction.executeWithoutResult(status -> store.publish(event()));
    EventDispatcher dispatcher = new EventDispatcher(store, event -> {
      throw new IllegalStateException("broker unavailable");
    }, List.of());
    for (int attempt = 0; attempt < 3; attempt++) {
      assertEquals(1, dispatcher.dispatch("OUTBOX", 10));
      assertEquals(0, dispatcher.dispatch("OUTBOX", 10));
      clock.advance(2000);
    }
    assertEquals(1, store.count("OUTBOX", "DEAD"));
    String id = jdbc.queryForObject("select id from sp_event_jobs_v1", String.class);
    assertThrows(IllegalArgumentException.class, () -> store.replay(id, "", "reason"));
    assertTrue(store.replay(id, "operator", "restored broker"));
    assertFalse(store.replay(id, "operator", "duplicate replay"));
    assertEquals(1, jdbc.queryForObject("select count(*) from sp_event_replays_v1", Integer.class));
    new EventDispatcher(store, event -> {}, List.of()).dispatch("OUTBOX", 10);
    assertEquals(1, store.count("OUTBOX", "DONE"));
  }

  @Test
  void independentSubscriptionsEachRunOnce() {
    EventMessage event = event();
    EventSubscription second = new EventSubscription("dna.offline", "app", event.eventType(), 1);
    store.receive(subscription, event);
    store.receive(second, event);
    EventHandler firstHandler = handler(subscription);
    EventHandler secondHandler = handler(second);
    var dispatcher = new EventDispatcher(store, ignored -> {}, List.of(firstHandler, secondHandler));
    assertEquals(2, dispatcher.dispatch("INBOX", 10));
    store.receive(subscription, event);
    assertEquals(0, dispatcher.dispatch("INBOX", 10));
    assertEquals(2, jdbc.queryForObject("select count(*) from business", Integer.class));
  }

  @Test
  void poisonEvidenceIsBoundedAndNeverDispatchable() {
    store.quarantine(subscription.id(), new byte[EventCodec.MAX_BYTES + 1]);
    assertEquals(EventCodec.MAX_BYTES + 1, jdbc.queryForObject(
        "select original_bytes from sp_event_quarantine_v1", Integer.class));
    assertNull(store.claim("INBOX"));
  }

  private EventHandler handler(EventSubscription contract) {
    return new EventHandler() {
      public EventSubscription subscription() {
        return contract;
      }

      public void handle(EventMessage event) {
        jdbc.update("insert into business values (?, 1)", contract.id());
      }
    };
  }

  private EventMessage event() {
    return new EventMessage(UUID.randomUUID(), subscription.eventType(), 1, "app", "tenant",
        "resource", 1, "op", "trace", clock.instant(), JsonNodeFactory.instance.objectNode()
        .put("text", "中文日本語"));
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-15T00:00:00Z");

    void advance(long millis) {
      now = now.plusMillis(millis);
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
