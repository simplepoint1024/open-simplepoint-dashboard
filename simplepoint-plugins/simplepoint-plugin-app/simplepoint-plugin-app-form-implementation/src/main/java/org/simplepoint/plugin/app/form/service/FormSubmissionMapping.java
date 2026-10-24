package org.simplepoint.plugin.app.form.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimeForm;

/** Explicit, type-checked projection from stable form fields to declared workflow variables. */
final class FormSubmissionMapping {
  static final String PROVIDER = "simplepoint-app";

  private FormSubmissionMapping() {}

  static void requireBinding(
      PublishedApplicationForm form, RuntimeForm workflow, DnaInvocationContext authority) {
    var binding = workflow.binding();
    if (binding == null
        || !PROVIDER.equals(binding.provider())
        || !form.formId().equals(binding.formId())
        || form.formVersion() != binding.version()) {
      throw invalid("Workflow must bind this exact published form version");
    }
    var fields = fields(form);
    var variables =
        workflow.formContract().variables().stream()
            .collect(Collectors.toMap(WorkflowFormContract.Variable::name, Function.identity()));
    Set<String> targets = new HashSet<>();
    binding
        .variableMappings()
        .forEach(
            (fieldId, name) -> {
              var field = fields.get(fieldId);
              var variable = variables.get(name);
              if (!FormWorkflowFields.supports(field)
                  || variable == null
                  || !Set.of("start", "both").contains(variable.scope())
                  || !targets.add(name)
                  || !FormWorkflowFields.compatible(field.type(), variable.type())) {
                throw invalid("Invalid or ambiguous form variable mapping: " + fieldId);
              }
              String policy = RecordPermissionModel.access(authority.fieldPermissions(), form.physicalModelId(),
                  field.fieldId(), field.storageCode());
              if (Set.of("HIDDEN", "MASKED").contains(policy == null ? "EDITABLE" : policy)) {
                throw new ApplicationFormException(
                    ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
                    "A hidden or masked field cannot be copied into process variables");
              }
            });
  }

  static Map<String, Object> variables(
      PublishedApplicationForm form,
      RuntimeForm workflow,
      Map<String, Object> formValues,
      Map<String, Object> submitted) {
    Map<String, Object> result = new LinkedHashMap<>(submitted);
    var variables =
        workflow.formContract().variables().stream()
            .collect(Collectors.toMap(WorkflowFormContract.Variable::name, Function.identity()));
    workflow
        .binding()
        .variableMappings()
        .forEach(
            (field, name) -> {
              if (submitted.containsKey(name)) {
                throw invalid(
                    "Mapped process variables must come from the submitted form: " + name);
              }
              Object value = formValues.get(field);
              var variable = variables.get(name);
              if (value != null && "decimal".equals(variable.type())) {
                value = new BigDecimal(value.toString()).toPlainString();
              } else if (value != null && "number".equals(variable.type())) {
                value = new BigDecimal(value.toString()).intValueExact();
              }
              result.put(name, value);
            });
    return java.util.Collections.unmodifiableMap(result);
  }

  /** Freezes consumer requirements once; retries must reuse this result from the accepted intent. */
  static List<RecordSnapshotFieldRequirement> captureRequirements(final PublishedApplicationForm form, final RuntimeForm workflow) {
    var fields = fields(form);
    var variables = workflow.formContract().variables().stream()
        .collect(Collectors.toMap(WorkflowFormContract.Variable::name, Function.identity()));
    List<RecordSnapshotFieldRequirement> requirements = workflow.binding().variableMappings().entrySet().stream()
        .sorted(Map.Entry.comparingByKey()).map(entry -> {
          var field = fields.get(entry.getKey());
          var variable = variables.get(entry.getValue());
          if (!FormWorkflowFields.supports(field) || variable == null || !FormWorkflowFields.compatible(field.type(), variable.type())) {
            throw invalid("Captured process variable mapping is invalid");
          }
          Integer maximum = switch (variable.type()) {
            case "string" -> 4000;
            case "user", "role" -> 100;
            case "date", "date-time" -> 40;
            default -> null;
          };
          boolean role = "role".equals(variable.type());
          return new RecordSnapshotFieldRequirement(field.fieldId(), variable.required(), maximum,
              "user".equals(variable.type()) ? "[A-Za-z0-9_][A-Za-z0-9_.:@-]{0,99}" : role ? "[^,]*" : null,
              role, role, role);
        }).toList();
    return requirements.isEmpty() ? null : requirements;
  }

  private static Map<String, FormFieldDefinition> fields(PublishedApplicationForm form) {
    return form.document().fields().stream()
        .collect(Collectors.toMap(FormFieldDefinition::fieldId, Function.identity()));
  }

  private static ApplicationFormException invalid(String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.RECORD_INVALID, message);
  }
}
