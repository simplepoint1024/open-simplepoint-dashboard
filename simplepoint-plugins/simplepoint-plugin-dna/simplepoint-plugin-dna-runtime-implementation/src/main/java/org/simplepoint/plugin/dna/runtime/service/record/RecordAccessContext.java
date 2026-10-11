package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Set;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/** Effective page, field, and row authorization for one model invocation. */
record RecordAccessContext(
    RuntimeActor actor,
    String resourceCode,
    Set<String> readableFields,
    Set<String> writableFields,
    Set<String> maskedFields,
    DataScopeCondition rowScope,
    String organizationId
) {

  RecordAccessContext {
    readableFields = Set.copyOf(readableFields);
    writableFields = Set.copyOf(writableFields);
    maskedFields = Set.copyOf(maskedFields);
  }
}
