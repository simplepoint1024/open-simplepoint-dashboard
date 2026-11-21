package org.simplepoint.plugin.dna.runtime.api.schema;

import java.util.List;
import java.util.Set;

/**
 * Platform-owned pure association storage, not an ordinary business or owned-detail model. The two
 * reference columns are populated together for an active edge and cleared together when unlinked.
 * The retained system record identity/version fences delayed commands after unlink.
 */
public record CollectionModelRelation(
    String relationId, String sourceReferenceId, String targetReferenceId) {
  /** Relation and endpoint identities are independent of columns, labels and deployment hosts. */
  public CollectionModelRelation {
    for (var identity : new String[] {relationId, sourceReferenceId, targetReferenceId}) {
      if (identity == null || !identity.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
        throw new IllegalArgumentException("Invalid collection relation identity");
      }
    }
    if (sourceReferenceId.equals(targetReferenceId)) {
      throw new IllegalArgumentException("Collection endpoints require distinct references");
    }
  }

  /**
   * The registry owns one model per application/form identity. Collections require a distinct,
   * deterministic synthetic form identity, just as owned details do, while their source reference
   * continues to point to the real root model. Labels and physical locations never participate.
   */
  public String modelFormId(String sourceFormId) {
    if (sourceFormId == null
        || sourceFormId.isBlank()
        || sourceFormId.length() > 64
        || !sourceFormId.equals(sourceFormId.trim())) {
      throw new IllegalArgumentException("A bounded exact source form identity is required");
    }
    String identity =
        "simplepoint.collection.model.v1|"
            + sourceFormId.length()
            + ":"
            + sourceFormId
            + "|"
            + relationId;
    return java.util
        .UUID
        .nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toString();
  }

  /** Rejects business payload columns, ambiguous endpoints and non-collection uniqueness. */
  void validate(
      List<LogicalFieldDefinition> fields,
      List<LogicalIndexDefinition> indexes,
      OwnedModelRelation owner,
      Boolean tenantRecordIdentity,
      List<LogicalReferenceDefinition> references) {
    if (owner != null
        || !Boolean.TRUE.equals(tenantRecordIdentity)
        || fields.size() != 2
        || references.size() != 2
        || !references.stream()
            .map(LogicalReferenceDefinition::relationId)
            .collect(java.util.stream.Collectors.toSet())
            .equals(Set.of(sourceReferenceId, targetReferenceId))
        || references.stream().anyMatch(LogicalReferenceDefinition::unique)
        || fields.stream()
            .anyMatch(
                field ->
                    field.type() != LogicalFieldType.UUID
                        || !field.nullable()
                        || field.defaultValue() != null)) {
      throw new IllegalArgumentException(
          "Collection storage requires two nullable root references");
    }
    var unique = indexes.stream().filter(LogicalIndexDefinition::unique).toList();
    var codes =
        fields.stream()
            .map(LogicalFieldDefinition::code)
            .collect(java.util.stream.Collectors.toSet());
    if (unique.size() != 1
        || unique.getFirst().fieldCodes().size() != 2
        || !Set.copyOf(unique.getFirst().fieldCodes()).equals(codes)) {
      throw new IllegalArgumentException(
          "Collection storage requires exactly one unique endpoint pair");
    }
  }
}
