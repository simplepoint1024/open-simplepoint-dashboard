package org.simplepoint.plugin.dna.runtime.service.record;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.sql.DataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceSql;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;

/** H2-only fixture; shared-lock and publication correctness are verified on PostgreSQL/MySQL. */
final class H2RecordWriteFenceFixture {

  private H2RecordWriteFenceFixture() {
  }

  static RuntimeSchemaDialect prepare(final DataSource source, final String tenant, final String model) throws Exception {
    String table = "simpoint_dna_record_write_fences_v1";
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE " + table + " (id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(255), "
          + "model_id VARCHAR(64), write_revision BIGINT, previous_revision BIGINT, "
          + "publication_fingerprint VARCHAR(64), deleted_at TIMESTAMP)");
      try (var insert = connection.prepareStatement("INSERT INTO " + table + " VALUES (?, ?, ?, 0, 0, ?, NULL)")) {
        insert.setString(1, new RecordWriteFenceKey(tenant, model).id());
        insert.setString(2, tenant);
        insert.setString(3, model);
        insert.setString(4, "0".repeat(64));
        insert.executeUpdate();
      }
    }
    String lock = "SELECT write_revision, publication_fingerprint, previous_revision FROM " + table
        + " WHERE id = ? AND tenant_id = ? AND model_id = ? AND deleted_at IS NULL FOR UPDATE";
    RuntimeSchemaDialect dialect = mock(RuntimeSchemaDialect.class);
    when(dialect.recordSqlDialectFactory()).thenReturn(org.apache.calcite.sql.SqlDialectFactoryImpl.INSTANCE);
    // H2 does not implement PostgreSQL/MySQL shared-row lock syntax. Only these isolated tests
    // use its stronger exclusive lock; management provisioning remains a real-database gate.
    when(dialect.recordWriteFenceSql(any(), any(), any())).thenReturn(new RecordWriteFenceSql(
        "unsupported_h2_management_initialization", lock, lock, "unsupported_h2_management_publication"));
    return dialect;
  }
}
