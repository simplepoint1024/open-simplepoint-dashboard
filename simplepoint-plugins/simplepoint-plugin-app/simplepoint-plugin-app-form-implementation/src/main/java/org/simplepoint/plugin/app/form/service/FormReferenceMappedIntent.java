package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.Objects;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingIntent;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingConfirmation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingObservation;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope;

/** Converts only frozen form identities into physical confirmation coordinates. */
final class FormReferenceMappedIntent {
  private FormReferenceMappedIntent() {}

  static MappedOwnedRecordCommand bind(
      PublishedApplicationForm form,
      OwnedRecordCommand command,
      RelatedRecordScope relatedScope,
      FormReferenceMappingIntent intent) {
    Objects.requireNonNull(intent, "intent");
    var confirmations = new ArrayList<RecordReferenceMappingConfirmation>();
    for (var confirmation : intent.confirmations()) {
      var relation =
          form.document().relations().stream()
              .filter(candidate -> candidate.relationId().equals(confirmation.relationId()))
              .findFirst()
              .orElseThrow(FormReferenceMappedIntent::invalid);
      if (relation.retired() || relation.sourceField() == null
          || relation.mappings().isEmpty()
          || !Objects.equals(relation.sourceField().detailId(), confirmation.detailId())
          || !relation.mappings().stream()
              .filter(mapping -> mapping.mode() == FormReferenceMapping.Mode.EDITABLE_DEFAULT)
              .map(FormReferenceMapping::mappingId)
              .collect(Collectors.toSet())
              .containsAll(confirmation.applyDefaultMappingIds())) {
        throw invalid();
      }
      String model = null;
      if (confirmation.detailId() != null) {
        var bindings =
            form.detailBindings().stream()
                .filter(binding -> binding.detailId().equals(confirmation.detailId()))
                .toList();
        if (bindings.size() != 1) {
          throw invalid();
        }
        model = bindings.getFirst().physicalModelId();
      }
      var observed = confirmation.observation();
      confirmations.add(
          new RecordReferenceMappingConfirmation(
              confirmation.relationId(),
              model,
              confirmation.rowIndex(),
              confirmation.sourceRowId(),
              new RecordReferenceMappingObservation(
                  observed.revision(), observed.targetRecordId(), observed.targetVersion()),
              confirmation.applyDefaultMappingIds()));
    }
    try {
      // The DNA contract checks exact collection positions and saved row identities. Reordering
      // must move a confirmation with its row; no lookup by position alone can repair a mismatch.
      return new MappedOwnedRecordCommand("1", command, relatedScope, confirmations);
    } catch (IllegalArgumentException failure) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RECORD_INVALID,
          "Mapping confirmation does not match the record intent",
          failure);
    }
  }

  private static ApplicationFormException invalid() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RECORD_INVALID,
        "The form reference mapping confirmation is invalid");
  }
}
