package org.simplepoint.plugin.app.form.service;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingIntent;

/** Explicit value producers for one row; only DNA validates rules requiring their final outputs. */
record FormRecordValueProviders(Set<String> managed, Set<String> deferred) {
  FormRecordValueProviders {
    managed = Set.copyOf(managed);
    deferred = Set.copyOf(deferred);
    if (!deferred.containsAll(managed)) {
      throw new IllegalArgumentException("Managed outputs must be deferred to their producer");
    }
  }

  static FormRecordValueProviders forRow(
      ApplicationFormDocument document,
      String detailId,
      Integer rowIndex,
      FormReferenceMappingIntent intent) {
    var fields =
        detailId == null
            ? document.fields()
            : document.details().stream()
                .filter(detail -> detail.detailId().equals(detailId))
                .findFirst()
                .orElseThrow(FormRecordValueProviders::invalid)
                .fields();
    var managed = new HashSet<String>();
    fields.stream()
        .filter(field -> field.storageMode() == FormStorageMode.COMPUTED)
        .forEach(field -> managed.add(field.fieldId()));
    var deferred = new HashSet<String>();
    for (var relation : document.relations()) {
      if (relation.retired() || relation.sourceField() == null
          || !Objects.equals(detailId, relation.sourceField().detailId())) {
        continue;
      }
      Set<String> selected =
          intent == null
              ? Set.of()
              : intent.confirmations().stream()
                  .filter(
                      item ->
                          item.relationId().equals(relation.relationId())
                              && Objects.equals(item.detailId(), detailId)
                              && Objects.equals(item.rowIndex(), rowIndex))
                  .flatMap(item -> item.applyDefaultMappingIds().stream())
                  .collect(java.util.stream.Collectors.toSet());
      for (var mapping : relation.mappings()) {
        if (mapping.mode() != FormReferenceMapping.Mode.EDITABLE_DEFAULT) {
          managed.add(mapping.destinationFieldId());
        } else if (selected.contains(mapping.mappingId())) {
          deferred.add(mapping.destinationFieldId());
        }
      }
    }
    deferred.addAll(managed);
    return new FormRecordValueProviders(managed, deferred);
  }

  private static ApplicationFormException invalid() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RECORD_INVALID, "The value provider's detail is unavailable");
  }
}
