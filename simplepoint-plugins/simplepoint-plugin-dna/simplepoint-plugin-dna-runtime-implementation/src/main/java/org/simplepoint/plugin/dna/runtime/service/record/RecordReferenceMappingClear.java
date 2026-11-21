package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.LinkedHashMap;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingObservation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/** Clearing reads no target values; admission and destination writes still belong to the source. */
record RecordReferenceMappingClear(String revision, RecordReferenceMappingBinding.Source source) {
  static RecordReferenceMappingClear bind(
      RecordReferenceAdmission.Plan plan, String relationId, RuntimeActor actor) {
    if (actor == null) {
      throw RecordReferenceAdmission.incompatible();
    }
    return bind(plan, relationId, actor.tenantId());
  }

  /** Transaction admission already owns a verified tenant; no synthetic actor is needed. */
  static RecordReferenceMappingClear bind(
      RecordReferenceAdmission.Plan plan, String relationId, String tenantId) {
    if (tenantId == null || tenantId.isBlank() || tenantId.length() > 128) {
      throw RecordReferenceAdmission.incompatible();
    }
    var source = RecordReferenceMappingBinding.source(plan, relationId);
    String revision =
        RecordReferenceMappingBinding.digest(
            new Identity(
                "reference-mapping-clear:1",
                tenantId,
                relationId,
                source.model().physicalModelId(),
                source.installed().revision(),
                RecordCalculationStore.policyChecksum(source.installed().policy()),
                source.model().activeVersion(),
                source.schemaChecksum(),
                source.model().target(),
                source.parent().target()));
    return new RecordReferenceMappingClear(revision, source);
  }

  RecordReferenceMappingObservation observe() {
    return new RecordReferenceMappingObservation(revision, null, null);
  }

  RecordReferenceCopyPlan.Prepared prepare(
      RecordReferenceMappingObservation confirmation,
      Set<String> writableDestinationCodes,
      Set<String> applyDefaultMappingIds) {
    if (!observe().equals(confirmation)
        || writableDestinationCodes == null
        || applyDefaultMappingIds == null) {
      throw conflict();
    }
    var defaults =
        source.rule().mappings().stream()
            .filter(mapping -> mapping.mode() == RecordReferenceMapping.Mode.EDITABLE_DEFAULT)
            .map(RecordReferenceMapping::mappingId)
            .collect(java.util.stream.Collectors.toSet());
    if (!defaults.containsAll(applyDefaultMappingIds)) {
      throw conflict();
    }
    var managed = new LinkedHashMap<String, LogicalValue>();
    var suggestions = new LinkedHashMap<String, LogicalValue>();
    for (var mapping : source.rule().mappings()) {
      if (mapping.mode() == RecordReferenceMapping.Mode.EDITABLE_DEFAULT
          && !applyDefaultMappingIds.contains(mapping.mappingId())) {
        continue;
      }
      var destination =
          RecordReferenceMappingBinding.field(source.model(), mapping.destinationFieldId());
      if (!writableDestinationCodes.contains(destination.code())
          || mapping.nullPolicy() == RecordReferenceMapping.NullPolicy.REJECT) {
        throw conflict();
      }
      if (mapping.nullPolicy() == RecordReferenceMapping.NullPolicy.PRESERVE) {
        continue;
      }
      if (!destination.nullable()) {
        throw conflict();
      }
      var output =
          mapping.mode() == RecordReferenceMapping.Mode.SERVER_SNAPSHOT ? managed : suggestions;
      output.put(mapping.destinationFieldId(), LogicalValue.nullValue());
    }
    return new RecordReferenceCopyPlan.Prepared(confirmation, managed, suggestions);
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "Reference clearing confirmation or destination policy changed");
  }

  private record Identity(
      String domain,
      String tenantId,
      String relationId,
      String sourceModelId,
      long sourceRevision,
      String sourcePolicyChecksum,
      int schemaVersion,
      String schemaChecksum,
      PhysicalSchemaTarget sourceTarget,
      PhysicalSchemaTarget aggregateTarget) {}
}
