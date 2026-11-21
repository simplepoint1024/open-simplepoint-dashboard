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
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPreviewView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordModel;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordPreview;
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
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope;
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
      DnaOwnedRecordService dna,
      FormRecordPayloadCompiler payloads,
      FormAttachmentReferenceValidator attachments) {
    this.dna = dna;
    this.payloads = payloads;
    this.attachments = attachments;
  }

  /** Uses the same aggregate contract for a distinct parent page selected by an owned relation. */
  org.simplepoint.plugin.dna.runtime.api.record.RecordPageView pageRelated(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordPageQuery query) {
    return dna.pageRelated(context, query);
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
    return intent(form, mutation, key, requested, state, captureSnapshot, null);
  }

  /** A captured integration freezes extra value requirements without changing ordinary CRUD. */
  public OwnedRecordCommand intent(
      PublishedApplicationForm form,
      RecordMutation mutation,
      String key,
      Map<String, Object> requested,
      FormRecordState state,
      boolean captureSnapshot,
      List<org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement>
          requirements) {
    if (!captureSnapshot && requirements != null) {
      throw new IllegalArgumentException(
          "Only captured operations can declare snapshot requirements");
    }
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
                              row.id(), row.version(), project(form, detail, row.values(), state)))
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
                    .toList(),
                requirements)
            : null);
  }

  /** Finds the committed receipt for the same aggregate identity and request. */
  public RecordCommandResult findResult(DnaInvocationContext context, OwnedRecordCommand command) {
    return dna.findResult(context, command);
  }

  /** Keeps an inverse intent and its receipt in the same DNA command domain. */
  RecordCommandResult findResult(
      DnaInvocationContext context,
      OwnedRecordCommand command,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope scope) {
    return scope == null
        ? findResult(context, command)
        : dna.findRelatedResult(
            context,
            new org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand(command, scope));
  }

  /** Executes parent and owned child mutations in one DNA transaction. */
  public RecordCommandResult execute(DnaInvocationContext context, OwnedRecordCommand command) {
    return dna.execute(context, command);
  }

  /** Related writes never fall back to an unconstrained aggregate endpoint. */
  RecordCommandResult execute(
      DnaInvocationContext context,
      OwnedRecordCommand command,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope scope) {
    return scope == null
        ? execute(context, command)
        : dna.executeRelated(
            context,
            new org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand(command, scope));
  }

  /** Mapping receipts and writes retain the complete immutable confirmation intent. */
  RecordCommandResult findMappedResult(
      DnaInvocationContext context, MappedOwnedRecordCommand command) {
    return dna.findMappedResult(context, command);
  }

  RecordCommandResult executeMapped(
      DnaInvocationContext context, MappedOwnedRecordCommand command) {
    return dna.executeMapped(context, command);
  }

  OwnedRecordView snapshotMapped(DnaInvocationContext context, MappedOwnedRecordCommand command) {
    return dna.snapshotMapped(context, command);
  }

  ApplicationFormRecordPreviewView previewMapped(
      PublishedApplicationForm form,
      DnaInvocationContext context,
      MappedOwnedRecordCommand command) {
    return previewView(form, dna.previewMapped(context, command));
  }

  /**
   * Maps already authorized preview values; formulas, columns and model identities stay
   * server-side.
   */
  public ApplicationFormRecordPreviewView preview(
      final PublishedApplicationForm form,
      final DnaInvocationContext context,
      final OwnedRecordCommand command) {
    return previewView(form, dna.preview(context, command));
  }

  private ApplicationFormRecordPreviewView previewView(
      PublishedApplicationForm form, OwnedRecordPreview result) {
    if (result == null || result.writePolicyVersion() != form.writeAdmissionVersion()) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RUNTIME_VERSION_CONFLICT,
          "The form policy changed before preview completed");
    }
    Map<String, List<Map<String, Object>>> collections = new LinkedHashMap<>();
    for (var detail : form.document().details()) {
      var rows = result.details().get(reference(form, detail.detailId()).physicalModelId());
      if (rows != null) {
        collections.put(
            detail.detailId(),
            rows.stream().map(row -> previewValues(detail.fields(), row)).toList());
      }
    }
    return new ApplicationFormRecordPreviewView(
        new ApplicationFormRuntimeIdentity(form.formId(), form.formVersion()),
        previewValues(form.document().fields(), result.values()),
        collections);
  }

  private static Map<String, Object> previewValues(
      final List<FormFieldDefinition> fields, final Map<String, Object> storage) {
    Map<String, Object> values = new LinkedHashMap<>();
    fields.stream()
        .filter(FormFieldDefinition::persistent)
        .forEach(
            field -> {
              if (storage.containsKey(field.storageCode())) {
                values.put(
                    field.fieldId(),
                    FormChoiceValues.displayValue(field, storage.get(field.storageCode())));
              }
            });
    return values;
  }

  /** Loads owned children and rejects a parent version change during the read. */
  public ApplicationFormRecordView enrich(
      PublishedApplicationForm form,
      DnaInvocationContext context,
      ApplicationFormRecordView parent) {
    return enrichAuthorized(form, parent, query -> context);
  }

  /** Resolves child copy provenance for the exact owned read, outside any DNA transaction. */
  ApplicationFormRecordView enrichAuthorized(
      PublishedApplicationForm form,
      ApplicationFormRecordView parent,
      java.util.function.Function<OwnedRecordQuery, DnaInvocationContext> authority) {
    if (form.document().details().isEmpty()) {
      return parent;
    }
    var query =
        new OwnedRecordQuery(
            new RecordLookupQuery(
                form.physicalModelId(),
                new RecordSchemaReference(form.physicalModelVersion(), form.storageChecksum()),
                parent.id(),
                List.of("id")),
            form.document().details().stream()
                .map(detail -> reference(form, detail.detailId()))
                .toList());
    var aggregate = dna.get(authority.apply(query), query);
    if (aggregate.parent().version() != parent.version()) {
      throw conflict();
    }
    return merge(form, parent, aggregate);
  }

  /** Reads the exact committed snapshot instead of the current mutable record. */
  public OwnedRecordView snapshot(DnaInvocationContext context, OwnedRecordCommand command) {
    return snapshot(context, command, null);
  }

  OwnedRecordView snapshot(
      DnaInvocationContext context, OwnedRecordCommand command, RelatedRecordScope scope) {
    return scope == null
        ? dna.snapshot(context, command)
        : dna.snapshotRelated(context, new RelatedRecordCommand(command, scope));
  }

  /** Uses the same storage-to-field conversion for immutable history and captured mutations. */
  ApplicationFormRecordView projectSnapshot(
      PublishedApplicationForm form, OwnedRecordView aggregate, FormRecordState state) {
    var parent = aggregate.parent();
    return merge(
        form,
        new ApplicationFormRecordView(
            parent.id(),
            parent.version(),
            state,
            previewValues(form.document().fields(), parent.values()),
            FormRecordSystemFields.fromStorage(parent.values())),
        aggregate);
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
                    Map<String, Object> fields = previewValues(detail.fields(), row.values());
                    return Map.of(
                        "id",
                        row.id(),
                        "version",
                        row.version(),
                        "values",
                        fields,
                        "systemValues",
                        FormRecordSystemFields.fromStorage(row.values()));
                  })
              .toList());
    }
    var merged =
        new ApplicationFormRecordView(
            parent.id(), parent.version(), parent.state(), values, parent.systemValues());
    return new ApplicationFormRecordView(
        merged.id(),
        merged.version(),
        merged.state(),
        merged.values(),
        merged.systemValues(),
        merged.systemLabels(),
        FormReferenceSnapshots.capture(form, aggregate, merged));
  }

  /** Validates all stored rows at submission, including collections omitted from the patch. */
  public void validate(
      PublishedApplicationForm form,
      Map<String, Object> existing,
      Map<String, Object> requested,
      FormRecordState currentState,
      FormRecordState targetState) {
    validate(form, existing, requested, currentState, targetState, null);
  }

  /** Confirms only exact row defaults while retaining attachment, identity and input validation. */
  void validate(
      PublishedApplicationForm form,
      Map<String, Object> existing,
      Map<String, Object> requested,
      FormRecordState currentState,
      FormRecordState targetState,
      org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingIntent mappings) {
    attachments.validate(form.document().fields(), existing, requested);
    Map<String, Set<String>> deferredDetails = new LinkedHashMap<>();
    Map<String, List<Map<String, Object>>> prospectiveDetails = new LinkedHashMap<>();
    for (var detail : form.document().details()) {
      List<DetailRow> oldRows = parseRows(existing.getOrDefault(detail.detailId(), List.of()));
      Map<String, DetailRow> oldById = new LinkedHashMap<>();
      oldRows.stream().filter(row -> row.id() != null).forEach(row -> oldById.put(row.id(), row));
      boolean changed = requested.containsKey(detail.detailId());
      List<DetailRow> rows = changed ? parseRows(requested.get(detail.detailId())) : oldRows;
      List<Map<String, Object>> prospectiveRows = new ArrayList<>();
      Set<String> deferred = new java.util.HashSet<>();
      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        var row = rows.get(rowIndex);
        var providers =
            FormRecordValueProviders.forRow(
                form.document(),
                detail.detailId(),
                changed ? rowIndex : null,
                changed ? mappings : null);
        deferred.addAll(providers.deferred());
        var old = row.id() == null ? null : oldById.get(row.id());
        if (changed && row.id() != null && (old == null || !row.version().equals(old.version()))) {
          throw conflict();
        }
        attachments.validate(
            detail.fields(),
            old == null ? Map.of() : old.values(),
            changed ? row.values() : Map.of());
        prospectiveRows.add(
            payloads.validatedValues(
                document(detail),
                old == null ? Map.of() : old.values(),
                changed ? row.values() : Map.of(),
                old == null ? null : currentState,
                targetState,
                providers));
      }
      prospectiveDetails.put(detail.detailId(), List.copyOf(prospectiveRows));
      deferredDetails.put(detail.detailId(), Set.copyOf(deferred));
    }
    if (targetState == FormRecordState.SUBMITTED && !form.document().constraints().isEmpty()) {
      FormAggregateValidation.validateInputs(
          form.document(),
          payloads.validatedValues(
              form.document(),
              parentValues(form, existing),
              parentValues(form, requested),
              currentState,
              targetState,
              FormRecordValueProviders.forRow(form.document(), null, null, mappings)),
          prospectiveDetails,
          FormRecordValueProviders.forRow(form.document(), null, null, mappings).deferred(),
          deferredDetails);
    }
  }

  private Map<String, Object> project(
      PublishedApplicationForm form,
      FormDetailDefinition detail,
      Map<String, Object> values,
      FormRecordState state) {
    Map<String, Object> projection =
        new LinkedHashMap<>(
            payloads.project(
                document(detail),
                values,
                state,
                FormRecordValueProviders.forRow(form.document(), detail.detailId(), null, null)));
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
      result.add(
          new DetailRow(
              id,
              version,
              java.util.Collections.unmodifiableMap(values),
              java.util.Collections.unmodifiableMap(system)));
    }
    return List.copyOf(result);
  }

  static OwnedRecordModel reference(PublishedApplicationForm form, String detailId) {
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

  record DetailRow(
      String id, Long version, Map<String, Object> values, Map<String, Object> systemValues) {}
}
