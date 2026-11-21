package org.simplepoint.data.ownership;

import static org.simplepoint.data.ownership.PhysicalOwnershipException.Code.SESSION_CONFIGURATION_UNSUPPORTED;
import static org.simplepoint.data.ownership.PhysicalOwnershipException.Code.SESSION_STATE_INVALID;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Evidence that one lease retains its physical driver and server session. Driver-specific public
 * accessors are inspected without linking vendor jars into this JDK/JDBC-only module.
 */
final class NativeSessionConnection {
  private final Connection physical;
  private final boolean postgres;
  private final long sessionId;

  private NativeSessionConnection(Connection physical, boolean postgres, long sessionId) {
    this.physical = physical;
    this.postgres = postgres;
    this.sessionId = sessionId;
  }

  static NativeSessionConnection capture(Connection connection, boolean postgres)
      throws SQLException {
    Connection physical = physical(connection, postgres);
    return new NativeSessionConnection(physical, postgres, sessionId(connection, postgres));
  }

  void verify(Connection connection) throws SQLException {
    if (physical(connection, postgres) != physical
        || sessionId(connection, postgres) != sessionId) {
      throw new PhysicalOwnershipException(SESSION_STATE_INVALID);
    }
  }

  private static Connection physical(Connection connection, boolean postgres) throws SQLException {
    // Hikari and the platform's SimpleConnection preserve the JDBC unwrap contract. An opaque
    // alternative wrapper must not silently claim the same session affinity guarantee.
    Connection nativeConnection;
    try {
      nativeConnection = connection.unwrap(Connection.class);
    } catch (SQLException failure) {
      throw unsupported();
    }
    String expected =
        postgres ? "org.postgresql.jdbc.PgConnection" : "com.mysql.cj.jdbc.ConnectionImpl";
    if (nativeConnection == null || !expected.equals(nativeConnection.getClass().getName())) {
      throw unsupported();
    }
    if (!postgres) {
      // A multi-host wrapper may unwrap to its current physical connection. Check the actual
      // driver's proxy link as well; a later operation must not select another host/session.
      if (!Boolean.FALSE.equals(invoke(nativeConnection, "isProxySet"))) {
        throw unsupported();
      }
      Object properties = invoke(nativeConnection, "getPropertySet");
      for (String name :
          new String[] {"autoReconnect", "autoReconnectForPools", "reconnectAtTxEnd"}) {
        Object property = invoke(properties, "getBooleanProperty", name);
        if (!Boolean.FALSE.equals(invoke(property, "getValue"))) {
          throw unsupported();
        }
      }
    }
    return nativeConnection;
  }

  private static Object invoke(Object target, String name, String... argument) throws SQLException {
    if (target == null) {
      throw unsupported();
    }
    try {
      if (argument.length == 0) {
        return target.getClass().getMethod(name).invoke(target);
      }
      return target.getClass().getMethod(name, String.class).invoke(target, argument[0]);
    } catch (ReflectiveOperationException | SecurityException failure) {
      // Driver diagnostics can contain connection properties; retain only the stable safe code.
      if (failure instanceof InvocationTargetException invocation
          && invocation.getCause() instanceof VirtualMachineError fatal) {
        throw fatal;
      }
      throw unsupported();
    }
  }

  private static long sessionId(Connection connection, boolean postgres) throws SQLException {
    String sql = postgres ? "SELECT pg_catalog.pg_backend_pid()" : "SELECT CONNECTION_ID()";
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      try (var rows = statement.executeQuery(sql)) {
        if (!rows.next()) {
          throw new PhysicalOwnershipException(SESSION_STATE_INVALID);
        }
        long id = rows.getLong(1);
        if (rows.wasNull() || id <= 0 || rows.next()) {
          throw new PhysicalOwnershipException(SESSION_STATE_INVALID);
        }
        return id;
      }
    }
  }

  private static PhysicalOwnershipException unsupported() {
    return new PhysicalOwnershipException(SESSION_CONFIGURATION_UNSUPPORTED);
  }
}
