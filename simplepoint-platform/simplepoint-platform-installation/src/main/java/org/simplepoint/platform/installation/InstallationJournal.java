package org.simplepoint.platform.installation;

import java.util.List;
import java.util.UUID;

/** Durable operation authority; implementations never keep a transaction open around an action. */
public interface InstallationJournal {
  /** Creates or verifies the entire exact plan under the target generation boundary. */
  Snapshot open(InstallationPlan plan);

  /** Returns the current operation and refuses a changed or superseded plan. */
  Snapshot read(InstallationPlan plan);

  /** Claims only the first unfinished step; a running attempt is never blindly stolen. */
  Attempt claim(InstallationPlan plan, int index);

  /** Persists a verified durable owner receipt, also usable after response/journal loss. */
  void complete(InstallationPlan plan, Attempt attempt, String receiptFingerprint);

  /** Records an action exception; this is not proof that the domain operation rolled back. */
  void failed(InstallationPlan plan, Attempt attempt);

  /** Completes only after every required step has a durable receipt. */
  Snapshot finish(InstallationPlan plan);

  /**
   * Local recovery administration; evidence must come from the installer executor retirement check.
   */
  void authorizeRetry(InstallationPlan plan, Attempt attempt, String retirementEvidenceFingerprint);

  /** Persisted attempt token fences late journal acknowledgements. */
  record Attempt(int index, UUID token) {}

  /** Immutable operation view; COMPLETE is scoped to this plan, not platform business readiness. */
  record Snapshot(long generation, boolean complete, List<Progress> steps) {
    /** Defensively copies the ordered progress. */
    public Snapshot {
      steps = List.copyOf(steps);
    }
  }

  /**
   * Only fixed states, opaque tokens and hashes are returned; no configuration or secret payload.
   */
  record Progress(State state, UUID attempt, String receiptFingerprint) {}

  /** Persisted step state; FAILED never implies that external effects rolled back. */
  enum State {
    PENDING,
    RUNNING,
    FAILED,
    COMPLETE
  }
}
