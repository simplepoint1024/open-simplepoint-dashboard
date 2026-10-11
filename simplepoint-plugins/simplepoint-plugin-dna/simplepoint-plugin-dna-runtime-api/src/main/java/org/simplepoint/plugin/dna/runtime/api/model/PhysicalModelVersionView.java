package org.simplepoint.plugin.dna.runtime.api.model;

import java.time.Instant;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

/** Immutable target schema version returned by the runtime. */
public record PhysicalModelVersionView(
    String id,
    String physicalModelId,
    int version,
    PhysicalSchemaDefinition schema,
    String checksum,
    String createdBy,
    Instant createdAt
) {
}
