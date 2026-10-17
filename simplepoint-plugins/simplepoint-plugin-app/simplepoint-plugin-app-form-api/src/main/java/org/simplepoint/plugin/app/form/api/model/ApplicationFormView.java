package org.simplepoint.plugin.app.form.api.model;

import java.time.Instant;

/** Tenant-safe form definition projection without physical placement details. */
public record ApplicationFormView(
    String id,
    String applicationId,
    String code,
    String name,
    String description,
    ApplicationFormStatus status,
    long draftRevision,
    int latestVersion,
    Integer publishedVersion,
    long lockVersion,
    Instant createdAt,
    Instant updatedAt
) {
}
