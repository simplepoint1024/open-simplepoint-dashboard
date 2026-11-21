package org.simplepoint.plugin.app.form.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceConversion;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/** Pure projection of a frozen APP relation snapshot; no target lookup or runtime authorization. */
final class FormRelationPublicationProjection {
  private FormRelationPublicationProjection() {}

  static Map<String, FormRelationBinding> bindings(
      ApplicationFormDocument document, List<FormRelationBinding> bindings) {
    FormRelationDocumentValidator.validate(document);
    if (bindings == null
        || bindings.size() > 50
        || bindings.size() != document.relations().size()) {
      throw invalid();
    }
    Map<String, FormRelationBinding> byId;
    try {
      byId =
          bindings.stream()
              .collect(
                  Collectors.toUnmodifiableMap(
                      FormRelationBinding::relationId, Function.identity()));
    } catch (IllegalStateException | NullPointerException failure) {
      throw invalid();
    }
    for (var relation : document.relations()) {
      var binding = byId.get(relation.relationId());
      if (binding == null || !relation.target().equals(binding.target())) {
        throw invalid();
      }
      if (!relation.retired()
          && binding.snapshotLabelFieldId() != null
          && (relation.referenceView() == null
              || !binding.snapshotLabelFieldId().equals(relation.referenceView().labelFieldId()))) {
        throw invalid();
      }
      var successor = relation.retired() ? byId.get(relation.retiredByCollectionId()) : null;
      if (successor != null && !binding.targetPhysicalModelId().equals(successor.targetPhysicalModelId())) {
        throw invalid();
      }
    }
    return byId;
  }

  static List<LogicalReferenceDefinition> references(
      ApplicationFormDocument document, List<FormRelationBinding> bindings, String detailId) {
    var byId = bindings(document, bindings);
    return document.relations().stream()
        .filter(relation -> relation.sourceField() != null && Objects.equals(detailId, relation.sourceField().detailId()))
        .sorted(java.util.Comparator.comparing(relation -> relation.relationId()))
        .map(
            relation ->
                new LogicalReferenceDefinition(
                    relation.relationId(),
                    relation.sourceField().fieldId(),
                    byId.get(relation.relationId()).targetPhysicalModelId(),
                    relation.cardinality() == FormRelationCardinality.ONE_TO_ONE,
                    byId.get(relation.relationId()).targetFields()))
        .toList();
  }

  static List<RecordReferenceRule> rules(
      ApplicationFormDocument document, List<FormRelationBinding> bindings) {
    var byId = bindings(document, bindings);
    if (document.relations().stream().noneMatch(relation -> relation.sourceField() != null)) {
      return null;
    } // Preserve legacy governance JSON exactly.
    return document.relations().stream()
        .filter(relation -> relation.sourceField() != null)
        .sorted(java.util.Comparator.comparing(relation -> relation.relationId()))
        .map(
            relation -> {
              var binding = byId.get(relation.relationId());
              if (relation.retired()) {
                return new RecordReferenceRule(
                    "2",
                    relation.relationId(),
                    new RecordValidationField("parent", relation.sourceField().fieldId()),
                    binding.targetPhysicalModelId(),
                    binding.target().applicationId(),
                    binding.target().formId(),
                    binding.targetResourceCode(),
                    null,
                    null,
                    null,
                    List.of(),
                    true);
              }
              return new RecordReferenceRule(
                  "1",
                  relation.relationId(),
                  new RecordValidationField(
                      relation.sourceField().detailId() == null
                          ? "parent"
                          : relation.sourceField().detailId(),
                      relation.sourceField().fieldId()),
                  binding.targetPhysicalModelId(),
                  binding.target().applicationId(),
                  binding.target().formId(),
                  binding.targetResourceCode(),
                  FormRelationQualificationProjection.eligibility(relation.qualification()),
                  FormRelationQualificationProjection.recheck(relation.qualification()),
                  binding.snapshotLabelFieldId(),
                  relation.mappings().stream()
                      .filter(mapping -> mapping.mode() != FormReferenceMapping.Mode.LIVE)
                      .sorted(java.util.Comparator.comparing(mapping -> mapping.mappingId()))
                      .map(
                          mapping ->
                              new RecordReferenceMapping(
                                  mapping.mappingId(),
                                  mapping.targetFieldId(),
                                  mapping.destinationFieldId(),
                                  RecordReferenceMapping.Mode.valueOf(mapping.mode().name()),
                                  RecordReferenceConversion.valueOf(mapping.conversion().name()),
                                  RecordReferenceMapping.NullPolicy.valueOf(
                                      mapping.nullPolicy().name())))
                      .toList());
            })
        .toList();
  }

  static List<RecordCollectionRule> collections(
      ApplicationFormDocument document,
      List<FormRelationBinding> targets,
      List<FormCollectionSchemaBinding> edges,
      String sourceModelId,
      FormStorageProjectionCompiler storage) {
    var definitions = document.relations().stream()
        .filter(relation -> relation.cardinality() == FormRelationCardinality.MANY_TO_MANY)
        .toList();
    if (edges.size() != definitions.size()
        || edges.stream().map(FormCollectionSchemaBinding::relationId).distinct().count() != edges.size()) {
      throw invalid();
    }
    if (definitions.isEmpty()) {
      return null;
    }
    var targetById = bindings(document, targets);
    var edgeById = edges.stream()
        .collect(Collectors.toMap(FormCollectionSchemaBinding::relationId, Function.identity()));
    var mutable = new RecordValidationCondition(
        RecordValueConditionOperator.EQUALS,
        new RecordValidationField("parent", FormStorageProjectionCompiler.RECORD_STATE_FIELD_ID),
        List.of(new LogicalValue(LogicalValueKind.STRING, "DRAFT")), List.of());
    return definitions.stream().map(relation -> {
      var target = targetById.get(relation.relationId());
      var edge = edgeById.get(relation.relationId());
      if (edge == null || sourceModelId.equals(edge.physicalModelId())
          || !storage.compileCollection(sourceModelId, target).checksum().equals(edge.schema().checksum())) {
        throw invalid();
      }
      return new RecordCollectionRule(
          "1", relation.relationId(), edge.physicalModelId(), edge.schema(),
          target.targetPhysicalModelId(), target.target().applicationId(), target.target().formId(),
          target.targetResourceCode(), FormRelationQualificationProjection.eligibility(relation.qualification()),
          mutable, target.snapshotLabelFieldId());
    }).toList();
  }

  private static ApplicationFormException invalid() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.PERSISTENCE_FAILED,
        "Frozen relation bindings do not match the immutable form document");
  }
}
