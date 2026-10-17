package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.springframework.stereotype.Component;

/** Checks cross-page requirements without returning privileged validation fields to the browser. */
@Component
public class FormRecordInvariantValidator {
  private final FormRecordInvariantService invariants;
  private final FormRecordPayloadCompiler payloads;
  private final DnaFormRuntimeService dna;

  /** Keeps metadata locks separate from row-scoped DNA reads and the optimistic mutation. */
  public FormRecordInvariantValidator(final FormRecordInvariantService invariants,
      final FormRecordPayloadCompiler payloads, final DnaFormRuntimeService dna) {
    this.invariants = invariants;
    this.payloads = payloads;
    this.dna = dna;
  }

  /** Applies every active historical page to the new record's persisted values and storage defaults. */
  public void create(final PublishedApplicationForm form, final Map<String, Object> values, final FormRecordState target) {
    var plan = invariants.resolve(form);
    plan.validate(payloads, plan.createDefaults(), values, null, target);
  }

  /** Every batch must describe the same optimistic revision; mutation still checks that revision and admission. */
  public void update(final PublishedApplicationForm form, final DnaInvocationContext validationContext,
      final String recordId, final long expectedVersion, final Map<String, Object> values, final FormRecordState target) {
    var plan = invariants.resolve(form);
    if (plan.documents().isEmpty()) {
      return;
    }
    var fields = plan.governing().fields().stream().filter(field -> field.storageMode() == FormStorageMode.STORED).toList();
    Map<String, Object> existing = new LinkedHashMap<>();
    FormRecordState current = null;
    // Reserve one projection slot for state, leaving the public DNA limit of 100 unchanged.
    for (int start = 0; start < Math.max(fields.size(), 1); start += 99) {
      var batch = fields.subList(start, Math.min(start + 99, fields.size()));
      var codes = new ArrayList<>(batch.stream().map(FormFieldDefinition::storageCode).toList());
      codes.add(FormStorageProjectionCompiler.RECORD_STATE_CODE);
      var record = dna.getRecord(validationContext, new RecordLookupQuery(form.physicalModelId(),
          new RecordSchemaReference(plan.physicalVersion(), plan.storageChecksum()), recordId, codes));
      if (record.version() != expectedVersion || !record.id().equals(recordId) || !record.values().keySet().containsAll(codes)) {
        throw new ApplicationFormException(ApplicationFormErrorCode.RECORD_CONFLICT, "The validation record changed or its projection is incomplete");
      }
      FormRecordState state;
      try {
        state = FormRecordState.valueOf(String.valueOf(record.values().get(FormStorageProjectionCompiler.RECORD_STATE_CODE)));
      } catch (IllegalArgumentException failure) {
        throw new ApplicationFormException(ApplicationFormErrorCode.RECORD_INVALID, "The stored record state is invalid", failure);
      }
      if (current != null && current != state) {
        throw new ApplicationFormException(ApplicationFormErrorCode.RECORD_CONFLICT, "The validation record state changed");
      }
      current = state;
      batch.forEach(field -> existing.put(field.fieldId(), FormChoiceValues.displayValue(field, record.values().get(field.storageCode()))));
    }
    plan.validate(payloads, existing, values, current, target);
  }
}
