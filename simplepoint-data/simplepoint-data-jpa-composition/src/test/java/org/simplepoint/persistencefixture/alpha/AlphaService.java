package org.simplepoint.persistencefixture.alpha;

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/** Exercises module-owned resource injection and annotation-based transactions. */
@Service
public class AlphaService {
  public final EntityManager entities;
  public final DataSource source;
  public final JdbcTemplate jdbc;
  public final PlatformTransactionManager manager;
  private final AlphaRepository records;

  /** Receives independently bound infrastructure from the module assembler. */
  public AlphaService(
      EntityManager entities,
      DataSource source,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      AlphaRepository records) {
    this.entities = entities;
    this.source = source;
    this.jdbc = jdbc;
    this.manager = manager;
    this.records = records;
  }

  /** Persists an owned row and optionally fails after the SQL write. */
  @Transactional
  public String create(String label, boolean fail) {
    var record = new AlphaRecord();
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

  /** Reads through the injected shared EntityManager. */
  @Transactional(readOnly = true)
  public long count() {
    return entities.createQuery("select count(r) from AlphaRecord r", Long.class).getSingleResult();
  }
}
