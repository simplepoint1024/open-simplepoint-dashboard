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
    var pending = new ArrayDeque<Throwable>();
    var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    pending.add(failure);
    while (!pending.isEmpty() && visited.size() < 32) {
      var current = pending.removeFirst();
      if (!visited.add(current)) {
        continue;
      }
      if (current instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (state != null && state.length() == 5 && state.startsWith("23")) {
          return true;
        }
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
