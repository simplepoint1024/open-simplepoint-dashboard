package org.simplepoint.plugin.app.form.service;

import static org.simplepoint.plugin.app.form.service.FormRuleEvaluator.empty;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalJsonValues;
import org.springframework.stereotype.Component;

/** Validates field-identity payloads and compiles them to immutable storage codes. */
@Component
public class FormRecordPayloadCompiler {

  private static final int MAX_VALUES = 300;

  /** Validates one mutation and returns only persistent DNA values. */
  public Map<String, Object> compile(
      final ApplicationFormDocument document,
      final Map<String, Object> existing,
      final Map<String, Object> requested,
      final FormRecordState currentState,
      final FormRecordState targetState
  ) {
    validatedValues(document, existing, requested, currentState, targetState);
    return project(document, requested, targetState);
  }

  /** Server-only prospective values for integration preflight; never bypasses the write-time validation. */
  Map<String, Object> validatedValues(
      final ApplicationFormDocument document, final Map<String, Object> existing,
      final Map<String, Object> requested, final FormRecordState currentState, final FormRecordState targetState
  ) {
    if (requested == null || requested.size() > MAX_VALUES || targetState == null) {
      throw invalid("Form record values are invalid");
    }
    if (currentState == FormRecordState.SUBMITTED && targetState == FormRecordState.DRAFT) {
      throw invalid("A submitted record cannot return to draft state");
    }
    Map<String, FormFieldDefinition> fields = new HashMap<>();
    document.fields().forEach(field -> fields.put(field.fieldId(), field));
    Map<String, Object> merged = new LinkedHashMap<>();
    if (existing != null) {
      merged.putAll(existing);
    }
    for (Map.Entry<String, Object> entry : requested.entrySet()) {
      FormFieldDefinition field = fields.get(entry.getKey());
      if (field == null || field.storageMode() == FormStorageMode.COMPUTED) {
        throw invalid("Form record contains an unknown or computed field");
      }
      if (!field.allowsWrite(currentState)) {
        throw invalid("Field is not writable in the current record state: " + field.fieldId());
      }
      validateChoice(field, entry.getValue());
      merged.put(entry.getKey(), entry.getValue());
    }
    FormComponentValues.validate(document, requested);
    applyDefaults(fields, merged);
    if (targetState == FormRecordState.SUBMITTED) FormComponentValues.validate(document, merged);
    List<FormSubmissionRequirements.Requirement> requirements = FormSubmissionRequirements.compile(document).stream()
        .map(requirement -> requirement.forState(targetState)).toList();
    if (currentState == null) {
      requirements.stream().filter(requirement -> requirement.field().effectiveWritePolicy() == FormFieldWritePolicy.CREATE_ONLY)
          .filter(requirement -> requirement.missing(merged)).findFirst().ifPresent(requirement -> {
            throw invalid("A required create-only field must be filled before saving: " + requirement.field().fieldId());
          });
    }
    fields.values().stream()
        .filter(field -> targetState == FormRecordState.SUBMITTED || !field.allowsWrite(targetState))
        .forEach(field -> validateChoice(field, merged.get(field.fieldId())));
    validateSubmission(requirements, merged);
    if (targetState == FormRecordState.SUBMITTED) {
      requirements.stream().filter(requirement -> requirement.field().storageMode() == FormStorageMode.ATTACHMENT)
          .filter(requirement -> requirement.field().submitRequired())
          .filter(requirement -> FormAttachmentValues.parse(merged.get(requirement.field().fieldId())).isEmpty())
          .findFirst().ifPresent(requirement -> {
            throw invalid("请上传附件：" + requirement.field().label());
          });
    }
    return java.util.Collections.unmodifiableMap(merged);
  }

  /** Maps an exact intent for receipt lookup without revalidating later business state. */
  public Map<String, Object> project(
      final ApplicationFormDocument document,
      final Map<String, Object> requested,
      final FormRecordState targetState
  ) {
    if (requested == null || requested.size() > MAX_VALUES || targetState == null) {
      throw invalid("Form record values are invalid");
    }
    Map<String, FormFieldDefinition> fields = new HashMap<>();
    document.fields().forEach(field -> fields.put(field.fieldId(), field));
    Map<String, Object> storage = new LinkedHashMap<>();
    requested.forEach((fieldId, value) -> {
      FormFieldDefinition field = fields.get(fieldId);
      if (field == null || field.storageMode() == FormStorageMode.COMPUTED) {
        throw invalid("Form record contains an unknown or computed field");
      }
      if (persistent(field)) {
        storage.put(field.storageCode(), field.type() == FormFieldType.JSON && empty(value) ? null : value);
      }
    });
    storage.put(FormStorageProjectionCompiler.RECORD_STATE_CODE, targetState.name());
    return storage;
  }

  private static void validateChoice(final FormFieldDefinition field, final Object value) {
    try {
      FormFieldValidations.value(field, value);
      FormChoiceValues.validateStoredTemporal(field, value);
      if (field.storageMode() == FormStorageMode.ATTACHMENT) {
        FormAttachmentValues.parse(value);
      }
      if (field.type() == FormFieldType.JSON && value != null) {
        if (!(value instanceof String)) {
          throw new IllegalArgumentException("JSON fields require lossless JSON text");
        }
        if (!empty(value)) {
          LogicalJsonValues.canonicalize(value);
        }
      }
    } catch (IllegalArgumentException exception) {
      throw invalid("Form value is invalid or exceeds storage precision: " + field.fieldId());
    }
    if (value == null || field.choices().isEmpty()) {
      return;
    }
    try {
      String actual = FormChoiceValues.canonical(field.type(), value);
      if (field.choices().stream().anyMatch(choice -> actual.equals(
          FormChoiceValues.canonical(field.type(), choice.value())
      ))) {
        return;
      }
    } catch (IllegalArgumentException | ArithmeticException exception) {
      // Return a stable domain error without exposing the submitted value.
    }
    throw invalid("Form record choice is invalid: " + field.fieldId());
  }

  private static void applyDefaults(
      final Map<String, FormFieldDefinition> fields,
      final Map<String, Object> values
  ) {
    fields.values().forEach(field -> {
      if (!values.containsKey(field.fieldId()) && field.defaultValue() != null) {
        values.put(field.fieldId(), value(field.defaultValue()));
      }
    });
  }

  private static void validateSubmission(
      final List<FormSubmissionRequirements.Requirement> requirements,
      final Map<String, Object> values
  ) {
    Set<String> missing = new LinkedHashSet<>();
    requirements.stream()
        .filter(requirement -> requirement.missing(values))
        .forEach(requirement -> missing.add(requirement.field().label()));
    if (!missing.isEmpty()) {
      throw invalid("Required form record fields are missing: " + String.join(", ", missing));
    }
  }

  /** Shares exact default-value interpretation with historical publication inspection. */
  static Object value(final FormValue value) {
    if (value == null || value.kind() == FormValueKind.NULL) {
      return null;
    }
    return switch (value.kind()) {
      case BOOLEAN -> Boolean.parseBoolean(value.value());
      case INTEGER, DECIMAL -> new BigDecimal(value.value());
      default -> value.value();
    };
  }

  private static boolean persistent(final FormFieldDefinition field) {
    return field.storageMode() == FormStorageMode.STORED
        || field.storageMode() == FormStorageMode.REFERENCE
        || field.storageMode() == FormStorageMode.ATTACHMENT;
  }

  private static ApplicationFormException invalid(final String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.RECORD_INVALID, message);
  }
}
