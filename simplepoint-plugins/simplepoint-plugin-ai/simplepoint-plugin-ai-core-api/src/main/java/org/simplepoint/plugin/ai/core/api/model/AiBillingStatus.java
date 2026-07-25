package org.simplepoint.plugin.ai.core.api.model;

/** Billing lifecycle state captured on an AI invocation ledger record. */
public enum AiBillingStatus {
  /** The model does not have billing enabled. */
  UNPRICED,
  /** Pricing was snapshotted and the invocation is still running. */
  PENDING,
  /** Usage has been priced successfully. */
  CALCULATED,
  /** The invocation failed or was cancelled and no charge was recorded. */
  NOT_CHARGED
}
