package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.CollectionModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalIndexDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Tests DNA-generated physical constraints, not the collection authorization/write protocol. */
class CollectionSchemaDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void preservesTenantQualifiedUniqueEdgesAndRetiredIdentities(boolean postgres) throws Exception {
    String prefix = "DNA_RUNTIME_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
    String url = System.getenv(prefix + "_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "Disposable database is required");
    var source =
        new DriverManagerDataSource(
            url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"));
    var codec = new PhysicalSchemaSnapshotCodec(new ObjectMapper());
    JdbcDatabaseDialect core =
        postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
    var dialect =
        postgres ? new PostgresqlRuntimeSchemaDialect(codec) : new MysqlRuntimeSchemaDialect(codec);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String leftName = "sp_collection_left_" + suffix;
    String rightName = "sp_collection_right_" + suffix;
    String edgeName = "sp_collection_edge_" + suffix;
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      var metadata = connection.getMetaData();
      var context =
          new JdbcDatabaseDialect.SupportContext(
              core.code(),
              metadata.getDriverName(),
              metadata.getDatabaseProductName(),
              metadata.getDatabaseProductVersion(),
              connection.getCatalog(),
              connection.getSchema(),
              metadata.getIdentifierQuoteString(),
              Map.of());
      var database =
          new DatabaseIdentity(
              metadata.getDatabaseProductName(),
              metadata.getDatabaseProductVersion(),
              metadata.getDatabaseMajorVersion(),
              metadata.getDatabaseMinorVersion());
      var left = new PhysicalSchemaTarget("projects", target(connection, postgres, leftName));
      var right = new PhysicalSchemaTarget("members", target(connection, postgres, rightName));
      var edge =
          new PhysicalSchemaTarget(
              "membership",
              target(connection, postgres, edgeName),
              Map.of("projects", left.metadataTarget(), "members", right.metadataTarget()));
      var root = new PhysicalSchemaDefinition("v1", List.of(), List.of(), null, true);
      var schema = schema(new CollectionModelRelation("membership", "source", "target"));
      try {
        install(
            connection,
            core,
            context,
            dialect,
            left,
            dialect.planInitial(connection, core, context, database, left, "项目", root));
        install(
            connection,
            core,
            context,
            dialect,
            right,
            dialect.planInitial(connection, core, context, database, right, "成员", root));
        install(
            connection,
            core,
            context,
            dialect,
            edge,
            dialect.planInitial(connection, core, context, database, edge, "项目成员", schema));
        var actual = dialect.inspect(connection, core, context, edge);
        assertThat(actual.foreignKeys()).hasSize(2);
        assertThat(actual.foreignKeys())
            .allSatisfy(
                key -> {
                  assertThat(key.columns()).startsWith("tenant_id");
                  assertThat(key.targetColumns()).containsExactly("tenant_id", "id");
                });
        insert(connection, leftName, 1, "tenant-a", null, null, false);
        insert(connection, leftName, 2, "tenant-b", null, null, false);
        insert(connection, rightName, 3, "tenant-a", null, null, false);
        insert(connection, rightName, 4, "tenant-a", null, null, false);
        insert(connection, rightName, 5, "tenant-b", null, null, false);
        insert(connection, edgeName, 10, "tenant-a", 1, 3, true);
        insert(connection, edgeName, 11, "tenant-a", 1, 4, true);
        insert(connection, edgeName, 12, "tenant-b", 2, 5, true);
        assertThatThrownBy(() -> insert(connection, edgeName, 13, "tenant-a", 1, 3, true))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insert(connection, edgeName, 13, "tenant-b", 1, 5, true))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insert(connection, edgeName, 13, "tenant-a", 1, 5, true))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                () ->
                    statement.executeUpdate(
                        "DELETE FROM " + leftName + " WHERE id='" + id(1) + "'"))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                () ->
                    statement.executeUpdate(
                        "DELETE FROM " + rightName + " WHERE id='" + id(3) + "'"))
            .isInstanceOf(SQLException.class);
        // Clearing both references releases FK retention while preserving the edge's CAS fence.
        assertThat(unlink(connection, edgeName, 10, 0)).isEqualTo(1);
        assertThat(unlink(connection, edgeName, 11, 0)).isEqualTo(1);
        assertThat(unlink(connection, edgeName, 10, 0)).isZero();
        assertThat(statement.executeUpdate("DELETE FROM " + leftName + " WHERE id='" + id(1) + "'"))
            .isEqualTo(1);
        assertThat(
                statement.executeUpdate("DELETE FROM " + rightName + " WHERE id='" + id(3) + "'"))
            .isEqualTo(1);
        try (var rows =
            statement.executeQuery(
                "SELECT record_version,source_id,target_id FROM "
                    + edgeName
                    + " WHERE id='"
                    + id(10)
                    + "'")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong(1)).isEqualTo(1);
          assertThat(rows.getString(2)).isNull();
          assertThat(rows.getString(3)).isNull();
        }
        // Removing/swapping collection identity must not turn an edge table into a CRUD model.
        assertThatThrownBy(
                () ->
                    dialect.planEvolution(
                        connection, core, context, database, edge, "普通表", schema, schema(null)))
            .isInstanceOf(DnaRuntimeException.class);
        assertThatThrownBy(
                () ->
                    dialect.planEvolution(
                        connection,
                        core,
                        context,
                        database,
                        edge,
                        "反向伪装",
                        schema,
                        schema(new CollectionModelRelation("membership", "target", "source"))))
            .isInstanceOf(DnaRuntimeException.class);
        assertThatThrownBy(
                () ->
                    dialect.planEvolution(
                        connection, core, context, database, edge, "添加身份", schema(null), schema))
            .isInstanceOf(DnaRuntimeException.class);
      } finally {
        statement.execute("DROP TABLE IF EXISTS " + edgeName);
        statement.execute("DROP TABLE IF EXISTS " + rightName);
        statement.execute("DROP TABLE IF EXISTS " + leftName);
      }
    }
  }

  private static RuntimeMetadataTarget target(Connection connection, boolean postgres, String table)
      throws SQLException {
    return new RuntimeMetadataTarget(
        "test", connection.getCatalog(), postgres ? connection.getSchema() : null, table);
  }

  private static PhysicalSchemaDefinition schema(CollectionModelRelation collection) {
    return new PhysicalSchemaDefinition(
        "v1",
        List.of(field("source"), field("target")),
        List.of(
            new LogicalIndexDefinition("edge_pair", List.of("source_id", "target_id"), true),
            new LogicalIndexDefinition("by_target", List.of("target_id"), false)),
        null,
        true,
        List.of(
            new LogicalReferenceDefinition("source", "source", "projects", false),
            new LogicalReferenceDefinition("target", "target", "members", false)),
        collection);
  }

  private static LogicalFieldDefinition field(String name) {
    return new LogicalFieldDefinition(
        name, name + "_id", name, LogicalFieldType.UUID, true, null, null, null, null);
  }

  private static void install(
      Connection connection,
      JdbcDatabaseDialect core,
      JdbcDatabaseDialect.SupportContext context,
      AbstractRuntimeSchemaDialect dialect,
      PhysicalSchemaTarget target,
      InitialSchemaPlan plan)
      throws SQLException {
    assertThat(dialect.inspect(connection, core, context, target).checksum())
        .isEqualTo(plan.checkpointChecksums().getFirst());
    for (var step : plan.steps()) {
      try (var statement = connection.createStatement()) {
        statement.execute(step.statementSql());
      }
      assertThat(dialect.inspect(connection, core, context, target).checksum())
          .isEqualTo(plan.checkpointChecksums().get(step.sequence()));
    }
  }

  private static void insert(
      Connection connection,
      String table,
      int number,
      String tenant,
      Integer left,
      Integer right,
      boolean edge)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "INSERT INTO "
                + table
                + " (id,tenant_id,created_by,created_at,updated_by,updated_at"
                + (edge ? ",source_id,target_id" : "")
                + ") VALUES (?,?,'actor',CURRENT_TIMESTAMP,'actor',CURRENT_TIMESTAMP"
                + (edge ? ",?,?" : "")
                + ")")) {
      statement.setString(1, id(number));
      statement.setString(2, tenant);
      if (edge) {
        statement.setString(3, left == null ? null : id(left));
        statement.setString(4, right == null ? null : id(right));
      }
      statement.executeUpdate();
    }
  }

  private static int unlink(Connection connection, String table, int edge, long version)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "UPDATE "
                + table
                + " SET source_id=NULL,target_id=NULL,record_version=record_version+1"
                + " WHERE id=? AND tenant_id=? AND record_version=?")) {
      statement.setString(1, id(edge));
      statement.setString(2, "tenant-a");
      statement.setLong(3, version);
      return statement.executeUpdate();
    }
  }

  private static String id(int value) {
    return new UUID(0, value).toString();
  }
}
