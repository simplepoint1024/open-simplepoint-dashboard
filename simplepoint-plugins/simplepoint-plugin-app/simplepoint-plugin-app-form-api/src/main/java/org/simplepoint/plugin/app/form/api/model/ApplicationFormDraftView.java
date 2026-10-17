package org.simplepoint.plugin.app.form.api.model;

import java.time.Instant;

/** Current mutable design snapshot and its optimistic revision. */
public record ApplicationFormDraftView(
    String formId,
    long revision,
    ApplicationFormDocument document,
    String checksum,
    String updatedBy,
    Instant updatedAt
) {
}
