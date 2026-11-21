package org.simplepoint.data.calcite.core.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Connection lifecycle/fault proofs; actual database enforcement has separate native tests. */
class ReadOnlyJdbcDataSourceTest {
  @ParameterizedTest
  @ValueSource(strings = {"PostgreSQL", "MySQL"})
  void owningLeaseRollsBackAndRestoresStateThroughEveryConnectionBackReference(String product)
      throws Exception {
    var physical = new Physical(product);
    var source = new ReadOnlyJdbcDataSource(physical.source());
    var lease = source.getConnection();
    assertFalse(lease.getAutoCommit());
    assertTrue(lease.isReadOnly());
    assertThrows(SQLException.class, () -> lease.setAutoCommit(true));
    assertThrows(SQLException.class, lease::commit);
    assertThrows(SQLException.class, lease::rollback);
    assertThrows(SQLException.class, () -> lease.setReadOnly(false));
    var statement = lease.createStatement();
    assertSame(lease, statement.getConnection());
    assertSame(lease, lease.getMetaData().getConnection());
    lease.getMetaData().getConnection().close();
    lease.close();
    assertTrue(lease.isClosed());
    assertTrue(physical.autoCommit);
    assertFalse(physical.readOnly);
    assertFalse(physical.serverReadOnly);
    assertEquals(1, physical.events.stream().filter("rollback"::equals).count());
    assertEquals(1, physical.events.stream().filter("close"::equals).count());
    assertTrue(physical.events.indexOf("rollback") < physical.events.indexOf("autocommit:true"));
    assertThrows(SQLException.class, () -> statement.execute("SELECT 1"));
    assertThrows(SQLException.class, () -> source.getConnection("other", "ignored"));
    assertThrows(SQLException.class, () -> source.unwrap(DataSource.class));
  }

  @Test
  void mysqlRestoresServerModeIndependentlyOfTheDriversOriginalHint() throws Exception {
    var physical = new Physical("MySQL");
    physical.serverReadOnly = true;
    try (var ignored = new ReadOnlyJdbcDataSource(physical.source()).getConnection()) {
      assertTrue(physical.serverReadOnly);
    }
    assertFalse(physical.readOnly);
    assertTrue(physical.serverReadOnly);
  }

  @Test
  void rollbackFailureNeverRestoresAutocommitOrReturnsTheConnection() throws Exception {
    var physical = new Physical("PostgreSQL");
    var lease = new ReadOnlyJdbcDataSource(physical.source()).getConnection();
    physical.rollbackFailure = true;
    physical.abortFailure = true;
    var failure = assertThrows(SQLException.class, lease::close);
    assertEquals("injected rollback", failure.getMessage());
    assertEquals(1, failure.getSuppressed().length);
    assertFalse(physical.events.contains("autocommit:true"));
    assertFalse(physical.events.contains("close"));
    assertTrue(physical.events.contains("abort"));
    assertTrue(lease.isClosed());
  }

  @Test
  void setupFailureAndUnfinishedTransactionsAreDiscardedWithoutCommit() throws Exception {
    var transaction = new Physical("PostgreSQL");
    transaction.autoCommit = false;
    assertThrows(
        SQLException.class, () -> new ReadOnlyJdbcDataSource(transaction.source()).getConnection());
    assertEquals(List.of("abort"), transaction.events);
    var setup = new Physical("PostgreSQL");
    setup.setupFailure = true;
    assertThrows(
        SQLException.class, () -> new ReadOnlyJdbcDataSource(setup.source()).getConnection());
    assertTrue(setup.events.contains("abort"));
    assertFalse(setup.events.contains("autocommit:true"));
    assertFalse(setup.events.contains("commit"));
    var unsupported = new Physical("Unverified database");
    assertThrows(
        SQLException.class, () -> new ReadOnlyJdbcDataSource(unsupported.source()).getConnection());
    assertEquals(List.of("abort"), unsupported.events);
  }

  @Test
  void cancellationReachesDriverWhileQueryHoldsTheScopeMonitor() throws Exception {
    var physical = new Physical("PostgreSQL");
    try (var lease = new ReadOnlyJdbcDataSource(physical.source()).getConnection();
        var statement = lease.createStatement();
        var executor = Executors.newFixedThreadPool(2)) {
      var query = executor.submit(() -> statement.execute("SELECT wait"));
      assertTrue(physical.started.await(2, TimeUnit.SECONDS));
      var cancellation =
          executor.submit(
              () -> {
                statement.cancel();
                return null;
              });
      cancellation.get(2, TimeUnit.SECONDS);
      assertTrue(query.get(2, TimeUnit.SECONDS));
    }
  }

  private static final class Physical {
    private final String product;
    private final List<String> events = new ArrayList<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch cancelled = new CountDownLatch(1);
    private boolean autoCommit = true;
    private boolean readOnly;
    private boolean serverReadOnly;
    private boolean rollbackFailure;
    private boolean abortFailure;
    private boolean setupFailure;
    private boolean closed;

    private Physical(String product) {
      this.product = product;
    }

    private DataSource source() {
      Connection connection =
          (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (proxy, method, args) -> {
                    return switch (method.getName()) {
                      case "getAutoCommit" -> autoCommit;
                      case "isReadOnly" -> readOnly;
                      case "isClosed" -> closed;
                      case "getMetaData" ->
                          Proxy.newProxyInstance(
                              DatabaseMetaData.class.getClassLoader(),
                              new Class<?>[] {DatabaseMetaData.class},
                              (ignored, call, values) -> {
                                return switch (call.getName()) {
                                  case "getDatabaseProductName" -> product;
                                  case "getConnection" -> proxy;
                                  default -> throw new AssertionError(call.getName());
                                };
                              });
                      case "setReadOnly" -> {
                        readOnly = (boolean) args[0];
                        events.add("readonly:" + readOnly);
                        yield null;
                      }
                      case "setAutoCommit" -> {
                        autoCommit = (boolean) args[0];
                        events.add("autocommit:" + autoCommit);
                        yield null;
                      }
                      case "createStatement" -> statement((Connection) proxy);
                      case "rollback" -> {
                        events.add("rollback");
                        if (rollbackFailure) {
                          throw new SQLException("injected rollback");
                        }
                        yield null;
                      }
                      case "abort" -> {
                        events.add("abort");
                        if (abortFailure) {
                          throw new SQLException("injected abort");
                        }
                        closed = true;
                        yield null;
                      }
                      case "close" -> {
                        events.add("close");
                        closed = true;
                        yield null;
                      }
                      default -> throw new AssertionError(method.getName());
                    };
                  });
      return (DataSource)
          Proxy.newProxyInstance(
              DataSource.class.getClassLoader(),
              new Class<?>[] {DataSource.class},
              (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                  return connection;
                }
                throw new AssertionError(method.getName());
              });
    }

    private Statement statement(Connection connection) {
      return (Statement)
          Proxy.newProxyInstance(
              Statement.class.getClassLoader(),
              new Class<?>[] {Statement.class},
              (proxy, method, args) -> {
                return switch (method.getName()) {
                  case "setQueryTimeout", "close" -> null;
                  case "isClosed" -> false;
                  case "getConnection" -> connection;
                  case "cancel" -> {
                    cancelled.countDown();
                    yield null;
                  }
                  case "execute" -> {
                    String sql = (String) args[0];
                    if (setupFailure) {
                      throw new SQLException("injected setup");
                    }
                    if (sql.startsWith("SET SESSION")) {
                      serverReadOnly = sql.endsWith("READ ONLY");
                    }
                    if (sql.equals("SELECT wait")) {
                      started.countDown();
                      if (!cancelled.await(5, TimeUnit.SECONDS)) {
                        throw new SQLException("cancel did not reach driver");
                      }
                    }
                    yield true;
                  }
                  case "executeQuery" -> rows((String) args[0]);
                  default -> throw new AssertionError(method.getName());
                };
              });
    }

    private ResultSet rows(String sql) {
      var position = new java.util.concurrent.atomic.AtomicInteger();
      return (ResultSet)
          Proxy.newProxyInstance(
              ResultSet.class.getClassLoader(),
              new Class<?>[] {ResultSet.class},
              (proxy, method, args) -> {
                return switch (method.getName()) {
                  case "next" -> position.getAndIncrement() == 0;
                  case "getString" -> "on";
                  case "getBoolean" -> serverReadOnly;
                  case "wasNull" -> false;
                  case "close" -> null;
                  default -> throw new AssertionError(method.getName() + ": " + sql);
                };
              });
    }
  }
}
