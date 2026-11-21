package org.simplepoint.plugin.dna.runtime.service.record;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Bounded JDBC diagnostic classification; messages and submitted values are never inspected. */
final class RecordConstraintFailures {
  private RecordConstraintFailures() {
  }

  static boolean integrityViolation(Throwable failure) {
    return matches(failure, sql -> sql.getSQLState() != null && sql.getSQLState().length() == 5
        && sql.getSQLState().startsWith("23"));
  }

  /** PostgreSQL/MySQL FK diagnostics, never localized exception text or constraint-value extraction. */
  static boolean referenceViolation(Throwable failure) {
    return matches(failure, sql -> "23503".equals(sql.getSQLState())
        || ("23000".equals(sql.getSQLState()) && (sql.getErrorCode() == 1451 || sql.getErrorCode() == 1452)));
  }

  private static boolean matches(Throwable failure, java.util.function.Predicate<SQLException> predicate) {
    var pending = new ArrayDeque<Throwable>();
    var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    pending.add(failure);
    while (!pending.isEmpty() && visited.size() < 32) {
      var current = pending.removeFirst();
      if (!visited.add(current)) {
        continue;
      }
      if (current instanceof SQLException sql) {
        if (predicate.test(sql)) return true;
        if (sql.getNextException() != null) {
          pending.addLast(sql.getNextException());
        }
      }
      if (current.getCause() != null) {
        pending.addLast(current.getCause());
      }
    }
    return false;
  }
}
