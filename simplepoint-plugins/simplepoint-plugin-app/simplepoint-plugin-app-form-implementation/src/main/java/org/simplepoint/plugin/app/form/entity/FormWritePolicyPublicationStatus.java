package org.simplepoint.plugin.app.form.entity;

/** Durable coordination facts; elapsed leases never prove the target transaction was rolled back. */
public enum FormWritePolicyPublicationStatus {
  PREPARED,
  CANCELLING,
  CANCELLED,
  TARGET_READY,
  ACTIVE
}
