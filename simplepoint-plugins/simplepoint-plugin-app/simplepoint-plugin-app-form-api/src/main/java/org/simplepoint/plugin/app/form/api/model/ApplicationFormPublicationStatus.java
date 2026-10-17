package org.simplepoint.plugin.app.form.api.model;

/** Durable cross-service publication states. */
public enum ApplicationFormPublicationStatus {
  VALIDATING,
  VERSION_FROZEN,
  DATA_PREPARING,
  DATA_READY,
  RESOURCE_BINDING,
  ACTIVATING,
  ACTIVE,
  RETIRED,
  FAILED,
  RETRYING
}
