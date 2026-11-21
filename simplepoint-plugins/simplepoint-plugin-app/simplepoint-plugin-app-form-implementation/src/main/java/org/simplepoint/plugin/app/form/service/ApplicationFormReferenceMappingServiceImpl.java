package org.simplepoint.plugin.app.form.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingObservation;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingQuery;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingView;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormReferenceMappingService;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;
import org.springframework.stereotype.Service;

/** Read-only mapping adapter; source operation, field policy and exact target stay server-owned. */
@Service
public class ApplicationFormReferenceMappingServiceImpl
    implements ApplicationFormReferenceMappingService {
  private final PublishedApplicationFormResolver forms;
  private final ApplicationFormRecordServiceImpl records;
  private final DnaOwnedRecordService dna;
  private final FormReferenceMappingAuthority mappingAuthority;

  /** Reuses the published form, record authorization and trusted DNA read boundaries. */
  public ApplicationFormReferenceMappingServiceImpl(
      PublishedApplicationFormResolver forms,
      ApplicationFormRecordServiceImpl records,
      DnaOwnedRecordService dna,
      FormReferenceMappingAuthority mappingAuthority) {
    this.forms = forms;
    this.records = records;
    this.dna = dna;
    this.mappingAuthority = mappingAuthority;
  }

  @Override
  public FormReferenceMappingView observe(
      String applicationId, String pageId, String relationId, FormReferenceMappingQuery query) {
    if (query == null) {
      throw invalid("A mapping query is required");
    }
    var form = forms.resolve(applicationId, pageId);
    forms.requireRead(form);
    forms.requireAction(form, query.recordId() == null ? "records.create" : "records.edit");
    ApplicationFormRuntimeIdentity.requireCurrent(
        query.runtime(), form.formId(), form.formVersion());
    var relation =
        form.document().relations().stream()
            .filter(candidate -> candidate.relationId().equals(relationId))
            .findFirst()
            .orElseThrow(() -> invalid("The reference mapping is unavailable"));
    if (relation.sourceField() == null || relation.mappings().isEmpty()) {
      throw invalid("The reference has no scalar mappings");
    }
    var defaults = Set.copyOf(query.applyDefaultMappingIds());
    if (!relation.mappings().stream()
        .filter(mapping -> mapping.mode() == FormReferenceMapping.Mode.EDITABLE_DEFAULT)
        .map(FormReferenceMapping::mappingId)
        .collect(Collectors.toSet())
        .containsAll(defaults)) {
      throw invalid("The selected default mappings are unavailable");
    }
    String detailId = relation.sourceField().detailId();
    if (detailId == null && (query.sourceRowId() != null || query.newDetailRow() != null)) {
      throw invalid("A parent reference cannot identify a detail row");
    }
    FormRecordState state = null;
    if (query.recordId() != null) {
      var current = records.get(form, query.recordId());
      if (current.version() != query.expectedVersion()) {
        throw new ApplicationFormException(
            ApplicationFormErrorCode.RECORD_CONFLICT, "Record changed before mapping observation");
      }
      state = Boolean.TRUE.equals(query.newDetailRow()) ? null : current.state();
      if (query.sourceRowId() != null) {
        Object rows = current.values().get(detailId);
        if (!(rows instanceof List<?> list)
            || list.stream()
                    .filter(
                        row ->
                            row instanceof Map<?, ?> item
                                && query.sourceRowId().equals(item.get("id")))
                    .count()
                != 1) {
          throw invalid("The source detail row is unavailable");
        }
      }
    }
    var fields =
        detailId == null
            ? form.document().fields()
            : form.document().details().stream()
                .filter(detail -> detail.detailId().equals(detailId))
                .findFirst()
                .orElseThrow(() -> invalid("The reference detail is unavailable"))
                .fields();
    requireWritable(fields, relation.sourceField().fieldId(), state);
    var snapshots = new java.util.HashSet<String>();
    var suggestions = new java.util.HashSet<String>();
    var requiredSuggestions = new java.util.HashSet<String>();
    for (var mapping : relation.mappings()) {
      if (mapping.mode() == FormReferenceMapping.Mode.SERVER_SNAPSHOT) {
        snapshots.add(mapping.destinationFieldId());
        requireWritable(fields, mapping.destinationFieldId(), state);
      } else if (defaults.contains(mapping.mappingId())) {
        suggestions.add(mapping.destinationFieldId());
        if (mapping.nullPolicy() != FormReferenceMapping.NullPolicy.PRESERVE) {
          requiredSuggestions.add(mapping.destinationFieldId());
        }
        requireWritable(fields, mapping.destinationFieldId(), state);
      }
    }
    try {
      // Missing targets remain absent for explicit clearing. DNA rechecks the current installed
      // relation, resource generation, row/field access and original copied-value dependencies.
      var authority =
          mappingAuthority.observe(
              form, relation, query.targetRecordId(), query.applyDefaultMappingIds());
      var result =
          dna.observeMapping(
              authority,
              new RecordReferenceMappingQuery(
                  "1",
                  form.physicalModelId(),
                  new RecordSchemaReference(form.physicalModelVersion(), form.storageChecksum()),
                  form.writeAdmissionVersion(),
                  relationId,
                  query.targetRecordId(),
                  query.applyDefaultMappingIds(),
                  30_000));
      if (result == null
          || !java.util.Objects.equals(
              query.targetRecordId(), result.observation().targetRecordId())
          || !snapshots.equals(result.snapshots().keySet())
          || !suggestions.containsAll(result.defaults().keySet())
          || !result.defaults().keySet().containsAll(requiredSuggestions)) {
        throw new ApplicationFormException(
            ApplicationFormErrorCode.RECORD_OPERATION_FAILED, "Mapping response is inconsistent");
      }
      var observed = result.observation();
      return new FormReferenceMappingView(
          new FormReferenceMappingObservation(
              observed.revision(), observed.targetRecordId(), observed.targetVersion()),
          values(result.snapshots()),
          values(result.defaults()));
    } catch (DnaRuntimeException failure) {
      throw FormRecordFailures.fromDna(failure);
    } catch (IllegalArgumentException failure) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RECORD_INVALID,
          "Reference mapping observation is invalid",
          failure);
    }
  }

  private static void requireWritable(
      List<FormFieldDefinition> fields, String id, FormRecordState state) {
    var field =
        fields.stream()
            .filter(candidate -> candidate.fieldId().equals(id))
            .findFirst()
            .orElseThrow(() -> invalid("The mapped field is unavailable"));
    if (!field.allowsWrite(state)) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "The mapped field is not writable");
    }
  }

  private static Map<String, FormValue> values(Map<String, LogicalValue> values) {
    var result = new LinkedHashMap<String, FormValue>();
    values.forEach(
        (id, value) ->
            result.put(
                id,
                new FormValue(FormValueKind.valueOf(value.kind().name()), value.canonicalValue())));
    return result;
  }

  private static ApplicationFormException invalid(String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.RECORD_INVALID, message);
  }
}
