package org.simplepoint.data.jpa.transaction;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.hibernate.Session;
import org.hibernate.engine.spi.EntityKey;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Database serialization for a domain catalog, independent of its public error contract. */
public final class CatalogWriteBoundary {
  private final EntityManager entityManager;
  private final String table;
  private final Set<String> entities;
  private final Function<Failure, ? extends RuntimeException> rejection;

  /**
   * Uses an internal migration-owned table with one id='catalog' revision row. Table names are
   * trusted construction metadata, never request input.
   */
  public CatalogWriteBoundary(
      EntityManager entityManager,
      String table,
      Set<String> entities,
      Function<Failure, ? extends RuntimeException> rejection) {
    this.entityManager = Objects.requireNonNull(entityManager);
    if (table == null || !table.matches("[a-z][a-z0-9_]{0,62}")) {
      throw new IllegalArgumentException("Invalid catalog revision table");
    }
    this.table = table;
    this.entities = Set.copyOf(entities);
    if (this.entities.isEmpty()) {
      throw new IllegalArgumentException("Catalog entities must not be empty");
    }
    this.rejection = Objects.requireNonNull(rejection);
  }

  /**
   * Enters before catalog reads/writes; nested calls reuse the physical transaction's lock. Never
   * opens a second transaction or clears potentially dirty managed entities.
   */
  public void enter() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !entityManager.isJoinedToTransaction()) {
      throw rejection.apply(Failure.CONTEXT_CONFLICT);
    }
    Session session = entityManager.unwrap(Session.class);
    if (TransactionSynchronizationManager.getSynchronizations().stream()
        .anyMatch(
            item ->
                item instanceof Held held
                    && held.session() == session
                    && held.table().equals(table))) {
      return;
    }
    for (Object key : session.getStatistics().getEntityKeys()) {
      if (key instanceof EntityKey entity && entities.contains(entity.getEntityName())) {
        throw rejection.apply(Failure.CONTEXT_CONFLICT);
      }
    }
    try {
      int affected =
          entityManager
              .createNativeQuery(
                  "update "
                      + table
                      + " set revision=revision+1 where id='catalog' and"
                      + " revision<9223372036854775807")
              .setFlushMode(FlushModeType.COMMIT)
              .setHint("jakarta.persistence.query.timeout", 10000)
              .executeUpdate();
      if (affected != 1) {
        throw rejection.apply(Failure.UNAVAILABLE);
      }
    } catch (PersistenceException failure) {
      var error = rejection.apply(Failure.UNAVAILABLE);
      error.initCause(failure);
      throw error;
    }
    // An UPDATE also rejects older repeatable-read snapshots after a competing commit.
    TransactionSynchronizationManager.registerSynchronization(new Held(session, table));
  }

  /** Domain adapters map these categories to stable errors without leaking SQL or input. */
  public enum Failure {
    CONTEXT_CONFLICT,
    UNAVAILABLE
  }

  private record Held(Session session, String table) implements TransactionSynchronization {}
}
