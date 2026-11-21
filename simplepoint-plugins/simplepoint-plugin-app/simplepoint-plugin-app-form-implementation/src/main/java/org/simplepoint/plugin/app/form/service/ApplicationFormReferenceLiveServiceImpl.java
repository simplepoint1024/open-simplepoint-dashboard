package org.simplepoint.plugin.app.form.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveQuery;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveView;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormReferenceLiveService;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceConversion;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.ReferenceMappingConversions;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.springframework.stereotype.Service;

/** Read-only LIVE adapter; value conversion shares the exact persisted mapping type contract. */
@Service
public class ApplicationFormReferenceLiveServiceImpl
    implements ApplicationFormReferenceLiveService {
  private final PublishedApplicationFormResolver forms;
  private final FormRelationTargetResolver relations;
  private final DnaInvocationContextFactory contexts;
  private final FormRecordReadAuthority reads;
  private final DnaFormRuntimeService dna;

  /** No write service, mutation controller or workflow history enters this projection. */
  public ApplicationFormReferenceLiveServiceImpl(
      PublishedApplicationFormResolver forms,
      FormRelationTargetResolver relations,
      DnaInvocationContextFactory contexts,
      FormRecordReadAuthority reads,
      DnaFormRuntimeService dna) {
    this.forms = forms;
    this.relations = relations;
    this.contexts = contexts;
    this.reads = reads;
    this.dna = dna;
  }

  @Override
  public FormReferenceLiveView read(
      String applicationId, String pageId, String relationId, FormReferenceLiveQuery query) {
    if (query == null) {
      throw invalid();
    }
    return readMany(
            applicationId,
            pageId,
            relationId,
            query.runtime(),
            java.util.Collections.singletonList(query.targetRecordId()))
        .getFirst();
  }

  @Override
  public List<FormReferenceLiveView> readBatch(
      String applicationId,
      String pageId,
      String relationId,
      org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveBatchQuery query) {
    if (query == null) {
      throw invalid();
    }
    return readMany(applicationId, pageId, relationId, query.runtime(), query.targetRecordIds());
  }

  private List<FormReferenceLiveView> readMany(
      String applicationId,
      String pageId,
      String relationId,
      ApplicationFormRuntimeIdentity runtime,
      List<String> ids) {
    var form = forms.resolve(applicationId, pageId);
    forms.requireRead(form);
    ApplicationFormRuntimeIdentity.requireCurrent(runtime, form.formId(), form.formVersion());
    var relation =
        form.document().relations().stream()
            .filter(value -> value.relationId().equals(relationId))
            .findFirst()
            .orElseThrow(ApplicationFormReferenceLiveServiceImpl::invalid);
    var context = contexts.forForm(form);
    var source = FormRelationSourceField.resolve(form, relation.sourceField());
    if (!source.readable(context.fieldPermissions())) {
      throw denied();
    }
    var mappings =
        relation.mappings().stream()
            .filter(value -> value.mode() == FormReferenceMapping.Mode.LIVE)
            .toList();
    if (mappings.isEmpty()) {
      throw invalid();
    }
    Map<FormReferenceMapping, FormRelationSourceField> selected = new LinkedHashMap<>();
    for (var mapping : mappings) {
      var destination =
          FormRelationSourceField.resolve(
              form,
              new FormRelationField(
                  relation.sourceField().detailId(), mapping.destinationFieldId()));
      if (destination.readable(context.fieldPermissions())) {
        selected.put(mapping, destination);
      }
    }
    if (ids.isEmpty()) {
      return List.of();
    }
    if (ids.size() == 1 && ids.getFirst() == null) {
      Map<String, FormValue> cleared = new LinkedHashMap<>();
      selected
          .values()
          .forEach(
              field ->
                  cleared.put(field.field().fieldId(), new FormValue(FormValueKind.NULL, null)));
      return List.of(new FormReferenceLiveView(null, null, cleared));
    }
    if (selected.isEmpty()) {
      return unavailable(ids);
    }
    var target = relations.resolve(form, relationId).target();
    var targetContext = contexts.forRelatedRead(form, target);
    if (targetContext == null) {
      return unavailable(ids);
    }
    Map<FormReferenceMapping, FormRelationSourceField> inputs = new LinkedHashMap<>();
    for (var mapping : selected.keySet()) {
      var input =
          FormRelationSourceField.resolve(
              target, new FormRelationField(null, mapping.targetFieldId()));
      if (input.readable(targetContext.fieldPermissions())) {
        inputs.put(mapping, input);
      }
    }
    if (inputs.isEmpty()) {
      return unavailable(ids);
    }
    try {
      var lookup =
          new RecordPageQuery(
              target.physicalModelId(),
              new RecordSchemaReference(target.physicalModelVersion(), target.storageChecksum()),
              0,
              ids.size(),
              inputs.values().stream()
                  .map(value -> value.field().storageCode())
                  .distinct()
                  .toList(),
              new RecordFilter(
                  RecordFilterOperator.IN, "id", new java.util.ArrayList<>(ids), List.of()),
              List.of());
      var authority =
          reads.current(
              target,
              targetContext,
              new RecordCurrentTargetsQuery("1", null, lookup, null, 30_000),
              FormRecordReadAuthority.deadline());
      var page = dna.pageRecords(authority, lookup);
      if (page == null
          || page.records().size() > ids.size()
          || page.records().stream().anyMatch(record -> !ids.contains(record.id()))
          || page.records().stream().map(RecordView::id).distinct().count()
              != page.records().size()) {
        throw invalid();
      }
      Map<String, FormReferenceLiveView> projected = new LinkedHashMap<>();
      page.records()
          .forEach(record -> projected.put(record.id(), project(record, inputs, selected)));
      return ids.stream()
          .map(id -> projected.getOrDefault(id, new FormReferenceLiveView(id, null, Map.of())))
          .toList();
    } catch (DnaRuntimeException failure) {
      if (failure.errorCode() == DnaRuntimeErrorCode.RECORD_NOT_FOUND
          || failure.errorCode() == DnaRuntimeErrorCode.RECORD_ACCESS_DENIED) {
        return unavailable(ids);
      }
      throw FormRecordFailures.fromDna(failure);
    }
  }

  private static List<FormReferenceLiveView> unavailable(List<String> ids) {
    return ids.stream().map(id -> new FormReferenceLiveView(id, null, Map.of())).toList();
  }

  private static FormReferenceLiveView project(
      RecordView record,
      Map<FormReferenceMapping, FormRelationSourceField> inputs,
      Map<FormReferenceMapping, FormRelationSourceField> selected) {
    Map<String, FormValue> values = new LinkedHashMap<>();
    for (var entry : inputs.entrySet()) {
      var input = entry.getValue().field();
      if (!record.values().containsKey(input.storageCode())
          || "***".equals(record.values().get(input.storageCode()))) {
        continue;
      }
      var output = selected.get(entry.getKey()).field();
      var value =
          ReferenceMappingConversions.convert(
              RecordReferenceConversion.valueOf(entry.getKey().conversion().name()),
              FormLogicalFields.valueType(input),
              FormLogicalFields.valueType(output),
              record.values().get(input.storageCode()));
      values.put(
          output.fieldId(),
          new FormValue(FormValueKind.valueOf(value.kind().name()), value.canonicalValue()));
    }
    return new FormReferenceLiveView(record.id(), record.version(), values);
  }

  private static ApplicationFormException invalid() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RECORD_INVALID, "The live reference projection is unavailable");
  }

  private static ApplicationFormException denied() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
        "The live reference field is not readable");
  }
}
