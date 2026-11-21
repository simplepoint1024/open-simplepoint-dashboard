package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot.Action;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot.Deferrability;

/** Isolated physical FK guarantees; this does not claim that form publication is integrated yet. */
class ForeignKeySchemaDatabaseTest {
  @Test void postgresqlCompositeReferenceAndUniqueConstraint() throws Exception { verify(true); }
  @Test void mysqlCompositeReferenceAndUniqueConstraint() throws Exception { verify(false); }

  private void verify(boolean postgres) throws Exception {
    String prefix = "DNA_RUNTIME_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
    String url = System.getenv(prefix + "_URL");
    Assumptions.assumeTrue(url != null, "Disposable database is not configured");
    var codec = new PhysicalSchemaSnapshotCodec(new ObjectMapper());
    JdbcDatabaseDialect core = postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
    AbstractRuntimeSchemaDialect dialect = postgres ? new PostgresqlRuntimeSchemaDialect(codec) : new MysqlRuntimeSchemaDialect(codec);
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String parent = "sp_rel_parent_" + suffix, child = "sp_rel_child_" + suffix, key = "fk_rel_" + suffix;
    try (Connection connection = DriverManager.getConnection(url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"))) {
      var md = connection.getMetaData();
      var context = new JdbcDatabaseDialect.SupportContext(core.code(), md.getDriverName(),
          md.getDatabaseProductName(), md.getDatabaseProductVersion(), connection.getCatalog(),
          connection.getSchema(), md.getIdentifierQuoteString(), Map.of());
      var target = new PhysicalSchemaTarget("test", new RuntimeMetadataTarget("test",
          connection.getCatalog(), postgres ? connection.getSchema() : "", child));
      var schema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(new LogicalFieldDefinition(
          "customer", "customer_id", "客户", LogicalFieldType.UUID, true, null, null, null, null)), List.of());
      var database = new DatabaseIdentity(md.getDatabaseProductName(), md.getDatabaseProductVersion(),
          md.getDatabaseMajorVersion(), md.getDatabaseMinorVersion());
      var plan = dialect.planInitial(connection, core, context, database, target, "交易", schema);
      // Follow the actual Calcite-compatible mapping, including dialect UUID-to-string adaptation.
      var idColumn = plan.targetSnapshot().columns().stream().filter(column -> column.name().equals("id"))
          .findFirst().orElseThrow();
      var referenceColumn = plan.targetSnapshot().columns().stream().filter(column -> column.name().equals("customer_id"))
          .findFirst().orElseThrow();
      assertThat(referenceColumn.storageType()).isEqualTo(idColumn.storageType());
      assertThat(referenceColumn.length()).isEqualTo(idColumn.length());
      String idType = dialect.nativeType(idColumn);
      execute(connection, "CREATE TABLE " + parent + " (tenant_id varchar(255) NOT NULL, id " + idType + " NOT NULL PRIMARY KEY, UNIQUE (tenant_id,id))");
      try {
        execute(connection, "CREATE TABLE " + child + " (tenant_id varchar(255) NOT NULL, id " + idType + " NOT NULL PRIMARY KEY, customer_id " + idType + ", UNIQUE (tenant_id,customer_id), CONSTRAINT " + key
            + " FOREIGN KEY (tenant_id,customer_id) REFERENCES " + parent + " (tenant_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT)");
        var original = dialect.inspect(connection, core, context, target);
        assertThat(original.foreignKeys()).hasSize(1);
        var reference = original.foreignKeys().getFirst();
        assertThat(reference.columns()).containsExactly("tenant_id", "customer_id");
        assertThat(reference.targetColumns()).containsExactly("tenant_id", "id");
        assertThat(reference.targetTable()).isEqualTo(parent);
        assertThat(reference.deleteAction()).isEqualTo(Action.RESTRICT);
        assertThat(reference.deferrability()).isEqualTo(Deferrability.NOT_DEFERRABLE);
        execute(connection, "INSERT INTO " + parent + " VALUES ('tenant-a','00000000-0000-0000-0000-000000000001'),('tenant-b','00000000-0000-0000-0000-000000000002')");
        execute(connection, "INSERT INTO " + child + " VALUES ('tenant-a','00000000-0000-0000-0000-000000000011','00000000-0000-0000-0000-000000000001')");
        // Existing ID from another tenant cannot satisfy a composite reference.
        assertThatThrownBy(() -> execute(connection, "INSERT INTO " + child + " VALUES ('tenant-b','00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000001')"))
            .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).startsWith("23"));
        assertThatThrownBy(() -> execute(connection, "INSERT INTO " + child + " VALUES ('tenant-a','00000000-0000-0000-0000-000000000013','00000000-0000-0000-0000-000000000001')"))
            .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).startsWith("23"));
        execute(connection, "INSERT INTO " + child + " VALUES ('tenant-a','00000000-0000-0000-0000-000000000014',NULL),('tenant-a','00000000-0000-0000-0000-000000000015',NULL)");
        assertThatThrownBy(() -> execute(connection, "DELETE FROM " + parent + " WHERE id='00000000-0000-0000-0000-000000000001'"))
            .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).startsWith("23"));
        execute(connection, "ALTER TABLE " + child + (postgres ? " DROP CONSTRAINT " : " DROP FOREIGN KEY ") + key);
        var removed = dialect.inspect(connection, core, context, target);
        assertThat(removed.foreignKeys()).isEmpty();
        assertThat(removed.checksum()).isNotEqualTo(original.checksum());
        execute(connection, "ALTER TABLE " + child + " ADD CONSTRAINT " + key + " FOREIGN KEY (tenant_id,customer_id) REFERENCES "
            + parent + " (tenant_id,id) ON DELETE CASCADE ON UPDATE RESTRICT");
        var changed = dialect.inspect(connection, core, context, target);
        assertThat(changed.foreignKeys().getFirst().deleteAction()).isEqualTo(Action.CASCADE);
        assertThat(changed.checksum()).isNotEqualTo(original.checksum());
        assertThat(changed.checksum()).isNotEqualTo(removed.checksum());
        // Retry/adoption must reject an unplanned FK before generating any further DDL.
        execute(connection, "DROP TABLE " + child);
        for (var step : plan.steps()) {
          execute(connection, step.statementSql());
        }
        assertThat(dialect.inspect(connection, core, context, target).checksum())
            .isEqualTo(plan.targetSnapshot().checksum());
        execute(connection, "ALTER TABLE " + child + " ADD CONSTRAINT " + key
            + " FOREIGN KEY (tenant_id,customer_id) REFERENCES " + parent
            + " (tenant_id,id) ON DELETE RESTRICT ON UPDATE RESTRICT");
        assertThatThrownBy(() -> dialect.planInitial(connection, core, context, database, target, "交易", schema))
            .isInstanceOfSatisfying(DnaRuntimeException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED));
        assertThatThrownBy(() -> dialect.planEvolution(connection, core, context, database, target, "交易", schema, schema))
            .isInstanceOfSatisfying(DnaRuntimeException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED));
      } finally {
        execute(connection, "DROP TABLE IF EXISTS " + child);
        execute(connection, "DROP TABLE " + parent);
      }
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) { statement.execute(sql); }
  }
}
