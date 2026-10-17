package org.simplepoint.plugin.app.form.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
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
              if (field == null
                  || !Set.of(FormStorageMode.STORED, FormStorageMode.REFERENCE)
                      .contains(field.storageMode())
                  || variable == null
                  || !Set.of("start", "both").contains(variable.scope())
                  || !targets.add(name)
                  || !compatible(field.type(), variable.type())) {
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

  static boolean compatible(FormFieldType field, String variable) {
    return switch (variable) {
      case "decimal" ->
          Set.of(FormFieldType.INT32, FormFieldType.INT64, FormFieldType.DECIMAL).contains(field);
      case "number" -> field == FormFieldType.INT32;
      case "boolean" -> field == FormFieldType.BOOLEAN;
      case "date" -> field == FormFieldType.DATE;
      case "date-time" -> field == FormFieldType.DATETIME;
      case "user", "role" -> field == FormFieldType.STRING || field == FormFieldType.UUID;
      case "string" ->
          Set.of(FormFieldType.STRING, FormFieldType.TEXT, FormFieldType.UUID, FormFieldType.TIME)
              .contains(field);
      default -> false;
    };
  }

  private static Map<String, FormFieldDefinition> fields(PublishedApplicationForm form) {
    return form.document().fields().stream()
        .collect(Collectors.toMap(FormFieldDefinition::fieldId, Function.identity()));
  }

  private static ApplicationFormException invalid(String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.RECORD_INVALID, message);
  }
}
