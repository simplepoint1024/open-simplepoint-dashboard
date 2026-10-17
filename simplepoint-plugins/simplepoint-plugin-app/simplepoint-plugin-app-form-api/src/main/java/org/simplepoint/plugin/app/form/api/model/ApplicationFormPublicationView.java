package org.simplepoint.plugin.app.form.api.model;

import java.time.Instant;

/** Tenant-safe view of one durable form publication. */
public record ApplicationFormPublicationView(
    String id,
    String formId,
    int formVersion,
    ApplicationFormPublicationStatus status,
    ApplicationFormPublicationStep step,
    String migrationId,
    String errorCode,
    int attempt,
    Instant createdAt,
    Instant updatedAt
) {

  /** Returns whether the physical data schema is ready for later activation. */
  public boolean dataReady() {
    return status == ApplicationFormPublicationStatus.DATA_READY
        || status == ApplicationFormPublicationStatus.RESOURCE_BINDING
        || status == ApplicationFormPublicationStatus.ACTIVATING
        || status == ApplicationFormPublicationStatus.ACTIVE;
  }
}
