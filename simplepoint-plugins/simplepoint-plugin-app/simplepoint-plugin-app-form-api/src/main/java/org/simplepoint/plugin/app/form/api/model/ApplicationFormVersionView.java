package org.simplepoint.plugin.app.form.api.model;

import java.time.Instant;

/** Immutable form version produced from a validated draft. */
public record ApplicationFormVersionView(
    String id,
    String formId,
    int version,
    ApplicationFormDocument document,
    String checksum,
    String storageChecksum,
    Integer physicalModelVersion,
    String rendererVersion,
    String createdBy,
    Instant createdAt
) {
}
