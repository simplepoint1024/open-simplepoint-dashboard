/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.jdbc;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.api.EventSubscription;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional outbox/inbox with portable SQL, durable retry and fenced multi-instance leases. */
public final class JdbcEventStore implements EventPublisher {
  private final DataSource dataSource;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final EventCodec codec;
  private final String owner;
  private final Clock clock;
  private final EventRetryPolicy policy;

  /** Uses the same data source and transaction manager as the owning business service. */
  public JdbcEventStore(DataSource dataSource, PlatformTransactionManager manager,
                        EventCodec codec, String owner, Clock clock, EventRetryPolicy policy) {
    this.dataSource = dataSource;
    this.jdbc = new JdbcTemplate(dataSource);
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.codec = codec;
    this.owner = EventMessage.token(owner, "service owner");
    this.clock = clock;
    this.policy = policy;
  }

  @Override
  public void publish(EventMessage event) {
    requireTransaction();
    if (!owner.equals(event.producer())) {
      throw new IllegalArgumentException("Event producer must be the local service");
    }
    insert("OUTBOX", "", event);
  }

  /** Commits reception before Rabbit acknowledgement. A duplicate cannot alter the original fact. */
  public void receive(EventSubscription subscription, EventMessage event) {
    subscription.validate(event);
    codec.encode(event);
    try {
      transactions.executeWithoutResult(status -> insert("INBOX", subscription.id(), event));
    } catch (DuplicateKeyException duplicate) {
      // PostgreSQL aborts a transaction after a unique violation; compare outside that transaction.
      String stored = jdbc.queryForObject("select envelope from sp_event_jobs_v1 "
          + "where owner = ? and direction = 'INBOX' and subscription = ? and event_id = ?",
          String.class, owner, subscription.id(), event.eventId().toString());
      if (!Objects.equals(codec.decode(stored.getBytes(StandardCharsets.UTF_8)), event)) {
        throw new IllegalArgumentException("Event identity reused with different contents");
      }
    }
  }

  /** Claims one job using a conditional update; no broker I/O runs inside this transaction. */
  public EventJob claim(String direction) {
    if (!List.of("OUTBOX", "INBOX").contains(direction)) {
      throw new IllegalArgumentException("Invalid direction");
    }
    return transactions.execute(status -> {
      long now = clock.millis();
      List<String> candidates = jdbc.queryForList("select id from sp_event_jobs_v1 "
          + "where owner = ? and direction = ? and ((state = 'PENDING' and next_at <= ?) "
          + "or (state = 'PROCESSING' and lease_until <= ?)) order by created_at, id limit 16",
          String.class, owner, direction, now, now);
      for (String id : candidates) {
        String token = UUID.randomUUID().toString();
        int updated = jdbc.update("update sp_event_jobs_v1 set state = 'PROCESSING', "
            + "lease_token = ?, lease_until = ?, attempts = attempts + 1 where id = ? "
            + "and ((state = 'PENDING' and next_at <= ?) "
            + "or (state = 'PROCESSING' and lease_until <= ?))",
            token, now + policy.lease().toMillis(), id, now, now);
        if (updated == 1) {
          return jdbc.queryForObject("select * from sp_event_jobs_v1 where id = ?",
              this::mapJob, id);
        }
      }
      return null;
    });
  }

  /** Runs inbox effects and completion in one transaction after locking and checking the lease. */
  public void process(EventJob job, Runnable effect) {
    transactions.executeWithoutResult(status -> {
      List<String> tokens = jdbc.queryForList("select lease_token from sp_event_jobs_v1 "
          + "where id = ? and owner = ? and state = 'PROCESSING' for update",
          String.class, job.id(), owner);
      if (tokens.size() != 1 || !job.leaseToken().equals(tokens.getFirst())) {
        return;
      }
      effect.run();
      complete(job);
    });
  }

  /** Completes a broker delivery only for the current owner of the lease. */
  public void sent(EventJob job) {
    transactions.executeWithoutResult(status -> complete(job));
  }

  /** Stores only an error category; payloads, credentials and database error text are not logged. */
  public void failed(EventJob job, RuntimeException failure) {
    transactions.executeWithoutResult(status -> jdbc.update("update sp_event_jobs_v1 "
        + "set state = ?, next_at = ?, lease_token = null, lease_until = 0, last_error = ? "
        + "where id = ? and owner = ? and state = 'PROCESSING' and lease_token = ?",
        job.attempts() >= policy.maxAttempts() ? "DEAD" : "PENDING",
        clock.millis() + policy.delayMillis(job.attempts()),
        failure.getClass().getSimpleName(), job.id(), owner, job.leaseToken()));
  }

  /** Operator-only SPI: caller must authorize the actor; replay is durably audited. */
  public boolean replay(String jobId, String actor, String reason) {
    if (actor == null || actor.isBlank() || actor.length() > 160
        || reason == null || reason.isBlank() || reason.length() > 512) {
      throw new IllegalArgumentException("Replay requires an actor and reason");
    }
    return Boolean.TRUE.equals(transactions.execute(status -> {
      int changed = jdbc.update("update sp_event_jobs_v1 set state = 'PENDING', attempts = 0, "
          + "next_at = ?, last_error = null where id = ? and owner = ? and state = 'DEAD'",
          clock.millis(), jobId, owner);
      if (changed == 1) {
        jdbc.update("insert into sp_event_replays_v1 "
            + "(id, job_id, actor, reason, created_at) values (?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(), jobId, actor, reason, clock.millis());
      }
      return changed == 1;
    }));
  }

  /** Retains bounded poison evidence without accepting or dispatching the untrusted envelope. */
  public void quarantine(String subscription, byte[] body) {
    byte[] bounded = java.util.Arrays.copyOf(body, Math.min(body.length, EventCodec.MAX_BYTES));
    transactions.executeWithoutResult(status -> jdbc.update("insert into sp_event_quarantine_v1 "
        + "(id, owner, subscription, body_base64, original_bytes, created_at) "
        + "values (?, ?, ?, ?, ?, ?)", UUID.randomUUID().toString(), owner, subscription,
        java.util.Base64.getEncoder().encodeToString(bounded), body.length, clock.millis()));
  }

  /** Queue depth, also used by health/metrics without exposing business payloads. */
  public long count(String direction, String state) {
    Long count = jdbc.queryForObject("select count(*) from sp_event_jobs_v1 "
        + "where owner = ? and direction = ? and state = ?", Long.class, owner, direction, state);
    return count == null ? 0 : count;
  }

  private void insert(String direction, String subscription, EventMessage event) {
    String envelope = codec.encode(event);
    jdbc.update("insert into sp_event_jobs_v1 (id, owner, direction, subscription, event_id, "
        + "envelope, state, attempts, next_at, lease_until, created_at) "
        + "values (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, 0, ?)",
        UUID.randomUUID().toString(), owner, direction, subscription, event.eventId().toString(),
        envelope, clock.millis(), clock.millis());
  }

  private void complete(EventJob job) {
    jdbc.update("update sp_event_jobs_v1 set state = 'DONE', lease_token = null, lease_until = 0, "
        + "last_error = null where id = ? and owner = ? and state = 'PROCESSING' "
        + "and lease_token = ?", job.id(), owner, job.leaseToken());
  }

  private EventJob mapJob(ResultSet row, int index) throws SQLException {
    return new EventJob(row.getString("id"), row.getString("subscription"),
        codec.decode(row.getString("envelope").getBytes(StandardCharsets.UTF_8)),
        row.getString("lease_token"), row.getInt("attempts"));
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Event publishing requires a writable business transaction");
    }
    var connection = DataSourceUtils.getConnection(dataSource);
    try {
      if (!DataSourceUtils.isConnectionTransactional(connection, dataSource)
          || connection.getAutoCommit()) {
        throw new IllegalStateException("Event and business data must share a transaction");
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("Cannot verify event transaction", ex);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }
}
