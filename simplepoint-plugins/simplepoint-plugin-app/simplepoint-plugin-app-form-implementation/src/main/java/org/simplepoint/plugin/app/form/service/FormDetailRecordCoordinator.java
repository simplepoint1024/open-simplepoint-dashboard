package org.simplepoint.plugin.app.form.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordModel;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordQuery;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordRow;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSet;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSnapshotRequest;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;
import org.springframework.stereotype.Component;

/** Maps browser field identities to owned DNA commands without exposing child model metadata. */
@Component
public class FormDetailRecordCoordinator {
  private final DnaOwnedRecordService dna;
  private final FormRecordPayloadCompiler payloads;
  private final FormAttachmentReferenceValidator attachments;

  /** Creates the coordinator with explicit persistence, policy and service dependencies. */
  public FormDetailRecordCoordinator(
      DnaOwnedRecordService dna, FormRecordPayloadCompiler payloads, FormAttachmentReferenceValidator attachments) {
    this.dna = dna;
    this.payloads = payloads;
    this.attachments = attachments;
  }

  /** Separates parent fields from owned detail collections before scalar validation. */
  public Map<String, Object> parentValues(
      PublishedApplicationForm form, Map<String, Object> requested) {
    Map<String, Object> values = new LinkedHashMap<>(requested);
    form.document().details().forEach(detail -> values.remove(detail.detailId()));
    return values;
  }

  /** Builds an aggregate mutation without requesting an immutable submission snapshot. */
  public OwnedRecordCommand intent(
      PublishedApplicationForm form,
      RecordMutation mutation,
      String key,
      Map<String, Object> requested,
      FormRecordState state) {
    return intent(form, mutation, key, requested, state, false);
  }

  /** Captures every owned collection, including collections omitted from an update. */
  public OwnedRecordCommand intent(
      PublishedApplicationForm form,
      RecordMutation mutation,
      String key,
      Map<String, Object> requested,
      FormRecordState state,
      boolean captureSnapshot) {
    List<OwnedRecordSet> sets = new ArrayList<>();
    for (var detail : form.document().details()) {
      boolean delete = mutation instanceof RecordDeleteMutation;
      if (!delete && !requested.containsKey(detail.detailId())) {
        continue;
      }
      List<OwnedRecordRow> rows =
          delete
              ? List.of()
              : parseRows(requested.get(detail.detailId())).stream()
                  .map(
                      row ->
                          new OwnedRecordRow(
                              row.id(), row.version(), project(detail, row.values(), state)))
                  .toList();
      sets.add(new OwnedRecordSet(reference(form, detail.detailId()), rows));
    }
    return new OwnedRecordCommand(
        new RecordTransactionCommand(
            form.physicalModelId(),
            new RecordSchemaReference(form.physicalModelVersion(), form.storageChecksum()),
            List.of(mutation),
            30_000,
            key,
            form.writeAdmissionVersion()),
        sets,
        captureSnapshot
            ? new OwnedRecordSnapshotRequest(
                form.document().details().stream()
                    .map(detail -> reference(form, detail.detailId()))
                    .toList())
            : null);
  }

  /** Finds the committed receipt for the same aggregate identity and request. */
  public RecordCommandResult findResult(DnaInvocationContext context, OwnedRecordCommand command) {
    return dna.findResult(context, command);
  }

  /** Executes parent and owned child mutations in one DNA transaction. */
  public RecordCommandResult execute(DnaInvocationContext context, OwnedRecordCommand command) {
    return dna.execute(context, command);
  }

  /** Loads owned children and rejects a parent version change during the read. */
  public ApplicationFormRecordView enrich(
      PublishedApplicationForm form,
      DnaInvocationContext context,
      ApplicationFormRecordView parent) {
    if (form.document().details().isEmpty()) {
      return parent;
    }
    var aggregate =
        dna.get(
            context,
            new OwnedRecordQuery(
                new RecordLookupQuery(
                    form.physicalModelId(),
                    new RecordSchemaReference(form.physicalModelVersion(), form.storageChecksum()),
                    parent.id(),
                    List.of("id")),
                form.document().details().stream()
                    .map(detail -> reference(form, detail.detailId()))
                    .toList()));
    if (aggregate.parent().version() != parent.version()) {
      throw conflict();
    }
    return merge(form, parent, aggregate);
  }

  /** Reads the exact committed snapshot instead of the current mutable record. */
  public OwnedRecordView snapshot(DnaInvocationContext context, OwnedRecordCommand command) {
    return dna.snapshot(context, command);
  }

  /** Projects one already consistent aggregate without reading mutable business records again. */
  public ApplicationFormRecordView merge(
      PublishedApplicationForm form, ApplicationFormRecordView parent, OwnedRecordView aggregate) {
    Map<String, Object> values = new LinkedHashMap<>(parent.values());
    for (var detail : form.document().details()) {
      var model = reference(form, detail.detailId());
      values.put(
          detail.detailId(),
          aggregate.details().getOrDefault(model.physicalModelId(), List.of()).stream()
              .map(
                  row -> {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    detail
                        .fields()
                        .forEach(
                            field -> {
                              if (row.values().containsKey(field.storageCode())) {
                                fields.put(
                                    field.fieldId(),
                                    FormChoiceValues.displayValue(
                                        field, row.values().get(field.storageCode())));
                              }
                            });
                    return Map.of("id", row.id(), "version", row.version(), "values", fields,
                        "systemValues", FormRecordSystemFields.fromStorage(row.values()));
                  })
              .toList());
    }
    return new ApplicationFormRecordView(parent.id(), parent.version(), parent.state(), values, parent.systemValues());
  }

  /** Validates all stored rows at submission, including collections omitted from the patch. */
  public void validate(
      PublishedApplicationForm form,
      Map<String, Object> existing,
      Map<String, Object> requested,
      FormRecordState currentState,
      FormRecordState targetState) {
    attachments.validate(form.document().fields(), existing, requested);
    Map<String, List<Map<String, Object>>> prospectiveDetails = new LinkedHashMap<>();
    for (var detail : form.document().details()) {
      List<DetailRow> oldRows = parseRows(existing.getOrDefault(detail.detailId(), List.of()));
      Map<String, DetailRow> oldById = new LinkedHashMap<>();
      oldRows.stream().filter(row -> row.id() != null).forEach(row -> oldById.put(row.id(), row));
      boolean changed = requested.containsKey(detail.detailId());
      List<DetailRow> rows = changed ? parseRows(requested.get(detail.detailId())) : oldRows;
      List<Map<String, Object>> prospectiveRows = new ArrayList<>();
      for (var row : rows) {
        var old = row.id() == null ? null : oldById.get(row.id());
        if (changed && row.id() != null && (old == null || !row.version().equals(old.version()))) {
          throw conflict();
        }
        attachments.validate(detail.fields(), old == null ? Map.of() : old.values(),
            changed ? row.values() : Map.of());
        prospectiveRows.add(payloads.validatedValues(
            document(detail),
            old == null ? Map.of() : old.values(),
            changed ? row.values() : Map.of(),
            old == null ? null : currentState,
            targetState));
      }
      prospectiveDetails.put(detail.detailId(), List.copyOf(prospectiveRows));
    }
    if (targetState == FormRecordState.SUBMITTED && !form.document().constraints().isEmpty()) {
      FormAggregateValidation.validate(form.document(),
          payloads.validatedValues(form.document(), parentValues(form, existing), parentValues(form, requested),
              currentState, targetState), prospectiveDetails);
    }
  }

  private Map<String, Object> project(
      FormDetailDefinition detail, Map<String, Object> values, FormRecordState state) {
    Map<String, Object> projection =
        new LinkedHashMap<>(payloads.project(document(detail), values, state));
    projection.remove(FormStorageProjectionCompiler.RECORD_STATE_CODE);
    return projection;
  }

  private static ApplicationFormDocument document(FormDetailDefinition detail) {
    return new ApplicationFormDocument(
        "1", detail.fields(), detail.indexes(), List.of(), List.of());
  }

  static List<DetailRow> parseRows(Object value) {
    if (!(value instanceof List<?> rows) || rows.size() > 40) {
      throw invalid("Detail rows must be an array of at most 40 rows");
    }
    List<DetailRow> result = new ArrayList<>();
    Set<String> ids = new java.util.HashSet<>();
    for (var raw : rows) {
      if (!(raw instanceof Map<?, ?> row)
          || !Set.of("id", "version", "values", "systemValues").containsAll(row.keySet())
          || !(row.get("values") instanceof Map<?, ?> fields)) {
        throw invalid("Invalid detail row");
      }
      String id = row.get("id") == null ? null : row.get("id").toString();
      Long version = null;
      try {
        if (row.get("version") != null) {
          version = new BigDecimal(row.get("version").toString()).longValueExact();
        }
      } catch (RuntimeException failure) {
        throw invalid("Invalid detail version");
      }
      if ((id == null) != (version == null) || (id != null && (!ids.add(id) || version < 0))) {
        throw invalid("Invalid detail row identity");
      }
      Map<String, Object> values = new LinkedHashMap<>();
      fields.forEach(
          (key, field) -> {
            if (!(key instanceof String)) {
              throw invalid("Invalid detail field");
            }
            values.put((String) key, field);
          });
      Map<String, Object> system = new LinkedHashMap<>();
      if (row.get("systemValues") != null) {
        if (!(row.get("systemValues") instanceof Map<?, ?> metadata)) {
          throw invalid("Invalid detail metadata");
        }
        for (var field : org.simplepoint.plugin.dna.runtime.api.record.RecordSystemField.values()) {
          if (metadata.containsKey(field.property())) {
            system.put(field.property(), metadata.get(field.property()));
          }
        }
      }
      result.add(new DetailRow(id, version, java.util.Collections.unmodifiableMap(values),
          java.util.Collections.unmodifiableMap(system)));
    }
    return List.copyOf(result);
  }

  private static OwnedRecordModel reference(PublishedApplicationForm form, String detailId) {
    var binding =
        form.detailBindings().stream()
            .filter(candidate -> candidate.detailId().equals(detailId))
            .findFirst()
            .orElseThrow(
                () ->
                    new ApplicationFormException(
                        ApplicationFormErrorCode.PAGE_NOT_PUBLISHED, "Detail model is not active"));
    return new OwnedRecordModel(
        binding.physicalModelId(),
        new RecordSchemaReference(binding.schemaVersion(), binding.checksum()));
  }

  private static ApplicationFormException invalid(String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.RECORD_INVALID, message);
  }

  private static ApplicationFormException conflict() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RECORD_CONFLICT,
        "Detail rows changed or do not belong to this record");
  }

  record DetailRow(String id, Long version, Map<String, Object> values, Map<String, Object> systemValues) {}
}
