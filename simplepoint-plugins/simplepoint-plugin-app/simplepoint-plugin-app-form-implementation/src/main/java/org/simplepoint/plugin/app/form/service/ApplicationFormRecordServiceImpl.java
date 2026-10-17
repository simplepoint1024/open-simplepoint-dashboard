package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordFilter;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordMutationView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageQuery;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordSort;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordService;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSortDirection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.springframework.stereotype.Service;

/** Executes page-bound form record operations through the narrow DNA contract. */
@Service
public class ApplicationFormRecordServiceImpl implements ApplicationFormRecordService {

  static final String RECORD_STATE_FIELD = "recordState";

  private final PublishedApplicationFormResolver resolver;
  private final FormRecordPayloadCompiler payloads;
  private final DnaFormRuntimeService dna;
  private final DnaInvocationContextFactory contexts;
  private final FormRecordInvariantValidator invariants;
  private final FormDetailRecordCoordinator details;

  /** Creates the runtime record facade. */
  public ApplicationFormRecordServiceImpl(
      final PublishedApplicationFormResolver resolver,
      final FormRecordPayloadCompiler payloads,
      final DnaFormRuntimeService dna,
      final DnaInvocationContextFactory contexts,
      final FormRecordInvariantValidator invariants,
      final FormDetailRecordCoordinator details) {
    this.resolver = resolver;
    this.payloads = payloads;
    this.dna = dna;
    this.contexts = contexts;
    this.invariants = invariants;
    this.details = details;
  }

  @Override
  public ApplicationFormRecordPageView page(
      final String applicationId, final String pageId, final ApplicationFormRecordPageQuery query) {
    PublishedApplicationForm form = resolver.resolve(applicationId, pageId);
    if (query == null) {
      throw invalid("Record page query is required");
    }
    try {
      FieldMapping mapping = mapping(form);
      DnaInvocationContext context = contexts.forResource(form.resourceCode());
      List<String> fields = projection(query.fieldIds(), mapping, form, context);
      var page =
          FormRecordProjectionReads.page(
              fields,
              batch ->
                  dna.pageRecords(
                      context,
                      new RecordPageQuery(
                          form.physicalModelId(),
                          schema(form),
                          query.page(),
                          query.size(),
                          batch,
                          filter(query.filter(), mapping),
                          sorts(query.sorts(), mapping))));
      return new ApplicationFormRecordPageView(
          page.page(),
          page.size(),
          page.total(),
          page.records().stream().map(record -> view(record, mapping)).toList());
    } catch (DnaRuntimeException exception) {
      throw dnaFailure(exception);
    } catch (IllegalArgumentException exception) {
      throw invalid("Record page query is invalid", exception);
    }
  }

  @Override
  public ApplicationFormRecordView get(
      final String applicationId, final String pageId, final String recordId) {
    PublishedApplicationForm form = resolver.resolve(applicationId, pageId);
    return get(form, recordId);
  }

  ApplicationFormRecordView get(final PublishedApplicationForm form, final String recordId) {
    resolver.requireRead(form);
    try {
      FieldMapping mapping = mapping(form);
      DnaInvocationContext context = contexts.forResource(form.resourceCode());
      return details.enrich(
          form, context, view(loadRecord(form, mapping, recordId, context), mapping));
    } catch (DnaRuntimeException exception) {
      throw dnaFailure(exception);
    } catch (IllegalArgumentException exception) {
      throw invalid("Record identity is invalid", exception);
    }
  }

  @Override
  public ApplicationFormRecordMutationView create(
      final String applicationId,
      final String pageId,
      final CreateApplicationFormRecordCommand command) {
    PublishedApplicationForm form = resolver.resolve(applicationId, pageId);
    return createRecord(form, command, false);
  }

  /** Internal submission write against its persisted, server-resolved form version. */
  ApplicationFormRecordView captureCreate(
      final PublishedApplicationForm form, final CreateApplicationFormRecordCommand command) {
    return createRecord(form, command, true).currentRecord();
  }

  private ApplicationFormRecordMutationView createRecord(
      final PublishedApplicationForm form,
      final CreateApplicationFormRecordCommand command,
      final boolean captureSnapshot) {
    resolver.requireAction(form, "records.create");
    if (command == null) {
      throw invalid("Record create command is required");
    }
    ApplicationFormRuntimeIdentity.requireCurrent(
        command.runtime(), form.formId(), form.formVersion());
    try {
      FieldMapping mapping = mapping(form);
      var context = contexts.forResource(form.resourceCode());
      var intent =
          new RecordCreateCommand(
              form.physicalModelId(),
              schema(form),
              command.idempotencyKey(),
              payloads.project(
                  form.document(), details.parentValues(form, command.values()), command.state()),
              form.writeAdmissionVersion());
      var owned =
          !captureSnapshot && form.document().details().isEmpty()
              ? null
              : details.intent(
                  form,
                  new org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation(
                      "parent", intent.values()),
                  command.idempotencyKey(),
                  command.values(),
                  command.state(),
                  captureSnapshot);
      Supplier<RecordCommandResult> find =
          () ->
              owned == null
                  ? dna.findRecordCreateResult(context, intent)
                  : details.findResult(context, owned);
      Supplier<RecordCommandResult> execute =
          () -> owned == null ? dna.createRecord(context, intent) : details.execute(context, owned);
      RecordCommandResult committed = find.get();
      if (committed != null) {
        return mutationView(form, mapping, context, committed, command.state(), owned);
      }
      try {
        payloads.compile(
            form.document(),
            Map.of(),
            details.parentValues(form, command.values()),
            null,
            command.state());
        details.validate(form, Map.of(), command.values(), null, command.state());
        invariants.create(form, details.parentValues(form, command.values()), command.state());
        return mutationView(form, mapping, context, execute.get(), command.state(), owned);
      } catch (RuntimeException failure) {
        RecordCommandResult recovered = recoverResult(find, failure);
        if (recovered != null) {
          return mutationView(form, mapping, context, recovered, command.state(), owned);
        }
        throw failure;
      }
    } catch (DnaRuntimeException exception) {
      throw dnaFailure(exception);
    } catch (IllegalArgumentException exception) {
      throw invalid("Record create command is invalid", exception);
    }
  }

  @Override
  public ApplicationFormRecordMutationView update(
      final String applicationId,
      final String pageId,
      final String recordId,
      final UpdateApplicationFormRecordCommand command) {
    PublishedApplicationForm form = resolver.resolve(applicationId, pageId);
    return updateRecord(form, recordId, command, false);
  }

  /** Internal submission update against its persisted, server-resolved form version. */
  ApplicationFormRecordView captureUpdate(
      final PublishedApplicationForm form,
      final String recordId,
      final UpdateApplicationFormRecordCommand command) {
    return updateRecord(form, recordId, command, true).currentRecord();
  }

  private ApplicationFormRecordMutationView updateRecord(
      final PublishedApplicationForm form,
      final String recordId,
      final UpdateApplicationFormRecordCommand command,
      final boolean captureSnapshot) {
    resolver.requireAction(form, "records.edit");
    if (command == null) {
      throw invalid("Record update command is required");
    }
    ApplicationFormRuntimeIdentity.requireCurrent(
        command.runtime(), form.formId(), form.formVersion());
    try {
      FieldMapping mapping = mapping(form);
      var context = contexts.forResource(form.resourceCode());
      requireOperationKey(command.idempotencyKey());
      RecordUpdateCommand intent =
          new RecordUpdateCommand(
              form.physicalModelId(),
              schema(form),
              recordId,
              command.expectedVersion(),
              payloads.project(
                  form.document(), details.parentValues(form, command.values()), command.state()),
              command.idempotencyKey(),
              form.writeAdmissionVersion());
      var owned =
          !captureSnapshot && form.document().details().isEmpty()
              ? null
              : details.intent(
                  form,
                  new org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation(
                      "parent", recordId, command.expectedVersion(), intent.values()),
                  command.idempotencyKey(),
                  command.values(),
                  command.state(),
                  captureSnapshot);
      Supplier<RecordCommandResult> find =
          () ->
              owned == null
                  ? dna.findRecordUpdateResult(context, intent)
                  : details.findResult(context, owned);
      Supplier<RecordCommandResult> execute =
          () -> owned == null ? dna.updateRecord(context, intent) : details.execute(context, owned);
      RecordCommandResult committed = find.get();
      if (committed != null) {
        return mutationView(form, mapping, context, committed, command.state(), owned);
      }
      try {
        var validationContext = contexts.forResourceValidation(form.resourceCode());
        ApplicationFormRecordView current =
            details.enrich(
                form,
                validationContext,
                view(loadRecord(form, mapping, recordId, validationContext), mapping));
        if (current.version() != command.expectedVersion()) {
          throw new ApplicationFormException(
              ApplicationFormErrorCode.RECORD_CONFLICT,
              "Record version does not match the edit intent");
        }
        payloads.compile(
            form.document(),
            current.values(),
            details.parentValues(form, command.values()),
            current.state(),
            command.state());
        details.validate(
            form, current.values(), command.values(), current.state(), command.state());
        invariants.update(
            form,
            validationContext,
            recordId,
            command.expectedVersion(),
            details.parentValues(form, command.values()),
            command.state());
        return mutationView(form, mapping, context, execute.get(), command.state(), owned);
      } catch (RuntimeException failure) {
        // A concurrent retry may commit between the first probe and validation/read/write.
        // Only the exact committed receipt can turn that failure into a successful acknowledgement.
        RecordCommandResult recovered = recoverResult(find, failure);
        if (recovered != null) {
          return mutationView(form, mapping, context, recovered, command.state(), owned);
        }
        throw failure;
      }
    } catch (DnaRuntimeException exception) {
      throw dnaFailure(exception);
    } catch (IllegalArgumentException exception) {
      throw invalid("Record update command is invalid", exception);
    }
  }

  /** Preflight uses the same current record and validation rules as the eventual captured write. */
  Map<String, Object> previewSubmission(
      PublishedApplicationForm form,
      org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand command) {
    resolver.requireAction(form, command.recordId() == null ? "records.create" : "records.edit");
    ApplicationFormRuntimeIdentity.requireCurrent(
        command.runtime(), form.formId(), form.formVersion());
    var context = contexts.forResourceValidation(form.resourceCode());
    ApplicationFormRecordView current =
        command.recordId() == null
            ? null
            : details.enrich(
                form,
                context,
                view(loadRecord(form, mapping(form), command.recordId(), context), mapping(form)));
    if (current != null && current.version() != command.expectedVersion()) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RECORD_CONFLICT, "Record changed before submission");
    }
    var existing = current == null ? Map.<String, Object>of() : current.values();
    var state = current == null ? null : current.state();
    details.validate(form, existing, command.values(), state, FormRecordState.SUBMITTED);
    return payloads.validatedValues(
        form.document(),
        existing,
        details.parentValues(form, command.values()),
        state,
        FormRecordState.SUBMITTED);
  }

  @Override
  public void delete(
      final String applicationId,
      final String pageId,
      final String recordId,
      final long expectedVersion,
      final ApplicationFormRuntimeIdentity runtime,
      final String idempotencyKey) {
    PublishedApplicationForm form = resolver.resolve(applicationId, pageId);
    resolver.requireAction(form, "records.delete");
    ApplicationFormRuntimeIdentity.requireCurrent(runtime, form.formId(), form.formVersion());
    requireOperationKey(idempotencyKey);
    try {
      if (!form.document().details().isEmpty()) {
        var intent =
            details.intent(
                form,
                new org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation(
                    "parent", recordId, expectedVersion),
                idempotencyKey,
                Map.of(),
                FormRecordState.DRAFT);
        details.execute(contexts.forResource(form.resourceCode()), intent);
        return;
      }
      dna.deleteRecord(
          contexts.forResource(form.resourceCode()),
          new RecordDeleteCommand(
              form.physicalModelId(),
              schema(form),
              recordId,
              expectedVersion,
              idempotencyKey,
              form.writeAdmissionVersion()));
    } catch (DnaRuntimeException exception) {
      throw dnaFailure(exception);
    } catch (IllegalArgumentException exception) {
      throw invalid("Record delete command is invalid", exception);
    }
  }

  private RecordCommandResult recoverResult(
      final Supplier<RecordCommandResult> lookup, final RuntimeException original) {
    try {
      return lookup.get();
    } catch (DnaRuntimeException failure) {
      if (failure.errorCode() == DnaRuntimeErrorCode.RECORD_ACCESS_DENIED
          || failure.errorCode() == DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT) {
        throw failure;
      }
      original.addSuppressed(failure);
      return null;
    } catch (RuntimeException failure) {
      original.addSuppressed(failure);
      return null;
    }
  }

  private ApplicationFormRecordMutationView mutationView(
      final PublishedApplicationForm form,
      final FieldMapping mapping,
      final DnaInvocationContext context,
      final RecordCommandResult result,
      final FormRecordState state,
      final OwnedRecordCommand owned) {
    if (owned != null && owned.snapshot() != null) {
      var snapshot = details.snapshot(context, owned);
      if (snapshot == null
          || !result.recordId().equals(snapshot.parent().id())
          || result.version() != snapshot.parent().version()) {
        throw new ApplicationFormException(
            ApplicationFormErrorCode.RECORD_OPERATION_FAILED,
            "The committed submission snapshot is unavailable");
      }
      var captured = details.merge(form, view(snapshot.parent(), mapping), snapshot);
      return new ApplicationFormRecordMutationView(
          result.recordId(), result.version(), state, captured);
    }
    ApplicationFormRecordView current = null;
    try {
      current =
          details.enrich(
              form, context, view(loadRecord(form, mapping, result.recordId(), context), mapping));
    } catch (ApplicationFormException failure) {
      if (failure.errorCode() != ApplicationFormErrorCode.RECORD_CONFLICT) {
        throw failure;
      }
      // The acknowledgement remains valid when its separately authorized current projection
      // changes.
    } catch (DnaRuntimeException failure) {
      if (failure.errorCode() != DnaRuntimeErrorCode.RECORD_NOT_FOUND
          && failure.errorCode() != DnaRuntimeErrorCode.RECORD_ACCESS_DENIED) {
        throw failure;
      }
      // A later delete or policy change cannot undo the committed acknowledgement.
      // Never substitute request values for a separately authorized current record.
    }
    return new ApplicationFormRecordMutationView(
        result.recordId(), result.version(), state, current);
  }

  private static void requireOperationKey(final String key) {
    if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw invalid("A valid record operation idempotency key is required");
    }
  }

  private RecordView loadRecord(
      final PublishedApplicationForm form,
      final FieldMapping mapping,
      final String recordId,
      final DnaInvocationContext context) {
    return FormRecordProjectionReads.record(
        projection(List.of(), mapping, form, context),
        batch ->
            dna.getRecord(
                context,
                new RecordLookupQuery(form.physicalModelId(), schema(form), recordId, batch)));
  }

  private static ApplicationFormRecordView view(
      final RecordView record, final FieldMapping mapping) {
    Object stateValue = record.values().get(FormStorageProjectionCompiler.RECORD_STATE_CODE);
    FormRecordState state;
    try {
      state = FormRecordState.valueOf(String.valueOf(stateValue));
    } catch (RuntimeException exception) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RECORD_OPERATION_FAILED,
          "The stored form record state is invalid",
          exception);
    }
    Map<String, Object> values = new LinkedHashMap<>();
    record
        .values()
        .forEach(
            (code, value) -> {
              String fieldId = mapping.idsByCode().get(code);
              if (fieldId != null) {
                values.put(
                    fieldId,
                    FormChoiceValues.displayValue(mapping.fieldsById().get(fieldId), value));
              }
            });
    return new ApplicationFormRecordView(record.id(), record.version(), state, values,
        FormRecordSystemFields.fromStorage(record.values()));
  }

  private static FieldMapping mapping(final PublishedApplicationForm form) {
    Map<String, String> codesById =
        form.document().fields().stream()
            .filter(ApplicationFormRecordServiceImpl::persistent)
            .collect(
                Collectors.toMap(
                    FormFieldDefinition::fieldId,
                    FormFieldDefinition::storageCode,
                    (left, right) -> left,
                    LinkedHashMap::new));
    Map<String, String> idsByCode =
        codesById.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));
    Map<String, FormFieldDefinition> fieldsById =
        form.document().fields().stream()
            .collect(Collectors.toUnmodifiableMap(FormFieldDefinition::fieldId, field -> field));
    return new FieldMapping(Map.copyOf(codesById), idsByCode, fieldsById);
  }

  private static List<String> projection(
      final List<String> requested,
      final FieldMapping mapping,
      final PublishedApplicationForm form,
      final DnaInvocationContext context) {
    List<String> ids =
        requested == null || requested.isEmpty()
            ? visibleFieldIds(mapping, form, context)
            : List.copyOf(requested);
    List<String> result = new ArrayList<>();
    for (String fieldId : ids) {
      String code = mapping.code(fieldId);
      if (!result.contains(code)) {
        result.add(code);
      }
    }
    result.add(FormStorageProjectionCompiler.RECORD_STATE_CODE);
    String prefix = RecordPermissionModel.key(form.physicalModelId()) + "#";
    for (var field : org.simplepoint.plugin.dna.runtime.api.record.RecordSystemField.values()) {
      if (!"HIDDEN".equals(context.fieldPermissions().get(prefix + field.storageCode()))
          && !result.contains(field.storageCode())) {
        result.add(field.storageCode());
      }
    }
    return List.copyOf(result);
  }

  private static List<String> visibleFieldIds(
      final FieldMapping mapping,
      final PublishedApplicationForm form,
      final DnaInvocationContext context) {
    return mapping.codesById().entrySet().stream()
        .filter(
            entry -> !"HIDDEN".equals(RecordPermissionModel.access(context.fieldPermissions(),
                form.physicalModelId(), entry.getKey(), entry.getValue())))
        .map(Map.Entry::getKey)
        .toList();
  }

  private static RecordFilter filter(
      final ApplicationFormRecordFilter filter, final FieldMapping mapping) {
    if (filter == null) {
      return null;
    }
    RecordFilterOperator operator = RecordFilterOperator.valueOf(filter.operator().name());
    return new RecordFilter(
        operator,
        operator.group() ? null : mapping.code(filter.fieldId()),
        filter.values(),
        filter.children().stream().map(child -> filter(child, mapping)).toList());
  }

  private static List<RecordSort> sorts(
      final List<ApplicationFormRecordSort> sorts, final FieldMapping mapping) {
    if (sorts == null) {
      return List.of();
    }
    return sorts.stream()
        .map(
            sort ->
                new RecordSort(
                    mapping.code(sort.fieldId()),
                    RecordSortDirection.valueOf(sort.direction().name())))
        .toList();
  }

  private static RecordSchemaReference schema(final PublishedApplicationForm form) {
    return new RecordSchemaReference(form.physicalModelVersion(), form.storageChecksum());
  }

  private static boolean persistent(final FormFieldDefinition field) {
    return field.storageMode() == FormStorageMode.STORED
        || field.storageMode() == FormStorageMode.REFERENCE
        || field.storageMode() == FormStorageMode.ATTACHMENT;
  }

  private static ApplicationFormException dnaFailure(final DnaRuntimeException exception) {
    ApplicationFormErrorCode code =
        switch (exception.errorCode()) {
          case RECORD_NOT_FOUND -> ApplicationFormErrorCode.RECORD_NOT_FOUND;
          case RECORD_ACCESS_DENIED -> ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED;
          case RECORD_VERSION_CONFLICT,
              RECORD_IDEMPOTENCY_CONFLICT,
              RECORD_WRITE_POLICY_CONFLICT,
              RECORD_SCHEMA_INCOMPATIBLE,
              PHYSICAL_MODEL_NOT_ACTIVE ->
              ApplicationFormErrorCode.RECORD_CONFLICT;
          case INVALID_ARGUMENT,
              VALUE_CONVERSION_FAILED,
              RECORD_QUERY_INVALID,
              RECORD_COMMAND_INVALID ->
              ApplicationFormErrorCode.RECORD_INVALID;
          default -> ApplicationFormErrorCode.RECORD_OPERATION_FAILED;
        };
    return new ApplicationFormException(code, exception.getMessage(), exception);
  }

  private static ApplicationFormException invalid(final String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.RECORD_INVALID, message);
  }

  private static ApplicationFormException invalid(
      final String message, final RuntimeException cause) {
    return new ApplicationFormException(ApplicationFormErrorCode.RECORD_INVALID, message, cause);
  }

  private record FieldMapping(
      Map<String, String> codesById,
      Map<String, String> idsByCode,
      Map<String, FormFieldDefinition> fieldsById) {

    private String code(final String fieldId) {
      if (RECORD_STATE_FIELD.equals(fieldId)) {
        return FormStorageProjectionCompiler.RECORD_STATE_CODE;
      }
      var system = org.simplepoint.plugin.dna.runtime.api.record.RecordSystemField.findProperty(fieldId);
      if (system != null) {
        return system.storageCode();
      }
      String code = codesById.get(fieldId);
      if (code == null) {
        throw invalid("Record query references an unknown form field");
      }
      return code;
    }
  }
}
