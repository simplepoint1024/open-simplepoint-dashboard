package org.simplepoint.plugin.rbac.tenant.service.initialize;

import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal, transaction-scoped creation permit after durable owner/caller verification. */
public final class DictionaryRegistrationWrites {
  private DictionaryRegistrationWrites() {}

  // Package-private: only the identity registration application can open this bounded scope.
  static void apply(DictionaryContribution declaration, Runnable action) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Dictionary approval requires a transaction");
    }
    var permit =
        new Permit(
            declaration.dictionaries().stream()
                .map(d -> d.code())
                .collect(Collectors.toUnmodifiableSet()));
    TransactionSynchronizationManager.registerSynchronization(permit);
    try {
      action.run();
    } finally {
      permit.active = false;
    }
  }

  /** Read-only guard for local management writes; callers cannot create or extend a permit. */
  public static boolean allowsCreation(String code) {
    return TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.getSynchronizations().stream()
            .anyMatch(
                item ->
                    item instanceof Permit permit && permit.active && permit.codes.contains(code));
  }

  private static final class Permit implements TransactionSynchronization {
    private final Set<String> codes;
    private boolean active = true;

    private Permit(Set<String> codes) {
      this.codes = codes;
    }
  }
}
