package org.simplepoint.platform.installation;

import java.util.Optional;

/** Domain adapter with exact immutable inputs and a durable, read-only receipt lookup. */
public interface InstallationAction {
  /** Returns the exact versioned action and input fingerprint bound to the plan. */
  InstallationPlan.Step definition();

  /**
   * Applies the same operation key on repeat calls and returns its persisted receipt fingerprint.
   */
  String apply() throws Exception;

  /** Empty means uncertain or incomplete, never proof that a previous executor is stopped. */
  Optional<String> completedReceipt();
}
