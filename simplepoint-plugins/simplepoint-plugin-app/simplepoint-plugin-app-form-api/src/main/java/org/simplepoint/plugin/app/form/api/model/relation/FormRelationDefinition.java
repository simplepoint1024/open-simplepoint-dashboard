package org.simplepoint.plugin.app.form.api.model.relation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.simplepoint.api.locale.LocalizedText;
import org.simplepoint.api.locale.LocalizedTexts;
import org.simplepoint.api.schema.LocalizedField;

/**
 * Owning-side relation contract. The enclosing document owns the source form identity. Single
 * references always point to an independent root record; collections own an edge store. Version 1
 * uses same-tenant, same-store references and RESTRICT deletion only.
 */
public record FormRelationDefinition(
    String contractVersion,
    String relationId,
    FormRelationCardinality cardinality,
    @JsonInclude(JsonInclude.Include.NON_NULL) FormRelationField sourceField,
    FormRelationTarget target,
    @JsonInclude(JsonInclude.Include.NON_NULL) FormRelationQualification qualification,
    @JsonInclude(JsonInclude.Include.NON_NULL) FormReferenceView referenceView,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FormReferenceMapping> mappings,
    @JsonInclude(JsonInclude.Include.NON_NULL) @LocalizedField(maxLength = 120)
        LocalizedText collectionLabel,
    @JsonInclude(JsonInclude.Include.NON_NULL) String retiredByCollectionId,
    @JsonInclude(JsonInclude.Include.NON_NULL) FormCollectionReplacement collectionReplacement) {

  /** Retains the existing single-reference conversion contract. */
  public FormRelationDefinition(
      String contractVersion,
      String relationId,
      FormRelationCardinality cardinality,
      FormRelationField sourceField,
      FormRelationTarget target,
      FormRelationQualification qualification,
      FormReferenceView referenceView,
      List<FormReferenceMapping> mappings,
      LocalizedText collectionLabel,
      String retiredByCollectionId) {
    this(
        contractVersion,
        relationId,
        cardinality,
        sourceField,
        target,
        qualification,
        referenceView,
        mappings,
        collectionLabel,
        retiredByCollectionId,
        null);
  }

  /** Retains the original document shape when no conversion was defined. */
  public FormRelationDefinition(
      String contractVersion,
      String relationId,
      FormRelationCardinality cardinality,
      FormRelationField sourceField,
      FormRelationTarget target,
      FormRelationQualification qualification,
      FormReferenceView referenceView,
      List<FormReferenceMapping> mappings,
      LocalizedText collectionLabel) {
    this(
        contractVersion,
        relationId,
        cardinality,
        sourceField,
        target,
        qualification,
        referenceView,
        mappings,
        collectionLabel,
        null);
  }

  public FormRelationDefinition(
      String contractVersion,
      String relationId,
      FormRelationCardinality cardinality,
      FormRelationField sourceField,
      FormRelationTarget target,
      FormRelationQualification qualification,
      FormReferenceView referenceView,
      List<FormReferenceMapping> mappings) {
    this(
        contractVersion,
        relationId,
        cardinality,
        sourceField,
        target,
        qualification,
        referenceView,
        mappings,
        null);
  }

  /** Preserves historical immutable definitions that have no value mappings. */
  public FormRelationDefinition(
      String contractVersion,
      String relationId,
      FormRelationCardinality cardinality,
      FormRelationField sourceField,
      FormRelationTarget target,
      FormRelationQualification qualification,
      FormReferenceView referenceView) {
    this(
        contractVersion,
        relationId,
        cardinality,
        sourceField,
        target,
        qualification,
        referenceView,
        List.of());
  }

  /** Preserves definitions before reference presentation was introduced. */
  public FormRelationDefinition(
      String contractVersion,
      String relationId,
      FormRelationCardinality cardinality,
      FormRelationField sourceField,
      FormRelationTarget target,
      FormRelationQualification qualification) {
    this(contractVersion, relationId, cardinality, sourceField, target, qualification, null);
  }

  /** Existing immutable definitions retain absence rather than materializing a default policy. */
  public FormRelationDefinition(
      String contractVersion,
      String relationId,
      FormRelationCardinality cardinality,
      FormRelationField sourceField,
      FormRelationTarget target) {
    this(contractVersion, relationId, cardinality, sourceField, target, null, null);
  }

  /** Bounds the aggregate mapping policy and rejects ambiguous local providers. */
  public FormRelationDefinition {
    mappings = mappings == null ? List.of() : List.copyOf(mappings);
    if (mappings.size() > 32 || sourceField == null && !mappings.isEmpty()) {
      throw new IllegalArgumentException("Value mappings require a bounded single-valued relation");
    }
    var mappingIds = new HashSet<String>();
    var destinations = new HashSet<String>();
    for (var mapping : mappings) {
      if (!mappingIds.add(mapping.mappingId()) || !destinations.add(mapping.destinationFieldId())) {
        throw new IllegalArgumentException(
            "Reference mapping identities and destinations must be unique");
      }
    }
    String expectedVersion =
        collectionReplacement != null ? "3" : retiredByCollectionId != null ? "2" : "1";
    if (!expectedVersion.equals(contractVersion)) {
      throw new IllegalArgumentException("Unsupported form relation contract version");
    }
    relationId = FormRelationIdentity.require(relationId);
    Objects.requireNonNull(cardinality, "Relation cardinality is required");
    Objects.requireNonNull(target, "Relation target is required");
    if (cardinality == FormRelationCardinality.ONE_TO_MANY) {
      throw new IllegalArgumentException(
          "One-to-many views must reference the owning many-to-one relation");
    }
    if (collectionLabel != null) {
      if (cardinality != FormRelationCardinality.MANY_TO_MANY) {
        throw new IllegalArgumentException("Only collection relations have an independent label");
      }
      collectionLabel = LocalizedTexts.validate(collectionLabel, 120, 32);
    }
    if ((cardinality == FormRelationCardinality.MANY_TO_MANY) != (sourceField == null)) {
      throw new IllegalArgumentException("Only single-valued relations require a source field");
    }
    if (retiredByCollectionId != null
        && (cardinality != FormRelationCardinality.MANY_TO_ONE
            || sourceField.detailId() != null
            || relationId.equals(FormRelationIdentity.require(retiredByCollectionId)))) {
      throw new IllegalArgumentException(
          "Conversion requires a root many-to-one source and a distinct collection");
    }
    if (collectionReplacement != null
        && (cardinality != FormRelationCardinality.MANY_TO_MANY
            || retiredByCollectionId != null
            || relationId.equals(collectionReplacement.sourceRelationId()))) {
      throw new IllegalArgumentException("Replacement requires a distinct collection identity");
    }
  }

  public boolean retired() {
    return retiredByCollectionId != null;
  }

  /** Preserves authored source metadata and its historical identity. */
  public FormRelationDefinition retireBy(String collectionId) {
    return new FormRelationDefinition(
        "2",
        relationId,
        cardinality,
        sourceField,
        target,
        qualification,
        referenceView,
        mappings,
        collectionLabel,
        collectionId);
  }
}
