package org.simplepoint.platform.installation;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.simplepoint.platform.installation.InstallationException.Code;
import org.simplepoint.platform.installation.InstallationJournal.Attempt;
import org.simplepoint.platform.installation.InstallationJournal.State;

/** Executes a pinned operation; reconciles owner receipts without guessing uncertain outcomes. */
public final class InstallationRunner {
  private final InstallationJournal journal;

  /** Uses a durable journal owned by the installer. */
  public InstallationRunner(InstallationJournal journal) {
    this.journal = Objects.requireNonNull(journal);
  }

  /** All adapters are checked before the journal or any domain is mutated. */
  public InstallationJournal.Snapshot run(
      InstallationPlan plan, List<InstallationAction> supplied) {
    var actions = new HashMap<String, InstallationAction>();
    for (var action : List.copyOf(supplied)) {
      if (actions.putIfAbsent(action.definition().id(), action) != null) {
        throw new InstallationException(Code.ADAPTER_MISMATCH);
      }
    }
    if (actions.size() != plan.steps().size()
        || plan.steps().stream()
            .anyMatch(
                step ->
                    !actions.containsKey(step.id())
                        || !step.equals(actions.get(step.id()).definition()))) {
      throw new InstallationException(Code.ADAPTER_MISMATCH);
    }
    var snapshot = journal.open(plan);
    if (snapshot.complete()) {
      return snapshot;
    }
    for (int index = 0; index < plan.steps().size(); index++) {
      var step = plan.steps().get(index);
      var action = actions.get(step.id());
      var progress = journal.read(plan).steps().get(index);
      if (progress.state() == State.COMPLETE) {
        continue;
      }
      if (progress.state() == State.RUNNING || progress.state() == State.FAILED) {
        final java.util.Optional<String> receipt;
        try {
          receipt = Objects.requireNonNull(action.completedReceipt());
        } catch (RuntimeException failure) {
          var rejection = new InstallationException(Code.RECONCILIATION_FAILED);
          rejection.initCause(failure);
          throw rejection;
        }
        if (receipt.isPresent()) {
          journal.complete(
              plan,
              new Attempt(index, progress.attempt()),
              InstallationFingerprint.require(receipt.orElseThrow()));
          continue;
        }
        if (progress.state() == State.RUNNING
            || step.retry() != InstallationPlan.Retry.DURABLE_IDEMPOTENT) {
          throw new InstallationException(Code.RECOVERY_REQUIRED);
        }
      }
      var attempt = journal.claim(plan, index);
      final String receipt;
      try {
        receipt = InstallationFingerprint.require(action.apply());
      } catch (Exception failed) {
        try {
          journal.failed(plan, attempt);
        } catch (RuntimeException journalFailure) {
          failed.addSuppressed(journalFailure);
        }
        var rejection = new InstallationException(Code.ACTION_FAILED);
        rejection.initCause(failed);
        throw rejection;
      }
      // A missing journal acknowledgement is not a failed business fact. Reconcile next time.
      journal.complete(plan, attempt, receipt);
    }
    return journal.finish(plan);
  }
}
