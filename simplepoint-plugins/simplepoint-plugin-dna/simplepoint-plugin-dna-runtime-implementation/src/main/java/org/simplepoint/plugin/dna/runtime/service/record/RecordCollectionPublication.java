package org.simplepoint.plugin.dna.runtime.service.record;

import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;

/** Pure declaration validation shared by publication and future collection operation admission. */
final class RecordCollectionPublication {
  private RecordCollectionPublication() {}

  static RecordCollectionBinding validate(
      RecordCollectionRule rule,
      RuntimeRecordModel source,
      RuntimeRecordModel edge,
      RuntimeRecordModel target) {
    var binding = new RecordCollectionBinding(edge, source, target);
    if (!rule.relationId().equals(binding.relationId())
        || !rule.edgeModelId().equals(edge.physicalModelId())
        || !rule.targetModelId().equals(target.physicalModelId())
        || !rule.targetAppId().equals(target.appId())
        || !rule.targetFormId().equals(target.formId())) {
      throw RecordReferenceAdmission.incompatible();
    }
    var collection = edge.activeSchema().collection();
    var sourceReference = reference(edge, collection.sourceReferenceId());
    var targetReference = reference(edge, collection.targetReferenceId());
    RecordReferenceRules.validateTargetDependencies(source, sourceReference, rule.mutableWhen());
    RecordReferenceRules.validateTargetCondition(source, rule.mutableWhen());
    RecordReferenceRules.validateTargetDependencies(target, targetReference, rule.eligibility());
    RecordReferenceRules.validateTargetCondition(target, rule.eligibility());
    RecordReferenceRules.snapshotLabel(target, targetReference, rule.snapshotLabelFieldId());
    return binding;
  }

  private static LogicalReferenceDefinition reference(RuntimeRecordModel edge, String id) {
    return edge.activeSchema().references().stream()
        .filter(value -> value.relationId().equals(id))
        .findFirst()
        .orElseThrow(RecordReferenceAdmission::incompatible);
  }
}
