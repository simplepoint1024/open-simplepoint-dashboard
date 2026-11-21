package org.simplepoint.plugin.dna.runtime.service.record;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.springframework.security.access.AccessDeniedException;

/** Exact vendor diagnostics only; this classification alone never authorizes transaction replay. */
final class RecordContentionFailures {
  private static final Set<String> POSTGRES_STATES = Set.of("40001", "40P01", "55P03");

  private RecordContentionFailures() {}

  static boolean retryable(final String dialect, final Throwable failure) {
    if (!"postgresql".equals(dialect) && !"mysql".equals(dialect)) {
      return false;
    }
    var pending = new ArrayDeque<Throwable>();
    var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    pending.add(failure);
    boolean found = false;
    while (!pending.isEmpty()) {
      var current = pending.removeFirst();
      if (!visited.add(current)) {
        continue;
      }
      if (visited.size() > 32
          || current instanceof RecordMutationMissException
          || current instanceof AccessDeniedException
          || current instanceof DnaRuntimeException domain
              && domain.errorCode() != DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED) {
        return false;
      }
      if (current instanceof SQLException sql) {
        String state = sql.getSQLState();
        boolean matches =
            "postgresql".equals(dialect)
                ? POSTGRES_STATES.contains(state == null ? "" : state)
                : "40001".equals(state) && sql.getErrorCode() == 1213
                    || ("HY000".equals(state) || "40001".equals(state))
                        && sql.getErrorCode() == 1205;
        if (matches) {
          found = true;
        } else if (state != null || sql.getErrorCode() != 0) {
          // A second concrete diagnostic (connection loss, integrity, cancellation, etc.) wins.
          return false;
        }
        if (sql.getNextException() != null) {
          pending.addLast(sql.getNextException());
        }
      }
      if (current.getCause() != null) {
        pending.addLast(current.getCause());
      }
      // Suppressed cleanup failures do not turn the primary failure into contention.
    }
    return found;
  }
}
