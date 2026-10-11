package org.simplepoint.plugin.dna.runtime.api.model;

import java.time.Instant;

/** Tenant-scoped physical model identity and its current binding. */
public record PhysicalModelView(
    String id,
    String appId,
    String formId,
    String displayName,
    PhysicalModelStatus status,
    int latestVersion,
    Integer activeVersion,
    long lockVersion,
    PhysicalModelVersionView latestSchema,
    Instant createdAt,
    Instant updatedAt
) {
}
