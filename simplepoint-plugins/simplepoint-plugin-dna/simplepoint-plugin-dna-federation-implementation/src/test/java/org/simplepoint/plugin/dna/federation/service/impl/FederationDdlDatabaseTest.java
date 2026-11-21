package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels.SqlConsoleRequest;

/** Exercises the public console boundary against disposable PostgreSQL and MySQL databases. */
class FederationDdlDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void completeNativeDdlLifecycleKeepsForeignKeysAndUnicode(boolean postgres) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      var dataSource = fixture.pool;
      var console = fixture.console;
      String stem = "ddl_" + UUID.randomUUID().toString().replace("-", "");
      String parent = stem + "_p";
      String child = stem + "_c";
      String renamed = stem + "_r";
      String view = stem + "_v";
      String index = stem + "_ix";
      String title = postgres ? "\"名称\"" : "`名称`";
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement()) {
        try {
          for (var sql :
              List.of(
                  "CREATE TABLE ds." + parent + " (id INT PRIMARY KEY)",
                  "CREATE TABLE ds."
                      + child
                      + " (id INT PRIMARY KEY, customer_id INT, "
                      + title
                      + " VARCHAR(100), FOREIGN KEY (customer_id) REFERENCES ds."
                      + parent
                      + "(id))",
                  "ALTER TABLE ds."
                      + child
                      + " ADD COLUMN amount DECIMAL(18,2) NOT NULL DEFAULT 0.00",
                  "CREATE INDEX ds." + index + " ON ds." + child + " (customer_id)",
                  "ALTER TABLE ds." + child + " RENAME TO " + renamed,
                  "CREATE VIEW ds." + view + " AS SELECT * FROM ds." + renamed)) {
            var result = console.executeDdl(new SqlConsoleRequest("ds", sql));
            assertEquals("ds", result.dataSourceCode());
          }
          statement.executeUpdate("INSERT INTO " + parent + " VALUES (1)");
          statement.executeUpdate(
              "INSERT INTO " + renamed + " (id, customer_id, " + title + ") VALUES (1,1,'客户・日本語')");
          assertThrows(
              SQLException.class,
              () ->
                  statement.executeUpdate(
                      "INSERT INTO " + renamed + " (id, customer_id) VALUES (2,99)"));
          assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM " + parent));
          try (var rows = statement.executeQuery("SELECT " + title + " FROM " + view)) {
            assertTrue(rows.next());
            assertEquals("客户・日本語", rows.getString(1));
          }
          console.executeDdl(new SqlConsoleRequest("ds", "DROP VIEW ds." + view));
          console.executeDdl(new SqlConsoleRequest("ds", "TRUNCATE TABLE ds." + renamed));
          try (var rows = statement.executeQuery("SELECT id FROM " + renamed)) {
            assertFalse(rows.next());
          }
          console.executeDdl(
              new SqlConsoleRequest("ds", "DROP TABLE ds." + renamed + ", ds." + parent));
          assertThrows(
              SQLException.class, () -> statement.executeQuery("SELECT * FROM " + renamed));
        } finally {
          statement.execute("DROP VIEW IF EXISTS " + view);
          for (String table : List.of(renamed, child, parent)) {
            statement.execute("DROP TABLE IF EXISTS " + table);
          }
        }
      }
    }
  }
}
