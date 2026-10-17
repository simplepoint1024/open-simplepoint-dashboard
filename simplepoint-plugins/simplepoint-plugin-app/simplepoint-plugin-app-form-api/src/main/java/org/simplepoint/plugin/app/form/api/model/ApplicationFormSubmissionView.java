package org.simplepoint.plugin.app.form.api.model;

import java.time.Instant;

/**
 * Metadata-only acknowledgement; business snapshots have a separate participant/policy read
 * boundary.
 */
public record ApplicationFormSubmissionView(
    String id,
    String title,
    String applicationId,
    String pageId,
    String formId,
    int formVersion,
    String recordId,
    Long recordVersion,
    String definitionId,
    int definitionVersion,
    String previousSubmissionId,
    String processInstanceId,
    String processStatus,
    String status,
    String step,
    String errorCode,
    int attempt,
    Instant createdAt,
    Instant updatedAt) {}
