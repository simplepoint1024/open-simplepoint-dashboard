package org.simplepoint.plugin.app.form.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceContextFilter.Source;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRuntimeReference;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;

/** Projects candidate inputs without exposing hidden field identities or their values. */
final class FormReferenceInputProjection {
  private FormReferenceInputProjection() {}

  static FormRuntimeReference project(
      PublishedApplicationForm form,
      FormRelationDefinition relation,
      Map<String, String> permissions) {
    return FormReferenceMappingProjection.enrich(
        form, relation, permissions, inputs(form, relation, permissions));
  }

  private static FormRuntimeReference inputs(
      PublishedApplicationForm form,
      FormRelationDefinition relation,
      Map<String, String> permissions) {
    var view = relation.referenceView();
    if (view.contextFilters().isEmpty()) {
      return new FormRuntimeReference(relation.relationId(), view.allowDrillThrough());
    }
    var inputs = new LinkedHashSet<FormRuntimeReference.ContextInput>();
    for (var filter : view.contextFilters()) {
      if (filter.source() == Source.ORGANIZATION) {
        continue;
      }
      var field = FormReferenceContextPolicy.sourceField(form.document(), relation, filter);
      String model = form.physicalModelId();
      if (filter.source() == Source.ROW) {
        model =
            form.detailBindings().stream()
                .filter(detail -> detail.detailId().equals(relation.sourceField().detailId()))
                .findFirst()
                .orElseThrow()
                .physicalModelId();
      }
      String access =
          RecordPermissionModel.access(permissions, model, field.fieldId(), field.storageCode());
      if ("HIDDEN".equals(access) || "MASKED".equals(access)) {
        return new FormRuntimeReference(
            relation.relationId(), view.allowDrillThrough(), true, false, List.of());
      }
      inputs.add(
          new FormRuntimeReference.ContextInput(filter.source(), field.fieldId(), field.type()));
    }
    return new FormRuntimeReference(
        relation.relationId(), view.allowDrillThrough(), true, null, List.copyOf(inputs));
  }
}
