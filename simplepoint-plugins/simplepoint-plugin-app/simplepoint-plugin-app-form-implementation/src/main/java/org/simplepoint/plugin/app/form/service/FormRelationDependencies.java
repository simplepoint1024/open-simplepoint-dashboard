package org.simplepoint.plugin.app.form.service;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceField;

/**
 * Freezes target-field types at publication; later projections never reinterpret current targets.
 */
final class FormRelationDependencies {
  private FormRelationDependencies() {}

  static List<LogicalReferenceField> freeze(
      FormRelationDefinition relation, ApplicationFormDocument target) {
    var ids = new TreeSet<String>();
    relation.mappings().forEach(mapping -> ids.add(mapping.targetFieldId()));
    var view = relation.referenceView();
    if (view != null) {
      ids.add(view.labelFieldId());
      ids.addAll(view.searchFieldIds());
      ids.addAll(view.displayFieldIds());
      collect(view.candidateFilter(), ids);
      view.contextFilters().forEach(filter -> ids.add(filter.targetFieldId()));
    }
    if (relation.qualification() != null) {
      collect(relation.qualification().condition(), ids);
    }
    var fields =
        target.fields().stream()
            .filter(FormFieldDefinition::persistent)
            .collect(Collectors.toMap(FormFieldDefinition::fieldId, Function.identity()));
    return ids.stream()
        .map(
            id -> {
              var field = fields.get(id);
              if (field == null) {
                throw new ApplicationFormException(
                    ApplicationFormErrorCode.DOCUMENT_INVALID,
                    "Reference dependencies require persistent target fields");
              }
              return new LogicalReferenceField(id, LogicalFieldType.valueOf(field.type().name()));
            })
        .toList();
  }

  private static void collect(FormCondition condition, Set<String> ids) {
    if (condition == null) {
      return;
    }
    if (condition.fieldId() != null) {
      ids.add(condition.fieldId());
    }
    condition.children().forEach(child -> collect(child, ids));
  }
}
