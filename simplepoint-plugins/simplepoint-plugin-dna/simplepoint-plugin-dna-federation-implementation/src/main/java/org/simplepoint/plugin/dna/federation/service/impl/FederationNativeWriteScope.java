package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlWriteOwnership.unavailable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;

/**
 * Native execution's exact-connection locks; direct ownership does not prove indirect SQL safety.
 */
final class FederationNativeWriteScope implements AutoCloseable {
  private final Resolver resolver;
  private final Map<String, PhysicalOwnershipSession> sessions = new LinkedHashMap<>();
  private List<FederationNativeSqlObjects.Use> expected;
  private boolean closed;

  private FederationNativeWriteScope(Resolver resolver) {
    this.resolver = resolver;
  }

  static FederationNativeWriteScope open(Connection connection, Resolver resolver) {
    var scope = new FederationNativeWriteScope(resolver);
    try {
      if (!connection.getAutoCommit()) {
        throw unavailable(null);
      }
      scope.expected = resolver.resolve();
      if (scope.expected.isEmpty() || scope.expected.size() > 512) {
        throw unavailable(null);
      }
      var names =
          scope.expected.stream()
              .map(FederationNativeSqlObjects.Use::namespace)
              .distinct()
              .sorted()
              .toList();
      for (var name : names) {
        scope.sessions.put(name, PhysicalOwnershipSession.open(connection, name));
      }
      scope.beforeExecution();
      return scope;
    } catch (SQLException | RuntimeException failure) {
      try {
        scope.close();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      if (failure instanceof FederationSqlWriteException rejected) {
        throw rejected;
      }
      throw unavailable(failure);
    }
  }

  void beforeExecution() {
    try {
      requireHeld();
      if (!expected.equals(resolver.resolve())) {
        throw unavailable(null);
      }
      for (var use : expected) {
        if (use.write() && sessions.get(use.namespace()).owner(use.table(), true).isPresent()) {
          throw new FederationSqlWriteException(Code.SQL_WRITE_PROTECTED);
        }
      }
      requireHeld();
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  void completed() {
    try {
      // DDL intentionally changes object identities, so only the retained locks are checked here.
      requireHeld();
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  private void requireHeld() throws SQLException {
    if (closed || sessions.isEmpty()) {
      throw unavailable(null);
    }
    for (var session : sessions.values()) {
      session.verifyExecutionSession();
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    FederationSqlWriteException failure = null;
    for (var session : List.copyOf(sessions.values()).reversed()) {
      try {
        session.close();
      } catch (SQLException | RuntimeException cleanup) {
        if (failure == null) {
          failure = unavailable(cleanup);
        } else {
          failure.addSuppressed(cleanup);
        }
      }
    }
    sessions.clear();
    if (failure != null) {
      throw failure;
    }
  }

  @FunctionalInterface
  interface Resolver {
    List<FederationNativeSqlObjects.Use> resolve() throws SQLException;
  }
}
