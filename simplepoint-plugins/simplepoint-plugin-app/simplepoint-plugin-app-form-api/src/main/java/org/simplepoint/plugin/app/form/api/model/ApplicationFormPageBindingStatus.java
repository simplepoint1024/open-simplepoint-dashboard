package org.simplepoint.plugin.app.form.api.model;

/** Durable lifecycle of one page-to-form publication. */
public enum ApplicationFormPageBindingStatus {
  DATA_PREPARING,
  RESOURCE_PREPARING,
  RESOURCE_READY,
  ACTIVATING,
  ACTIVE,
  RETIRED,
  CANCELLED,
  FAILED
}
