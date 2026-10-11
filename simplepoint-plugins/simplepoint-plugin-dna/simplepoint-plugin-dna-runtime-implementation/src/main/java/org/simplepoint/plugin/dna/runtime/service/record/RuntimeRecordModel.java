package org.simplepoint.plugin.dna.runtime.service.record;

import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;

/** Detached trusted model metadata used by record compilation and execution. */
record RuntimeRecordModel(
    String physicalModelId,
    String appId,
    String formId,
    int activeVersion,
    PhysicalSchemaDefinition schema,
    PhysicalSchemaTarget target
) {
}
