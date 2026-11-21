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
    String organizationId,
    long resourceGeneration,
    Set<String> queryProtectedFields) {

  RecordAccessContext(
      RuntimeActor actor,
      String resourceCode,
      Set<String> readableFields,
      Set<String> writableFields,
      Set<String> maskedFields,
      DataScopeCondition rowScope,
      String organizationId) {
    this(
        actor,
        resourceCode,
        readableFields,
        writableFields,
        maskedFields,
        rowScope,
        organizationId,
        0);
  }

  RecordAccessContext(
      RuntimeActor actor,
      String resourceCode,
      Set<String> readableFields,
      Set<String> writableFields,
      Set<String> maskedFields,
      DataScopeCondition rowScope,
      String organizationId,
      long resourceGeneration) {
    this(
        actor,
        resourceCode,
        readableFields,
        writableFields,
        maskedFields,
        rowScope,
        organizationId,
        resourceGeneration,
        Set.of());
  }

  RecordAccessContext {
    readableFields = Set.copyOf(readableFields);
    writableFields = Set.copyOf(writableFields);
    maskedFields = Set.copyOf(maskedFields);
    queryProtectedFields = Set.copyOf(queryProtectedFields);
  }

  RecordAccessContext withGeneration(long generation) {
    return new RecordAccessContext(
        actor,
        resourceCode,
        readableFields,
        writableFields,
        maskedFields,
        rowScope,
        organizationId,
        generation,
        queryProtectedFields);
  }

  RecordAccessContext withQueryProtection(final Set<String> fields) {
    var protectedFields = new java.util.LinkedHashSet<>(queryProtectedFields);
    protectedFields.addAll(fields);
    return new RecordAccessContext(
        actor,
        resourceCode,
        readableFields,
        writableFields,
        maskedFields,
        rowScope,
        organizationId,
        resourceGeneration,
        protectedFields);
  }

  boolean queryable(final RuntimeRecordModel model, final String code) {
    var field = model.requestedField(code);
    return readableFields.contains(code)
        && !maskedFields.contains(code)
        && (field == null || !queryProtectedFields.contains(field.fieldId()));
  }
}
