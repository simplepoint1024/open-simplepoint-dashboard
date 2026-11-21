package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingObservation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.service.support.ReferenceMappingConversions;

/**
 * Attempt-local copy preparation. The transaction owner supplies an authorized target snapshot,
 * locks participants in its normal order, then rechecks the observation before committing effects.
 */
final class RecordReferenceCopyPlan {
  private final String revision;
  private final List<BoundField> fields;

  RecordReferenceCopyPlan(String revision, List<BoundField> fields) {
    if (revision == null
        || !revision.matches("[a-f0-9]{64}")
        || fields == null
        || fields.size() > 32) {
      throw incompatible();
    }
    this.revision = revision;
    this.fields = List.copyOf(fields);
    var identities = new HashSet<String>();
    var destinations = new HashSet<String>();
    for (var field : this.fields) {
      if (!identities.add(field.mapping().mappingId())
          || !destinations.add(field.destination().fieldId())) {
        throw incompatible();
      }
    }
  }

  Prepared prepare(
      String selectedTargetId,
      RecordView target,
      RecordReferenceMappingObservation confirmation,
      Set<String> readableTargetCodes,
      Set<String> maskedTargetCodes,
      Set<String> writableDestinationCodes,
      Set<String> applyDefaultMappingIds) {
    var defaults =
        fields.stream()
            .filter(field -> field.mapping().mode() == RecordReferenceMapping.Mode.EDITABLE_DEFAULT)
            .map(field -> field.mapping().mappingId())
            .collect(java.util.stream.Collectors.toSet());
    if (applyDefaultMappingIds == null || !defaults.containsAll(applyDefaultMappingIds)) {
      throw incompatible();
    }
    if (confirmation == null
        || !revision.equals(confirmation.revision())
        || !Objects.equals(selectedTargetId, confirmation.targetRecordId())) {
      throw conflict();
    }
    verify(target, confirmation);
    Map<String, LogicalValue> managed = new LinkedHashMap<>();
    Map<String, LogicalValue> suggestions = new LinkedHashMap<>();
    for (var bound : fields) {
      var mapping = bound.mapping();
      if (mapping.mode() == RecordReferenceMapping.Mode.EDITABLE_DEFAULT
          && !applyDefaultMappingIds.contains(mapping.mappingId())) {
        continue;
      }
      // A stored null cannot bypass target field policy. Clearing a reference reads no target
      // value, so it needs only the destination write grant and the owner's unlink admission.
      if (target != null
              && (!readableTargetCodes.contains(bound.target().code())
                  || maskedTargetCodes.contains(bound.target().code()))
          || !writableDestinationCodes.contains(bound.destination().code())) {
        throw conflict();
      }
      Object value = null;
      if (target != null) {
        if (!target.values().containsKey(bound.target().code())) {
          throw incompatible();
        }
        value = target.values().get(bound.target().code());
      }
      if (value == null) {
        if (mapping.nullPolicy() == RecordReferenceMapping.NullPolicy.REJECT) {
          throw conflict();
        }
        if (mapping.nullPolicy() == RecordReferenceMapping.NullPolicy.PRESERVE) {
          continue;
        }
      }
      // A cleared relation has no source row. Its nullable destination is still validated;
      // source nullability only applies when an actual target row supplied the null value.
      var converted =
          value == null && target == null
              ? clear(bound.destination())
              : ReferenceMappingConversions.convert(
                  mapping.conversion(), bound.target(), bound.destination(), value);
      var output =
          mapping.mode() == RecordReferenceMapping.Mode.SERVER_SNAPSHOT ? managed : suggestions;
      output.put(mapping.destinationFieldId(), converted);
    }
    return new Prepared(confirmation, managed, suggestions);
  }

  /** Call after the ordinary deterministic participant locks, on the same physical transaction. */
  static void verify(RecordView current, RecordReferenceMappingObservation expected) {
    if (expected.targetRecordId() == null
        ? current != null
        : current == null
            || !expected.targetRecordId().equals(current.id())
            || !Objects.equals(expected.targetVersion(), current.version())) {
      throw conflict();
    }
  }

  private static LogicalValue clear(LogicalFieldDefinition destination) {
    if (!destination.nullable()) {
      throw conflict();
    }
    return LogicalValue.nullValue();
  }

  record BoundField(
      RecordReferenceMapping mapping,
      LogicalFieldDefinition target,
      LogicalFieldDefinition destination) {
    BoundField {
      if (mapping == null
          || target == null
          || destination == null
          || !mapping.targetFieldId().equals(target.fieldId())
          || !mapping.destinationFieldId().equals(destination.fieldId())
          || !mapping.conversion().supports(target.type(), destination.type())) {
        throw incompatible();
      }
    }
  }

  /**
   * No external accumulator is mutated; retries rebuild a fresh result and provenance observation.
   */
  record Prepared(
      RecordReferenceMappingObservation observed,
      Map<String, LogicalValue> managed,
      Map<String, LogicalValue> suggestions) {
    Prepared {
      managed = Collections.unmodifiableMap(new LinkedHashMap<>(managed));
      suggestions = Collections.unmodifiableMap(new LinkedHashMap<>(suggestions));
    }
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "Reference mapping source, confirmation or field authorization changed");
  }

  private static DnaRuntimeException incompatible() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
        "Reference mapping metadata is incompatible with its frozen field identities");
  }
}
