package org.simplepoint.plugin.app.form.api.model;

import java.util.List;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;

/** Complete versioned form design snapshot. */
public record ApplicationFormDocument(
    String contractVersion,
    List<FormFieldDefinition> fields,
    List<FormIndexDefinition> indexes,
    List<FormSceneDefinition> scenes,
    List<FormRuleDefinition> rules,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    List<FormDetailDefinition> details,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    List<FormValidationConstraint> constraints,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    FormPresentation presentation,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    List<FormRelationDefinition> relations
) {

  /** Retains the original JSON and source contract for designs without business relations. */
  public ApplicationFormDocument(String contractVersion, List<FormFieldDefinition> fields,
      List<FormIndexDefinition> indexes, List<FormSceneDefinition> scenes, List<FormRuleDefinition> rules,
      List<FormDetailDefinition> details, List<FormValidationConstraint> constraints,
      FormPresentation presentation) {
    this(contractVersion, fields, indexes, scenes, rules, details, constraints, presentation, List.of());
  }

  /** Keeps older snapshots byte compatible when presentation is absent. */
  public ApplicationFormDocument(String contractVersion, List<FormFieldDefinition> fields,
      List<FormIndexDefinition> indexes, List<FormSceneDefinition> scenes, List<FormRuleDefinition> rules,
      List<FormDetailDefinition> details, List<FormValidationConstraint> constraints) {
    this(contractVersion, fields, indexes, scenes, rules, details, constraints, null);
  }

  /** Preserves documents published before aggregate validation was introduced. */
  public ApplicationFormDocument(String contractVersion, List<FormFieldDefinition> fields,
      List<FormIndexDefinition> indexes, List<FormSceneDefinition> scenes, List<FormRuleDefinition> rules,
      List<FormDetailDefinition> details) {
    this(contractVersion, fields, indexes, scenes, rules, details, List.of());
  }

  /** Preserves the original v1 document serialization and source compatibility. */
  public ApplicationFormDocument(String contractVersion, List<FormFieldDefinition> fields,
      List<FormIndexDefinition> indexes, List<FormSceneDefinition> scenes, List<FormRuleDefinition> rules) {
    this(contractVersion, fields, indexes, scenes, rules, List.of());
  }

  /** Keeps all top-level collections immutable. */
  public ApplicationFormDocument {
    relations = relations == null ? List.of() : List.copyOf(relations);
    constraints = constraints == null ? List.of() : List.copyOf(constraints);
    details = details == null ? List.of() : List.copyOf(details);
    fields = fields == null ? List.of() : List.copyOf(fields);
    indexes = indexes == null ? List.of() : List.copyOf(indexes);
    scenes = scenes == null ? List.of() : List.copyOf(scenes);
    rules = rules == null ? List.of() : List.copyOf(rules);
  }
}
