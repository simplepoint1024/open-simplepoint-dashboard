package org.simplepoint.plugin.dna.runtime.service.record;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;

/** Keeps every JDBC back-reference inside the borrowed connection boundary used by Calcite. */
final class RuntimeJdbcConnection {
  private RuntimeJdbcConnection() {
  }

  static Connection borrow(final Connection physical) {
    return wrap(physical, null);
  }

  static Connection query(final Connection physical, final RuntimeSchemaDialect dialect) {
    return wrap(physical, () -> {
      try {
        dialect.resetRecordQueryConnection(physical);
      } finally {
        physical.close();
      }
    });
  }

  private static Connection wrap(final Connection physical, final CloseAction close) {
    AtomicBoolean closed = new AtomicBoolean();
    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, arguments) -> {
          if (method.getName().equals("close")) {
            if (close != null && closed.compareAndSet(false, true)) {
              close.run();
            }
            return null;
          }
          if (method.getName().equals("isClosed")) {
            return closed.get() || physical.isClosed();
          }
          if (method.getName().equals("commit") || method.getName().equals("rollback")
              || method.getName().equals("setAutoCommit") || method.getName().equals("abort")
              || method.getName().equals("setTransactionIsolation") || method.getName().equals("setReadOnly")) {
            throw new SQLException("The Record backend owns the physical transaction");
          }
          if (wrapperMethod(method)) {
            return wrapper(proxy, method, arguments);
          }
          Object result = invoke(physical, method, arguments);
          if (result instanceof Statement statement) {
            return statement(statement, (Connection) proxy);
          }
          if (result instanceof DatabaseMetaData metadata) {
            return metadata(metadata, (Connection) proxy);
          }
          return result;
        });
  }

  private static Statement statement(final Statement delegate, final Connection connection) {
    Class<?> type = delegate instanceof CallableStatement ? CallableStatement.class
        : delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
    return (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
        (proxy, method, arguments) -> {
          if (method.getName().equals("getConnection")) {
            return connection;
          }
          if (wrapperMethod(method)) {
            return wrapper(proxy, method, arguments);
          }
          Object result = invoke(delegate, method, arguments);
          return result instanceof ResultSet rows ? resultSet(rows, (Statement) proxy) : result;
        });
  }

  private static DatabaseMetaData metadata(final DatabaseMetaData delegate, final Connection connection) {
    return (DatabaseMetaData) Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
        new Class<?>[] {DatabaseMetaData.class}, (proxy, method, arguments) -> {
          if (method.getName().equals("getConnection")) {
            return connection;
          }
          if (wrapperMethod(method)) {
            return wrapper(proxy, method, arguments);
          }
          Object result = invoke(delegate, method, arguments);
          if (result instanceof ResultSet rows) {
            Statement owner = rows.getStatement();
            return resultSet(rows, owner == null ? null : statement(owner, connection));
          }
          return result;
        });
  }

  private static ResultSet resultSet(final ResultSet delegate, final Statement owner) {
    return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
        (proxy, method, arguments) -> {
          if (method.getName().equals("getStatement")) {
            return owner;
          }
          return wrapperMethod(method) ? wrapper(proxy, method, arguments) : invoke(delegate, method, arguments);
        });
  }

  private static boolean wrapperMethod(final Method method) {
    return method.getName().equals("unwrap") || method.getName().equals("isWrapperFor");
  }

  private static Object wrapper(Object proxy, Method method, Object[] arguments) throws SQLException {
    Class<?> type = (Class<?>) arguments[0];
    if (method.getName().equals("isWrapperFor")) {
      return type.isInstance(proxy);
    }
    if (type.isInstance(proxy)) {
      return proxy;
    }
    throw new SQLException("Cannot unwrap a borrowed transaction object");
  }

  @FunctionalInterface
  private interface CloseAction {
    void run() throws SQLException;
  }

  private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException exception) {
      throw exception.getCause();
    }
  }
}
