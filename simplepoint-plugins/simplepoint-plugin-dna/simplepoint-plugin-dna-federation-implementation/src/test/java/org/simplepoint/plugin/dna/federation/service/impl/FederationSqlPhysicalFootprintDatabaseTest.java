package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteSchemaConfigurer;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.service.support.FederationSqlIdentifierNormalizer;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Native object identity proof, not a claim that physical write admission is already wired. */
class FederationSqlPhysicalFootprintDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void sourceSpellingMatchesNativeObjectsAndAmbiguousCteCannotHideThem(boolean postgres)
      throws Exception {
    String prefix = "DNA_FEDERATION_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
    String url = System.getenv(prefix + "_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "Disposable native database is required");
    String namespace = "sp_footprint_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection =
        DriverManager.getConnection(
            url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"))) {
      execute(connection, "CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + namespace);
      try {
        if (postgres) {
          connection.setSchema(namespace);
        } else {
          connection.setCatalog(namespace);
        }
        execute(connection, "CREATE TABLE straße (id INTEGER)");
        execute(connection, "CREATE TABLE strasse (id INTEGER)");
        execute(connection, "CREATE TABLE destination (id INTEGER)");
        execute(connection, "CREATE TABLE calcite_target (id INTEGER)");
        execute(connection, "INSERT INTO straße VALUES (7)");
        execute(connection, "INSERT INTO strasse VALUES (9)");

        var source =
            new DriverManagerDataSource(
                url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"));
        CalciteSchemaConfigurer schemas =
            root ->
                root.add(
                    "ds",
                    JdbcSchema.create(
                        root,
                        "ds",
                        source,
                        postgres ? null : namespace,
                        postgres ? namespace : null));
        var engine = new DefaultCalciteQueryEngine();
        var result =
            engine.execute(
                new CalciteQueryRequest(
                    FederationSqlIdentifierNormalizer.normalize("SELECT id FROM ds.straße"),
                    "ds",
                    10,
                    5000),
                schemas);
        assertEquals(1, result.rows().size());
        assertEquals(7, ((Number) result.rows().getFirst().getFirst()).intValue());
        assertEquals(
            1,
            engine
                .executeUpdate(
                    new CalciteUpdateRequest(
                        FederationSqlIdentifierNormalizer.normalize(
                            "INSERT INTO ds.calcite_target SELECT id FROM ds.straße"),
                        "ds",
                        5000,
                        null),
                    schemas)
                .affectedRows());
        assertEquals(7, singleValue(connection, "SELECT id FROM calcite_target"));

        String qualified = "ds." + namespace + ".straße";
        var ddl =
            FederationDdlPlan.parse(
                "CREATE TABLE ds." + namespace + ".copy AS SELECT id FROM " + qualified);
        assertEquals(List.of("ds", namespace, "straße"), ddl.objects().getLast().names());
        execute(connection, ddl.nativeSql("ds"));
        assertEquals(7, singleValue(connection, "SELECT id FROM copy"));

        var dml =
            FederationDmlPlan.parse(
                "INSERT INTO ds." + namespace + ".destination SELECT id FROM " + qualified);
        assertEquals(List.of("ds", namespace, "straße"), dml.objects().getLast().names());
        assertEquals(FederationDmlPlan.Role.READ, dml.objects().getLast().role());
        execute(connection, dml.nativeSql("ds"));
        assertEquals(7, singleValue(connection, "SELECT id FROM destination"));

        if (postgres) {
          // Native PostgreSQL reads strasse, not the differently named CTE. Calcite normalizes
          // both unquoted spellings to STRASSE; treating that match as a CTE loses a real input.
          String query = "WITH straße AS (SELECT 100 AS id) SELECT id FROM strasse";
          assertEquals(9, singleValue(connection, query));
          assertThrows(
              FederationSqlWriteException.class,
              () -> FederationDmlPlan.parse("INSERT INTO ds.destination " + query));
          assertThrows(
              FederationSqlWriteException.class,
              () -> FederationDdlPlan.parse("CREATE TABLE ds.hidden_input AS " + query));
        }
      } finally {
        execute(
            connection,
            "DROP "
                + (postgres ? "SCHEMA " : "DATABASE ")
                + namespace
                + (postgres ? " CASCADE" : ""));
      }
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      statement.execute(sql);
    }
  }

  private static int singleValue(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      try (var rows = statement.executeQuery(sql)) {
        assertTrue(rows.next());
        int value = rows.getInt(1);
        assertFalse(rows.next());
        return value;
      }
    }
  }
}
