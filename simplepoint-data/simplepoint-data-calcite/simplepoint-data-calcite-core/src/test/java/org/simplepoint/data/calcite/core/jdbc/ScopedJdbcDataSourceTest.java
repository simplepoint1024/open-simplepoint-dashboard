package org.simplepoint.data.calcite.core.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.avatica.util.ByteString;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;

class ScopedJdbcDataSourceTest {
  @Test
  void arrayResultSetsCannotEscapeTheirConnectionLease() throws Exception {
    try (var scope = new ScopedJdbcDataSource(source().getConnection())) {
      var lease = scope.getConnection();
      var created = lease.createArrayOf("INTEGER", new Object[] {1, 2});
      var createdRows = created.getResultSet();
      var statement = lease.createStatement();
      var rows = statement.executeQuery("SELECT ARRAY[1,2]");
      assertTrue(rows.next());
      var selected = rows.getArray(1);
      var selectedRows = selected.getResultSet();
      lease.close();
      assertThrows(SQLException.class, createdRows::next);
      assertThrows(SQLException.class, selectedRows::next);
      assertThrows(SQLException.class, created::getResultSet);
      assertThrows(SQLException.class, selected::getResultSet);
      createdRows.close();
      selectedRows.close();
      created.free();
      selected.free();
      rows.close();
      statement.close();
    }
  }

  @Test
  void calciteWritesInTheOwnersTransactionAndCannotCommitIt() throws Exception {
    var source = source();
    var physical = source.getConnection();
    physical.createStatement().execute("CREATE TABLE orders (id INT PRIMARY KEY)");
    physical.setAutoCommit(false);
    try (var scope = new ScopedJdbcDataSource(physical)) {
      var result =
          new DefaultCalciteQueryEngine()
              .executeUpdate(
                  new CalciteUpdateRequest(
                      "INSERT INTO ds.orders VALUES (?)", "ds", 5000, List.of(1)),
                  root -> root.add("ds", JdbcSchema.create(root, "ds", scope, null, "PUBLIC")));
      assertEquals(1, result.affectedRows());
      try (var statement = physical.createStatement();
          var rows = statement.executeQuery("SELECT COUNT(*) FROM orders")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getInt(1));
      }
      assertEquals(0, count(source));
      physical.rollback();
      assertEquals(0, count(source));
      assertFalse(physical.isClosed());
    }
    assertTrue(physical.isClosed());
  }

  @Test
  void allBackReferencesStayBorrowedAndExpireWithTheirLease() throws Exception {
    var physical = source().getConnection();
    try (var scope = new ScopedJdbcDataSource(physical)) {
      var lease = scope.getConnection();
      var statement = lease.createStatement();
      var rows = statement.executeQuery("SELECT 1");
      var metadata = lease.getMetaData();
      assertSame(lease, statement.getConnection());
      assertSame(lease, rows.getStatement().getConnection());
      assertSame(lease, metadata.getConnection());
      assertSame(lease, lease.unwrap(Connection.class));
      assertThrows(SQLException.class, () -> lease.unwrap(org.h2.jdbc.JdbcConnection.class));
      assertThrows(SQLException.class, lease::commit);
      assertThrows(SQLException.class, lease::rollback);
      assertThrows(SQLException.class, () -> lease.setAutoCommit(false));
      assertThrows(SQLException.class, () -> statement.getConnection().setSchema("OTHER"));
      assertThrows(SQLException.class, () -> metadata.getConnection().abort(Runnable::run));
      assertThrows(SQLException.class, () -> scope.getConnection("different", "account"));
      lease.close();
      assertTrue(lease.isClosed());
      assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 2"));
      assertThrows(SQLException.class, rows::next);
      assertThrows(SQLException.class, metadata::getConnection);
      rows.close();
      statement.close();
      try (var next = scope.getConnection()) {
        assertFalse(next.isClosed());
      }
      assertFalse(physical.isClosed());
    }
  }

  @Test
  void scopeClosureFencesRetainedHandlesAndPreservesBinaryParameters() throws Exception {
    var physical = source().getConnection();
    var scope = new ScopedJdbcDataSource(physical);
    var lease = scope.getConnection();
    try (var create = lease.createStatement()) {
      create.execute("CREATE TABLE binary_data (payload VARBINARY(10))");
    }
    var insert = lease.prepareStatement("INSERT INTO binary_data VALUES (?)");
    byte[] value = {1, 2, -1};
    insert.setObject(1, new ByteString(value));
    assertEquals(1, insert.executeUpdate());
    try (var statement = lease.createStatement();
        var rows = statement.executeQuery("SELECT payload FROM binary_data")) {
      assertTrue(rows.next());
      assertArrayEquals(value, rows.getBytes(1));
    }
    scope.close();
    scope.close();
    assertTrue(physical.isClosed());
    assertTrue(lease.isClosed());
    assertThrows(SQLException.class, scope::getConnection);
    assertThrows(SQLException.class, scope::ownedConnection);
    assertThrows(SQLException.class, insert::executeUpdate);
  }

  @Test
  void uncertainCloseAbortsThePhysicalSessionAndDoesNotReopen() throws Exception {
    var physical = source().getConnection();
    var aborted = new AtomicBoolean();
    var broken =
        (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close")) {
                    throw new SQLException("uncertain close");
                  }
                  if (method.getName().equals("abort")) {
                    aborted.set(true);
                    physical.close();
                    return null;
                  }
                  try {
                    return method.invoke(physical, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    var scope = new ScopedJdbcDataSource(broken);
    assertThrows(SQLException.class, scope::close);
    assertTrue(aborted.get());
    assertTrue(physical.isClosed());
    assertThrows(SQLException.class, scope::getConnection);
    scope.close();
  }

  private static int count(JdbcDataSource source) throws SQLException {
    try (var connection = source.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM orders")) {
      assertTrue(rows.next());
      return rows.getInt(1);
    }
  }

  private static JdbcDataSource source() {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:scoped-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    source.setUser("sa");
    return source;
  }
}
