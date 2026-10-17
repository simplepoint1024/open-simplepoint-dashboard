package org.simplepoint.plugin.app.form.service;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.validation.FormViolation;
import org.simplepoint.core.validation.InvalidFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalJsonValues;

/** Publication-only checks; incomplete designs must remain saveable as drafts. */
final class FormPublicationValidator {

  private FormPublicationValidator() {
  }

  /** Requires an input or default for every potentially required field; this is not a general satisfiability proof. */
  static void validate(final ApplicationFormDocument document) {
    Set<String> editable = new HashSet<>();
    Set<String> visited = new HashSet<>();
    ArrayDeque<String> pending = new ArrayDeque<>();
    document.scenes().stream().filter(scene -> scene.type() == FormSceneType.MAIN)
        .forEach(scene -> pending.add(scene.sceneId()));
    while (!pending.isEmpty()) {
      String sceneId = pending.removeFirst();
      if (visited.add(sceneId)) {
        document.scenes().stream().filter(scene -> scene.sceneId().equals(sceneId))
            .forEach(scene -> collect(scene.root(), false, editable, pending));
      }
    }
    var missing = FormSubmissionRequirements.compile(document).stream()
        .filter(requirement -> requirement.predicate() != FormSubmissionRequirements.Constant.FALSE
            || requirement.field().submitRequired()
                && requirement.field().effectiveWritePolicy() == FormFieldWritePolicy.CREATE_ONLY)
        .map(FormSubmissionRequirements.Requirement::field)
        .filter(field -> !editable.contains(field.fieldId()) || field.access() != null
            && !field.access().allows(null)
            && (field.effectiveWritePolicy() == FormFieldWritePolicy.CREATE_ONLY
                || !field.access().allows(org.simplepoint.plugin.app.form.api.model.FormRecordState.DRAFT)))
        .filter(field -> !hasDefault(field.defaultValue()))
        .map(field -> new FormViolation("fields." + field.fieldId(), "input", Map.of(),
            "app.publication.requiredInput"))
        .toList();
    if (!missing.isEmpty()) {
      throw new InvalidFormException(missing);
    }
  }

  private static void collect(
      final FormComponentDefinition node,
      final boolean inheritedReadOnly,
      final Set<String> editable,
      final ArrayDeque<String> pending
  ) {
    boolean readOnly = inheritedReadOnly || node.readOnly();
    if (!readOnly && node.fieldId() != null) {
      editable.add(node.fieldId());
    }
    if (!readOnly && node.targetSceneId() != null
        && ("action.open-dialog".equals(node.componentType())
            || "action.open-drawer".equals(node.componentType()))) {
      pending.add(node.targetSceneId());
    }
    node.children().forEach(child -> collect(child, readOnly, editable, pending));
  }

  private static boolean hasDefault(final FormValue value) {
    if (value == null) {
      return false;
    }
    return switch (value.kind()) {
      case NULL -> false;
      case STRING -> !FormRuleEvaluator.empty(value.value());
      case JSON -> {
        try {
          yield !LogicalJsonValues.empty(value.value());
        } catch (IllegalArgumentException exception) {
          yield false;
        }
      }
      default -> true;
    };
  }
}
