package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceSnapshot;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;

/**
 * Pure frozen identity conversion and visible-source filtering; never performs live target reads.
 */
final class FormReferenceSnapshots {
  private FormReferenceSnapshots() {}

  static List<FormReferenceSnapshot> capture(
      PublishedApplicationForm form,
      OwnedRecordView aggregate,
      ApplicationFormRecordView projected) {
    var result = new ArrayList<FormReferenceSnapshot>();
    for (var label : aggregate.referenceSnapshots()) {
      var relation = relation(form, label.relationId());
      var binding =
          form.relationBindings().stream()
              .filter(value -> value.relationId().equals(label.relationId()))
              .findFirst()
              .orElseThrow(FormReferenceSnapshots::invalid);
      String sourceModel =
          relation.sourceField().detailId() == null
              ? form.physicalModelId()
              : form.detailBindings().stream()
                  .filter(value -> value.detailId().equals(relation.sourceField().detailId()))
                  .findFirst()
                  .orElseThrow(FormReferenceSnapshots::invalid)
                  .physicalModelId();
      if (!sourceModel.equals(label.sourceModelId())
          || !relation.sourceField().fieldId().equals(label.sourceFieldId())
          || !binding.targetPhysicalModelId().equals(label.targetModelId())
          || !binding.target().equals(relation.target())
          || !binding.target().applicationId().equals(label.targetAppId())
          || !binding.target().formId().equals(label.targetFormId())
          || !binding.targetResourceCode().equals(label.targetResourceCode())
          || !relation.referenceView().labelFieldId().equals(label.labelFieldId())) {
        throw invalid();
      }
      result.add(
          new FormReferenceSnapshot(
              label.relationId(),
              relation.sourceField().detailId(),
              label.sourceRecordId(),
              label.sourceFieldId(),
              label.targetRecordId(),
              label.targetVersion(),
              label.labelFieldId(),
              label.label()));
    }
    return visible(form, projected, result);
  }

  static List<FormReferenceSnapshot> visible(
      PublishedApplicationForm form,
      ApplicationFormRecordView projected,
      List<FormReferenceSnapshot> captured) {
    var result = new ArrayList<FormReferenceSnapshot>();
    var details =
        new java.util.HashMap<
            String, java.util.Map<String, FormDetailRecordCoordinator.DetailRow>>();
    for (var label : captured) {
      var relation = relation(form, label.relationId());
      if (!Objects.equals(label.detailId(), relation.sourceField().detailId())
          || !label.fieldId().equals(relation.sourceField().fieldId())
          || !label.labelFieldId().equals(relation.referenceView().labelFieldId())) {
        throw invalid();
      }
      Object reference;
      if (label.detailId() == null) {
        if (!projected.id().equals(label.sourceRecordId())) {
          throw invalid();
        }
        reference = projected.values().get(label.fieldId());
      } else {
        var indexed =
            details.computeIfAbsent(
                label.detailId(),
                id -> {
                  var rows =
                      new java.util.LinkedHashMap<String, FormDetailRecordCoordinator.DetailRow>();
                  for (var value :
                      FormDetailRecordCoordinator.parseRows(
                          projected.values().getOrDefault(id, List.of()))) {
                    if (value.id() == null || rows.putIfAbsent(value.id(), value) != null) {
                      throw invalid();
                    }
                  }
                  return rows;
                });
        var row = indexed.get(label.sourceRecordId());
        if (row == null) {
          continue;
        }
        reference = row.values().get(label.fieldId());
      }
      if (reference == null || "***".equals(reference)) {
        continue;
      }
      if (!(reference instanceof String id) || !id.equalsIgnoreCase(label.targetRecordId())) {
        throw invalid();
      }
      result.add(label);
    }
    return List.copyOf(result);
  }

  private static FormRelationDefinition relation(PublishedApplicationForm form, String id) {
    var relation =
        form.document().relations().stream()
            .filter(value -> value.relationId().equals(id))
            .findFirst()
            .orElseThrow(FormReferenceSnapshots::invalid);
    if (relation.sourceField() == null || relation.referenceView() == null) {
      throw invalid();
    }
    if (form.relationBindings().stream()
        .noneMatch(
            binding ->
                binding.relationId().equals(id)
                    && binding.target().equals(relation.target())
                    && relation
                        .referenceView()
                        .labelFieldId()
                        .equals(binding.snapshotLabelFieldId()))) {
      throw invalid();
    }
    return relation;
  }

  private static ApplicationFormException invalid() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.PERSISTENCE_FAILED,
        "Reference snapshot does not match its frozen form identities");
  }
}
