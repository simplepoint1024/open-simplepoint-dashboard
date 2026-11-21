package org.simplepoint.plugin.dna.runtime.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A single-valued business reference; physical targets are resolved by the owning tenant's
 * registry.
 */
public record LogicalReferenceDefinition(
    String relationId,
    String fieldId,
    String targetModelId,
    boolean unique,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<LogicalReferenceField> targetFields) {
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

  public LogicalReferenceDefinition {
    if (relationId == null
        || !ID.matcher(relationId).matches()
        || fieldId == null
        || !ID.matcher(fieldId).matches()
        || targetModelId == null
        || !ID.matcher(targetModelId).matches()) {
      throw new IllegalArgumentException(
          "Reference requires stable relation, field and target model identities");
    }
    targetFields = canonicalTargetFields(targetFields);
  }

  /**
   * Preserves the JSON and constructor contract of references without target-field dependencies.
   */
  public LogicalReferenceDefinition(
      String relationId, String fieldId, String targetModelId, boolean unique) {
    this(relationId, fieldId, targetModelId, unique, List.of());
  }

  public static List<LogicalReferenceField> canonicalTargetFields(
      List<LogicalReferenceField> fields) {
    if (fields == null || fields.isEmpty()) return List.of();
    if (fields.size() > 128
        || fields.stream().anyMatch(java.util.Objects::isNull)
        || fields.stream().map(LogicalReferenceField::fieldId).distinct().count()
            != fields.size()) {
      throw new IllegalArgumentException(
          "Reference dependencies must contain at most 128 distinct fields");
    }
    return fields.stream().sorted(Comparator.comparing(LogicalReferenceField::fieldId)).toList();
  }

  /** Dependency declarations may evolve; the stored relationship cannot be implicitly replaced. */
  public boolean sameStorageAs(LogicalReferenceDefinition other) {
    return sameBinding(other) && unique == other.unique;
  }

  /** Allows uniqueness tightening on the same foreign key through reviewed schema evolution. */
  public boolean canEvolveTo(LogicalReferenceDefinition other) {
    return sameBinding(other) && (!unique || other.unique);
  }

  private boolean sameBinding(LogicalReferenceDefinition other) {
    return other != null
        && relationId.equals(other.relationId)
        && fieldId.equals(other.fieldId)
        && targetModelId.equals(other.targetModelId);
  }
}
