package org.simplepoint.plugin.app.form.service;

import java.util.LinkedHashMap;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordMutationView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.service.FormInverseRelationResolver.ResolvedView;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope;
import org.springframework.stereotype.Component;

/** Delegates inverse mutations to the standard record lifecycle with a mandatory DNA scope. */
@Component
public class FormInverseRecordCommands {
  private final ApplicationFormRecordServiceImpl records;

  /** Shares the existing validation, permissions, aggregate write and receipt implementation. */
  public FormInverseRecordCommands(ApplicationFormRecordServiceImpl records) {
    this.records = records;
  }

  ApplicationFormRecordMutationView create(
      ResolvedView view, CreateApplicationFormRecordCommand command) {
    require(view.view().allowCreate());
    if (command == null) {
      throw invalid();
    }
    var values = new LinkedHashMap<>(command.values());
    if (view.relation().sourceField().detailId() == null) {
      String field = view.sourceField().fieldId();
      // This is a normal submitted field: the existing write policy must still admit it.
      if (values.containsKey(field) && !view.parentRecordId().equals(values.get(field))) {
        throw invalid();
      }
      values.put(field, view.parentRecordId());
    }
    return records.createRelated(
        view.source(),
        new CreateApplicationFormRecordCommand(
            command.runtime(),
            command.idempotencyKey(),
            command.state(),
            values,
            command.referenceMappings()),
        scope(view, RelatedRecordScope.Mode.MAINTAIN, null));
  }

  ApplicationFormRecordMutationView update(
      ResolvedView view, String recordId, UpdateApplicationFormRecordCommand command) {
    require(view.view().allowEdit());
    return records.updateRelated(
        view.source(), recordId, command, scope(view, RelatedRecordScope.Mode.MAINTAIN, null));
  }

  void delete(
      ResolvedView view,
      String recordId,
      long expectedVersion,
      ApplicationFormRuntimeIdentity runtime,
      String idempotencyKey) {
    require(view.view().allowDelete());
    // Membership is checked in DNA, after exact receipt recovery, not through a mutable reread.
    records.deleteRelated(
        view.source(),
        recordId,
        expectedVersion,
        runtime,
        idempotencyKey,
        scope(view, RelatedRecordScope.Mode.MAINTAIN, null));
  }

  ApplicationFormRecordMutationView unlink(
      ResolvedView view,
      String recordId,
      UpdateApplicationFormRecordCommand command,
      String sourceRowId) {
    require(view.view().allowUnlink());
    if (view.sourceField().storageRequired()
        || (view.relation().sourceField().detailId() == null) != (sourceRowId == null)) {
      throw invalid();
    }
    return records.updateRelated(
        view.source(), recordId, command, scope(view, RelatedRecordScope.Mode.UNLINK, sourceRowId));
  }

  private static RelatedRecordScope scope(
      ResolvedView view, RelatedRecordScope.Mode mode, String rowId) {
    return new RelatedRecordScope(
        "1",
        view.relation().relationId(),
        view.parent().physicalModelId(),
        view.parentRecordId(),
        mode,
        rowId);
  }

  private static void require(boolean enabled) {
    if (!enabled) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
          "The inverse view does not allow this operation");
    }
  }

  private static ApplicationFormException invalid() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RECORD_INVALID,
        "The inverse operation does not match the published relation");
  }
}
