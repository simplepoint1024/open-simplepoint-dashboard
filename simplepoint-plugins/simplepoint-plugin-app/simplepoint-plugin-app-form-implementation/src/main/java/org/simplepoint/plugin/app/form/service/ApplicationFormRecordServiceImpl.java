package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.HashSet;
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
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPreviewView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordSort;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.DeleteApplicationFormRecordsCommand;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.PreviewApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordService;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordDeleteTransaction;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSortDirection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope;
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
  private final FormRecordIdentityLabels identityLabels;
  private final FormRecordReadAuthority readAuthority;
  private final FormReferenceMappingAuthority mappingAuthority;

  /** Creates the runtime record facade. */
  public ApplicationFormRecordServiceImpl(
      final PublishedApplicationFormResolver resolver,
      final FormRecordPayloadCompiler payloads,
      final DnaFormRuntimeService dna,
      final DnaInvocationContextFactory contexts,
      final FormRecordInvariantValidator invariants,
      final FormDetailRecordCoordinator details,
      final FormRecordIdentityLabels identityLabels,
      final FormRecordReadAuthority readAuthority,
      final FormReferenceMappingAuthority mappingAuthority) {
    this.resolver = resolver;
    this.payloads = payloads;
    this.dna = dna;
    this.contexts = contexts;
    this.invariants = invariants;
    this.details = details;
    this.identityLabels = identityLabels;
    this.readAuthority = readAuthority;
    this.mappingAuthority = mappingAuthority;
  }

  @Override
  public ApplicationFormRecordPageView page(
      final String applicationId, final String pageId, final ApplicationFormRecordPageQuery query) {
    PublishedApplicationForm form = resolver.resolve(applicationId, pageId);
    return page(form, query);
  }

  /** Internal view adapter retains the exact resolved form instead of resolving another version. */
  ApplicationFormRecordPageView page(
      final PublishedApplicationForm form, final ApplicationFormRecordPageQuery query) {
    return pageRelated(form, query, null, null, null);
  }

  /** The installed relation predicate is applied in DNA, before count, ordering and pagination. */
  ApplicationFormRecordPageView pageRelated(
      final PublishedApplicationForm form,
      final ApplicationFormRecordPageQuery query,
      final String relationId,
      final String targetModelId,
      final String targetRecordId) {
    resolver.requireRead(form);
    if (query == null) {
      throw invalid("Record page query is required");
    }
    try {
      FieldMapping mapping = mapping(form);
      DnaInvocationContext context =
          relationId == null ? contexts.forForm(form) : readAuthority.related(form, relationId);
      long deadline = FormRecordReadAuthority.deadline();
      List<String> fields = projection(query.fieldIds(), mapping, form, context);
      var page =
          FormRecordProjectionReads.page(
              fields,
              batch -> {
                var request =
                    new RecordPageQuery(
                        form.physicalModelId(),
                        schema(form),
                        query.page(),
                        query.size(),
                        batch,
                        filter(query.filter(), mapping),
                        sorts(query.sorts(), mapping));
                var related =
                    relationId == null
                        ? null
                        : new org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordPageQuery(
                            request, relationId, targetModelId, targetRecordId);
                var authority =
                    readAuthority.current(
                        form,
                        context,
                        new org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery(
                            "1", null, related == null ? request : null, related, 30_000),
                        deadline);
                return related == null
                    ? dna.pageRecords(authority, request)
                    : details.pageRelated(authority, related);
              });
      return new ApplicationFormRecordPageView(
          page.page(),
          page.size(),
          page.total(),
          identityLabels.decorate(
              form.tenantId(),
              page.records().stream().map(record -> view(record, mapping)).toList()));
    } catch (DnaRuntimeException exception) {
      throw FormRecordFailures.fromDna(exception);
    } catch (IllegalArgumentException exception) {
      throw invalid("Record page query is invalid", exception);
    }
  }

  ApplicationFormRecordPageView pageCollection(
      PublishedApplicationForm form, ApplicationFormRecordPageQuery query, DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition condition,
      java.util.function.Function<RecordPageQuery, org.simplepoint.plugin.dna.runtime.api.record.RecordPageView> select) {
    resolver.requireRead(form);
    try {
      var mapping = mapping(form);
      var fields = projection(query.fieldIds(), mapping, form, context);
      var page = FormRecordProjectionReads.page(fields, batch -> select.apply(new RecordPageQuery(
          form.physicalModelId(), schema(form), query.page(), query.size(), batch,
          filter(query.filter(), mapping), sorts(query.sorts(), mapping), condition)));
      return new ApplicationFormRecordPageView(page.page(), page.size(), page.total(),
          identityLabels.decorate(form.tenantId(), page.records().stream().map(record -> view(record, mapping)).toList()));
    } catch (DnaRuntimeException failure) {
      throw FormRecordFailures.fromDna(failure);
    } catch (IllegalArgumentException failure) {
      throw invalid("Collection page query is invalid", failure);
    }
  }

  @Override
  public ApplicationFormRecordView get(
      final String applicationId, final String pageId, final String recordId) {
    PublishedApplicationForm form = resolver.resolve(applicationId, pageId);
    return getDecorated(form, recordId);
  }

  ApplicationFormRecordView get(final PublishedApplicationForm form, final String recordId) {
    resolver.requireRead(form);
    try {
      FieldMapping mapping = mapping(form);
      DnaInvocationContext context = contexts.forForm(form);
      return readCurrent(form, mapping, recordId, context);
    } catch (DnaRuntimeException exception) {
      throw FormRecordFailures.fromDna(exception);
    } catch (IllegalArgumentException exception) {
      throw invalid("Record identity is invalid", exception);
    }
  }

  /** Uses the exact entry already resolved by a relation, without a second page-binding lookup. */
  ApplicationFormRecordView getDecorated(
      final PublishedApplicationForm form, final String recordId) {
    return identityLabels.decorate(form.tenantId(), get(form, recordId));
  }

  @Override
  public ApplicationFormRecordMutationView create(
      final String applicationId,
      final String pageId,
      final CreateApplicationFormRecordCommand command) {
    PublishedApplicationForm form = resolver.resolve(applicationId, pageId);
    return createRecord(form, command, false, null, null);
  }

  /** Internal submission write against its persisted, server-resolved form version. */
  ApplicationFormRecordView captureCreate(
      final PublishedApplicationForm form, final CreateApplicationFormRecordCommand command) {
    return captureCreate(form, command, null);
  }

  ApplicationFormRecordView captureCreate(
      final PublishedApplicationForm form,
      final CreateApplicationFormRecordCommand command,
      final java.util.List<
              org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement>
          requirements) {
    return createRecord(form, command, true, requirements, null).currentRecord();
  }

  /** Uses the standard create lifecycle with a server-resolved inverse relation constraint. */
  ApplicationFormRecordMutationView createRelated(
      PublishedApplicationForm form,
      CreateApplicationFormRecordCommand command,
      RelatedRecordScope scope) {
    return createRecord(form, command, false, null, java.util.Objects.requireNonNull(scope));
  }

  private ApplicationFormRecordMutationView createRecord(
      final PublishedApplicationForm form,
      final CreateApplicationFormRecordCommand command,
      final boolean captureSnapshot,
      final java.util.List<
              org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement>
          requirements,
      final RelatedRecordScope relatedScope) {
    resolver.requireAction(form, "records.create");
    if (command == null) {
      throw invalid("Record create command is required");
    }
    ApplicationFormRuntimeIdentity.requireCurrent(
        command.runtime(), form.formId(), form.formVersion());
    try {
      FieldMapping mapping = mapping(form);
      var context = contexts.forWrite(form);
      var intent =
          new RecordCreateCommand(
              form.physicalModelId(),
              schema(form),
              command.idempotencyKey(),
              payloads.project(
                  form.document(), details.parentValues(form, command.values()), command.state()),
              form.writeAdmissionVersion());
      var owned =
          command.referenceMappings() == null
                  && relatedScope == null
                  && !captureSnapshot
                  && form.document().details().isEmpty()
              ? null
              : details.intent(
                  form,
                  new org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation(
                      "parent", intent.values()),
                  command.idempotencyKey(),
                  command.values(),
                  command.state(),
                  captureSnapshot,
                  requirements);
      var mappedIntent =
          command.referenceMappings() == null
              ? null
              : FormReferenceMappedIntent.bind(
                  form, owned, relatedScope, command.referenceMappings());
      Supplier<RecordCommandResult> find =
          () ->
              mappedIntent != null
                  ? details.findMappedResult(context, mappedIntent)
                  : owned == null
                      ? dna.findRecordCreateResult(context, intent)
                      : details.findResult(context, owned, relatedScope);
      Supplier<RecordCommandResult> execute =
          () ->
              mappedIntent != null
                  ? details.executeMapped(
                      mappingAuthority.forWrite(form, mappedIntent), mappedIntent)
                  : owned == null
                      ? dna.createRecord(context, intent)
                      : details.execute(context, owned, relatedScope);
      RecordCommandResult committed = find.get();
      if (committed != null) {
        return mutationView(
            form, mapping, context, committed, command.state(), owned, relatedScope, mappedIntent);
      }
      try {
        payloads.compile(
            form.document(),
            Map.of(),
            details.parentValues(form, command.values()),
            null,
            command.state(),
            FormRecordValueProviders.forRow(
                form.document(), null, null, command.referenceMappings()));
        details.validate(
            form, Map.of(), command.values(), null, command.state(), command.referenceMappings());
        invariants.create(
            form,
            details.parentValues(form, command.values()),
            command.state(),
            command.referenceMappings());
        return mutationView(
            form,
            mapping,
            context,
            execute.get(),
            command.state(),
            owned,
            relatedScope,
            mappedIntent);
      } catch (RuntimeException failure) {
        RecordCommandResult recovered = recoverResult(find, failure);
        if (recovered != null) {
          return mutationView(
              form,
              mapping,
              context,
              recovered,
              command.state(),
              owned,
              relatedScope,
              mappedIntent);
        }
        throw failure;
      }
    } catch (DnaRuntimeException exception) {
      throw FormRecordFailures.fromDna(exception);
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
    return updateRecord(form, recordId, command, false, null, null);
  }

  /** Internal submission update against its persisted, server-resolved form version. */
  ApplicationFormRecordView captureUpdate(
      final PublishedApplicationForm form,
      final String recordId,
      final UpdateApplicationFormRecordCommand command) {
    return captureUpdate(form, recordId, command, null);
  }

  ApplicationFormRecordView captureUpdate(
      final PublishedApplicationForm form,
      final String recordId,
      final UpdateApplicationFormRecordCommand command,
      final java.util.List<
              org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement>
          requirements) {
    return updateRecord(form, recordId, command, true, requirements, null).currentRecord();
  }

  /** Captures one accepted relation-scoped submission through the same immutable receipt path. */
  ApplicationFormRecordView captureRelatedUpdate(
      PublishedApplicationForm form,
      String recordId,
      UpdateApplicationFormRecordCommand command,
      java.util.List<org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement>
          requirements,
      RelatedRecordScope scope) {
    return updateRecord(
            form, recordId, command, true, requirements, java.util.Objects.requireNonNull(scope))
        .currentRecord();
  }

  /** Retains validation, exact receipt recovery and current write policies for inverse edits. */
  ApplicationFormRecordMutationView updateRelated(
      PublishedApplicationForm form,
      String recordId,
      UpdateApplicationFormRecordCommand command,
      RelatedRecordScope scope) {
    return updateRecord(
        form, recordId, command, false, null, java.util.Objects.requireNonNull(scope));
  }

  private ApplicationFormRecordMutationView updateRecord(
      final PublishedApplicationForm form,
      final String recordId,
      final UpdateApplicationFormRecordCommand command,
      final boolean captureSnapshot,
      final java.util.List<
              org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement>
          requirements,
      final RelatedRecordScope relatedScope) {
    resolver.requireAction(form, "records.edit");
    if (command == null) {
      throw invalid("Record update command is required");
    }
    ApplicationFormRuntimeIdentity.requireCurrent(
        command.runtime(), form.formId(), form.formVersion());
    try {
      FieldMapping mapping = mapping(form);
      var context = contexts.forWrite(form);
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
          command.referenceMappings() == null
                  && relatedScope == null
                  && !captureSnapshot
                  && form.document().details().isEmpty()
              ? null
              : details.intent(
                  form,
                  new org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation(
                      "parent", recordId, command.expectedVersion(), intent.values()),
                  command.idempotencyKey(),
                  command.values(),
                  command.state(),
                  captureSnapshot,
                  requirements);
      var mappedIntent =
          command.referenceMappings() == null
              ? null
              : FormReferenceMappedIntent.bind(
                  form, owned, relatedScope, command.referenceMappings());
      Supplier<RecordCommandResult> find =
          () ->
              mappedIntent != null
                  ? details.findMappedResult(context, mappedIntent)
                  : owned == null
                      ? dna.findRecordUpdateResult(context, intent)
                      : details.findResult(context, owned, relatedScope);
      Supplier<RecordCommandResult> execute =
          () ->
              mappedIntent != null
                  ? details.executeMapped(
                      mappingAuthority.forWrite(form, mappedIntent), mappedIntent)
                  : owned == null
                      ? dna.updateRecord(context, intent)
                      : details.execute(context, owned, relatedScope);
      RecordCommandResult committed = find.get();
      if (committed != null) {
        return mutationView(
            form, mapping, context, committed, command.state(), owned, relatedScope, mappedIntent);
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
            command.state(),
            FormRecordValueProviders.forRow(
                form.document(), null, null, command.referenceMappings()));
        details.validate(
            form,
            current.values(),
            command.values(),
            current.state(),
            command.state(),
            command.referenceMappings());
        invariants.update(
            form,
            validationContext,
            recordId,
            command.expectedVersion(),
            details.parentValues(form, command.values()),
            command.state(),
            command.referenceMappings());
        return mutationView(
            form,
            mapping,
            context,
            execute.get(),
            command.state(),
            owned,
            relatedScope,
            mappedIntent);
      } catch (RuntimeException failure) {
        // A concurrent retry may commit between the first probe and validation/read/write.
        // Only the exact committed receipt can turn that failure into a successful acknowledgement.
        RecordCommandResult recovered = recoverResult(find, failure);
        if (recovered != null) {
          return mutationView(
              form,
              mapping,
              context,
              recovered,
              command.state(),
              owned,
              relatedScope,
              mappedIntent);
        }
        throw failure;
      }
    } catch (DnaRuntimeException exception) {
      throw FormRecordFailures.fromDna(exception);
    } catch (IllegalArgumentException exception) {
      throw invalid("Record update command is invalid", exception);
    }
  }

  @Override
  public ApplicationFormRecordPreviewView preview(
      final String applicationId,
      final String pageId,
      final PreviewApplicationFormRecordCommand command) {
    return preview(resolver.resolve(applicationId, pageId), command);
  }

  private ApplicationFormRecordPreviewView preview(
      final PublishedApplicationForm form, final PreviewApplicationFormRecordCommand command) {
    return preview(form, command, null);
  }

  private ApplicationFormRecordPreviewView preview(
      PublishedApplicationForm form,
      PreviewApplicationFormRecordCommand command,
      RelatedRecordScope relatedScope) {
    if (command == null) {
      throw invalid("Record preview command is required");
    }
    resolver.requireAction(form, command.recordId() == null ? "records.create" : "records.edit");
    ApplicationFormRuntimeIdentity.requireCurrent(
        command.runtime(), form.formId(), form.formVersion());
    try {
      var validationContext = contexts.forResourceValidation(form.resourceCode());
      var current =
          command.recordId() == null
              ? null
              : details.enrich(
                  form,
                  validationContext,
                  view(
                      loadRecord(form, mapping(form), command.recordId(), validationContext),
                      mapping(form)));
      if (current != null && current.version() != command.expectedVersion()) {
        throw new ApplicationFormException(
            ApplicationFormErrorCode.RECORD_CONFLICT, "Record changed before preview");
      }
      var existing = current == null ? Map.<String, Object>of() : current.values();
      var state = current == null ? null : current.state();
      var parentValues = details.parentValues(form, command.values());
      var values =
          payloads.compile(
              form.document(),
              existing,
              parentValues,
              state,
              command.state(),
              FormRecordValueProviders.forRow(
                  form.document(), null, null, command.referenceMappings()));
      details.validate(
          form, existing, command.values(), state, command.state(), command.referenceMappings());
      if (current == null) {
        invariants.create(form, parentValues, command.state(), command.referenceMappings());
      } else {
        invariants.update(
            form,
            validationContext,
            current.id(),
            current.version(),
            parentValues,
            command.state(),
            command.referenceMappings());
      }
      org.simplepoint.plugin.dna.runtime.api.record.RecordMutation mutation =
          current == null
              ? new org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation(
                  "parent", values)
              : new org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation(
                  "parent", current.id(), current.version(), values);
      // Preview never creates a durable operation. Keep its ephemeral identity separate from
      // save/submit retries.
      var intent =
          details.intent(
              form,
              mutation,
              "preview:" + java.util.UUID.randomUUID(),
              command.values(),
              command.state());
      if (command.referenceMappings() != null) {
        var mapped =
            FormReferenceMappedIntent.bind(form, intent, relatedScope, command.referenceMappings());
        return details.previewMapped(form, mappingAuthority.forWrite(form, mapped), mapped);
      }
      return details.preview(form, contexts.forForm(form), intent);
    } catch (DnaRuntimeException failure) {
      throw FormRecordFailures.fromDna(failure);
    } catch (IllegalArgumentException failure) {
      throw invalid("Record preview command is invalid", failure);
    }
  }

  /** Preflight uses the same current record and validation rules as the eventual captured write. */
  Map<String, Object> previewSubmission(
      PublishedApplicationForm form,
      org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand command) {
    return previewSubmission(form, command, null);
  }

  Map<String, Object> previewSubmission(
      PublishedApplicationForm form,
      org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand command,
      RelatedRecordScope relatedScope) {
    if (command.referenceMappings() != null
        || form.document().relations().stream().anyMatch(relation -> !relation.mappings().isEmpty())
        || FormCalculationCompiler.hasCalculations(form.document())) {
      return preview(
              form,
              new PreviewApplicationFormRecordCommand(
                  command.runtime(),
                  command.recordId(),
                  command.expectedVersion(),
                  FormRecordState.SUBMITTED,
                  command.values(),
                  command.referenceMappings()),
              relatedScope)
          .values();
    }
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
    deleteRecord(
        resolver.resolve(applicationId, pageId),
        recordId,
        expectedVersion,
        runtime,
        idempotencyKey,
        null);
  }

  @Override
  public void deleteBatch(String applicationId, String pageId,
      DeleteApplicationFormRecordsCommand command) {
    var form = resolver.resolve(applicationId, pageId);
    resolver.requireAction(form, "records.delete");
    if (command == null || command.records() == null || command.records().isEmpty()
        || command.records().size() > RecordTransactionCommand.MAX_MUTATIONS) {
      throw invalid("Invalid record selection", null);
    }
    ApplicationFormRuntimeIdentity.requireCurrent(command.runtime(), form.formId(), form.formVersion());
    requireOperationKey(command.idempotencyKey());
    try {
      var seen = new HashSet<String>();
      var mutations = command.records().stream().<RecordMutation>map(item -> {
        if (item == null || item.expectedVersion() == null || !seen.add(item.id())) {
          throw new IllegalArgumentException("Each record requires a distinct identity and version");
        }
        return new RecordDeleteMutation("delete-" + seen.size(), item.id(), item.expectedVersion());
      }).toList();
      var transaction = new RecordTransactionCommand(form.physicalModelId(),
          schema(form), mutations, 30_000, command.idempotencyKey(), form.writeAdmissionVersion());
      var authority = contexts.forForm(form);
      if (form.document().details().isEmpty()) {
        dna.executeRecordTransaction(authority, transaction);
      } else {
        var ownedDetails = form.document().details().stream()
            .map(detail -> FormDetailRecordCoordinator.reference(form, detail.detailId())).toList();
        dna.deleteOwnedRecords(authority, new OwnedRecordDeleteTransaction(transaction, ownedDetails));
      }
    } catch (DnaRuntimeException failure) {
      throw FormRecordFailures.fromDna(failure);
    } catch (IllegalArgumentException failure) {
      throw invalid("Invalid record selection", failure);
    }
  }

  /** Exact scoped delete receipts remain recoverable after the source record is gone. */
  void deleteRelated(
      PublishedApplicationForm form,
      String recordId,
      long expectedVersion,
      ApplicationFormRuntimeIdentity runtime,
      String idempotencyKey,
      RelatedRecordScope scope) {
    deleteRecord(
        form,
        recordId,
        expectedVersion,
        runtime,
        idempotencyKey,
        java.util.Objects.requireNonNull(scope));
  }

  private void deleteRecord(
      PublishedApplicationForm form,
      String recordId,
      long expectedVersion,
      ApplicationFormRuntimeIdentity runtime,
      String idempotencyKey,
      RelatedRecordScope relatedScope) {
    resolver.requireAction(form, "records.delete");
    ApplicationFormRuntimeIdentity.requireCurrent(runtime, form.formId(), form.formVersion());
    requireOperationKey(idempotencyKey);
    try {
      if (relatedScope != null || !form.document().details().isEmpty()) {
        var intent =
            details.intent(
                form,
                new org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation(
                    "parent", recordId, expectedVersion),
                idempotencyKey,
                Map.of(),
                FormRecordState.DRAFT);
        var authority = relatedScope == null ? contexts.forForm(form) : contexts.forWrite(form);
        details.execute(authority, intent, relatedScope);
        return;
      }
      dna.deleteRecord(
          contexts.forForm(form),
          new RecordDeleteCommand(
              form.physicalModelId(),
              schema(form),
              recordId,
              expectedVersion,
              idempotencyKey,
              form.writeAdmissionVersion()));
    } catch (DnaRuntimeException exception) {
      throw FormRecordFailures.fromDna(exception);
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
      final OwnedRecordCommand owned,
      final RelatedRecordScope relatedScope,
      final MappedOwnedRecordCommand mappedIntent) {
    if (owned != null && owned.snapshot() != null) {
      var authority =
          readAuthority.snapshot(
              form,
              context,
              new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery(
                  "1",
                  context.actorId(),
                  form.applicationId(),
                  form.formId(),
                  form.resourceCode(),
                  result.recordId(),
                  result.version(),
                  owned,
                  relatedScope,
                  mappedIntent),
              FormRecordReadAuthority.deadline());
      var snapshot =
          mappedIntent == null
              ? details.snapshot(authority, owned, relatedScope)
              : details.snapshotMapped(authority, mappedIntent);
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
      current = readCurrent(form, mapping, result.recordId(), context);
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

  private ApplicationFormRecordView readCurrent(
      PublishedApplicationForm form,
      FieldMapping mapping,
      String recordId,
      DnaInvocationContext context) {
    long deadline = FormRecordReadAuthority.deadline();
    var record =
        FormRecordProjectionReads.record(
            projection(List.of(), mapping, form, context),
            batch -> {
              var query =
                  new RecordLookupQuery(form.physicalModelId(), schema(form), recordId, batch);
              var authority =
                  readAuthority.current(
                      form,
                      context,
                      new org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery(
                          "1",
                          new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordQuery(
                              query, List.of()),
                          null,
                          null,
                          30_000),
                      deadline);
              return dna.getRecord(authority, query);
            });
    return details.enrichAuthorized(
        form,
        view(record, mapping),
        query ->
            readAuthority.current(
                form,
                context,
                new org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery(
                    "1", query, null, null, 30_000),
                deadline));
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
    return new ApplicationFormRecordView(
        record.id(),
        record.version(),
        state,
        values,
        FormRecordSystemFields.fromStorage(record.values()));
  }

  private static FieldMapping mapping(final PublishedApplicationForm form) {
    Map<String, String> codesById =
        form.document().fields().stream()
            .filter(FormFieldDefinition::persistent)
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
            entry ->
                !"HIDDEN"
                    .equals(
                        RecordPermissionModel.access(
                            context.fieldPermissions(),
                            form.physicalModelId(),
                            entry.getKey(),
                            entry.getValue())))
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
      var system =
          org.simplepoint.plugin.dna.runtime.api.record.RecordSystemField.findProperty(fieldId);
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
