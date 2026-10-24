package org.simplepoint.persistencefixture.beta;

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exercises module-owned resource injection and annotation-based transactions. */
@Service
public class BetaService {
  public final EntityManager entities;
  public final DataSource source;
  public final JdbcTemplate jdbc;
  public final PlatformTransactionManager manager;
  private final BetaRepository records;

  /** Receives independently bound infrastructure from the module assembler. */
  public BetaService(
      EntityManager entities,
      DataSource source,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      BetaRepository records) {
    this.entities = entities;
    this.source = source;
    this.jdbc = jdbc;
    this.manager = manager;
    this.records = records;
  }

  /** Persists an owned row and optionally fails after the SQL write. */
  @Transactional
  public String create(String label, boolean fail) {
    var record = new BetaRecord();
    record.setLabel(label);
    records.saveAndFlush(record);
    if (fail) {
      throw new IllegalStateException("rollback");
    }
    return record.getId();
  }

  /** Establishes the owning transaction for nested cross-store assertions. */
  @Transactional
  public void execute(Runnable action) {
    action.run();
  }

  /** Exposes the target instance through its AOP proxy for identity assertions. */
  public DataSource dataSource() {
    return source;
  }

  /** Returns the module-bound transaction manager for identity assertions. */
  public PlatformTransactionManager transactionManager() {
    return manager;
  }

  /** Verifies that direct JDBC reads use the same store as repository reads. */
  public long jdbcCount() {
    return jdbc.queryForObject("select count(*) from beta_records", Long.class);
  }

  /** Verifies that decoration preserves custom checked-exception rollback rules. */
  @Transactional(rollbackFor = Exception.class)
  public void checkedRollback() throws Exception {
    create("checked-rollback", false);
    throw new Exception("checked failure");
  }

  /** Observes the original read-only annotation after manager binding. */
  @Transactional(readOnly = true)
  public boolean readOnly() {
    return TransactionSynchronizationManager.isCurrentTransactionReadOnly();
  }

  /** Reads through the injected shared EntityManager. */
  @Transactional(readOnly = true)
  public long count() {
    return entities.createQuery("select count(r) from BetaRecord r", Long.class).getSingleResult();
  }
}
