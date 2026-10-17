package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** Browser-safe form snapshot without physical storage or index metadata. */
public record ApplicationFormRuntimeDocument(
    String contractVersion,
    List<ApplicationFormRuntimeFieldDefinition> fields,
    List<FormSceneDefinition> scenes,
    List<FormRuleDefinition> rules,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        List<ApplicationFormRuntimeDetailDefinition> details,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    List<FormValidationConstraint> constraints,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    FormPresentation presentation) {

  /** Keeps older snapshots byte compatible when presentation is absent. */
  public ApplicationFormRuntimeDocument(String contractVersion, List<ApplicationFormRuntimeFieldDefinition> fields,
      List<FormSceneDefinition> scenes, List<FormRuleDefinition> rules,
      List<ApplicationFormRuntimeDetailDefinition> details, List<FormValidationConstraint> constraints) {
    this(contractVersion, fields, scenes, rules, details, constraints, null);
  }

  /** Keeps existing runtime readers source compatible. */
  public ApplicationFormRuntimeDocument(String contractVersion, List<ApplicationFormRuntimeFieldDefinition> fields,
      List<FormSceneDefinition> scenes, List<FormRuleDefinition> rules,
      List<ApplicationFormRuntimeDetailDefinition> details) {
    this(contractVersion, fields, scenes, rules, details, List.of());
  }

  /** Preserves compatibility for callers without optional snapshot or scene metadata. */
  public ApplicationFormRuntimeDocument(
      String contractVersion,
      List<ApplicationFormRuntimeFieldDefinition> fields,
      List<FormSceneDefinition> scenes,
      List<FormRuleDefinition> rules) {
    this(contractVersion, fields, scenes, rules, List.of());
  }

  /** Keeps the runtime projection immutable. */
  public ApplicationFormRuntimeDocument {
    constraints = constraints == null ? List.of() : List.copyOf(constraints);
    details = details == null ? List.of() : List.copyOf(details);
    fields = fields == null ? List.of() : List.copyOf(fields);
    scenes = scenes == null ? List.of() : List.copyOf(scenes);
    rules = rules == null ? List.of() : List.copyOf(rules);
  }
}
