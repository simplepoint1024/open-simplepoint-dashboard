package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;
import org.springframework.stereotype.Service;

/** Reads immutable DNA evidence only after APP has authorized exact submission participation. */
@Service
public class FormSubmissionHistory {
  private final DnaOwnedRecordService dna;
  private final FormDetailRecordCoordinator details;
  private final FormRecordPayloadCompiler payloads;
  private final DnaInvocationContextFactory contexts;
  private final FormRelationTargetResolver relations;

  /** Keeps receipt identity, current viewer policy and original permission discovery separate. */
  public FormSubmissionHistory(
      DnaOwnedRecordService dna,
      FormDetailRecordCoordinator details,
      FormRecordPayloadCompiler payloads,
      DnaInvocationContextFactory contexts,
      FormRelationTargetResolver relations) {
    this.dna = dna;
    this.details = details;
    this.payloads = payloads;
    this.contexts = contexts;
    this.relations = relations;
  }

  ApplicationFormRecordView read(FormSubmissionJournal.Snapshot snapshot) {
    long deadline = System.nanoTime() + 30_000_000_000L;
    var accepted = snapshot.accepted();
    var form = accepted.form();
    var submitted = accepted.command();
    var state = FormRecordState.SUBMITTED;
    var values =
        payloads.project(form.document(), details.parentValues(form, submitted.values()), state);
    RecordMutation mutation =
        submitted.recordId() == null
            ? new RecordCreateMutation("parent", values)
            : new RecordUpdateMutation(
                "parent", submitted.recordId(), submitted.expectedVersion(), values);
    var command =
        details.intent(
            form,
            mutation,
            "submission:" + snapshot.view().id(),
            submitted.values(),
            state,
            true,
            accepted.snapshotRequirements());
    var query =
        new OwnedRecordHistoryQuery(
            "1",
            accepted.dataAuthority().actorId(),
            form.applicationId(),
            form.formId(),
            form.resourceCode(),
            snapshot.record().id(),
            snapshot.record().version(),
            command,
            accepted.relatedScope(),
            submitted.referenceMappings() == null
                ? null
                : FormReferenceMappedIntent.bind(
                    form, command, accepted.relatedScope(), submitted.referenceMappings()));
    var viewer = contexts.forForm(form);
    if (!viewer.tenantId().equals(form.tenantId())
        || !viewer.tenantId().equals(accepted.dataAuthority().tenantId())) {
      throw invalid();
    }
    try {
      var targets = dna.snapshotTargets(viewer, query.withTimeout(remaining(deadline)));
      if (targets == null
          || targets.size() > 50
          || targets.stream().distinct().count() != targets.size()) {
        throw invalid();
      }
      var authority = contexts.forRelations(form, relations.snapshotTargets(form, targets));
      var result = dna.snapshotForParticipant(authority, query.withTimeout(remaining(deadline)));
      if (result == null
          || !result.parent().id().equals(snapshot.record().id())
          || result.parent().version() != snapshot.record().version()) {
        throw invalid();
      }
      return details.projectSnapshot(form, result, snapshot.record().state());
    } catch (org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException failure) {
      throw FormRecordFailures.fromDna(failure);
    }
  }

  private static int remaining(long deadline) {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0 || Thread.currentThread().isInterrupted()) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RUNTIME_UNAVAILABLE, "Submission history read budget exhausted");
    }
    return (int) Math.max(1, Math.min(30_000, nanos / 1_000_000));
  }

  private static ApplicationFormException invalid() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.PERSISTENCE_FAILED,
        "Immutable submission evidence is absent or inconsistent");
  }
}
