package org.simplepoint.plugin.dna.runtime.service.remote;

import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordObservationQuery;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordObservationView;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordPreview;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordQuery;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;
import org.simplepoint.plugin.dna.runtime.service.record.OwnedRecordService;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.stereotype.Service;

/**
 * Owned aggregate transport keeps the same caller allowlist and effective user policy as ordinary
 * records.
 */
@Service
@RemoteProvider
public class DnaOwnedRecordServiceImpl implements DnaOwnedRecordService {
  private final DnaInvocationExecutor invocations;
  private final OwnedRecordService records;

  /**
   * Creates the owned-record boundary with explicit authorization and transaction collaborators.
   */
  public DnaOwnedRecordServiceImpl(DnaFormRuntimeCallerGuard guard, OwnedRecordService records) {
    this.invocations = new DnaInvocationExecutor(guard);
    this.records = records;
  }

  @Override
  public org.simplepoint.plugin.dna.runtime.api.record.RecordPageView pageRelated(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordPageQuery query) {
    return invocations.invoke(context, true, () -> records.pageRelated(query));
  }

  @Override
  public OwnedRecordView get(DnaInvocationContext context, OwnedRecordQuery query) {
    return invocations.invoke(context, true, () -> records.get(query));
  }

  @Override
  public OwnedRecordPreview preview(DnaInvocationContext context, OwnedRecordCommand command) {
    return invocations.invoke(context, true, () -> records.preview(command));
  }

  @Override
  public RecordCommandResult findResult(DnaInvocationContext context, OwnedRecordCommand command) {
    return invocations.invoke(context, true, () -> records.findResult(command));
  }

  @Override
  public OwnedRecordView snapshot(DnaInvocationContext context, OwnedRecordCommand command) {
    return invocations.invoke(context, true, () -> records.snapshot(command));
  }

  @Override
  public RecordCommandResult executeRelated(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand command) {
    return invocations.invoke(context, true, () -> records.executeRelated(command));
  }

  @Override
  public RecordCommandResult findRelatedResult(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand command) {
    return invocations.invoke(context, true, () -> records.findRelatedResult(command));
  }

  @Override
  public OwnedRecordView snapshotRelated(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand command) {
    return invocations.invoke(context, true, () -> records.snapshotRelated(command));
  }

  @Override
  public OwnedRecordView snapshotForParticipant(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery query) {
    return invocations.invoke(context, true, () -> records.snapshotForParticipant(query));
  }

  @Override
  public java.util.List<org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget>
      snapshotTargets(
          DnaInvocationContext context,
          org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery query) {
    return invocations.invoke(context, true, () -> records.snapshotTargets(query));
  }

  @Override
  public java.util.List<org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget>
      currentTargets(
          DnaInvocationContext context,
          org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery query) {
    return invocations.invoke(context, true, () -> records.currentTargets(query));
  }

  @Override
  public org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingView observeMapping(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingQuery query) {
    return invocations.invoke(context, true, () -> records.observeMapping(query));
  }

  @Override
  public RecordCommandResult executeMapped(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand command) {
    return invocations.invoke(context, true, () -> records.executeMapped(command));
  }

  @Override
  public RecordCommandResult findMappedResult(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand command) {
    return invocations.invoke(context, true, () -> records.findMappedResult(command));
  }

  @Override
  public OwnedRecordPreview previewMapped(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand command) {
    return invocations.invoke(context, true, () -> records.previewMapped(command));
  }

  @Override
  public OwnedRecordView snapshotMapped(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand command) {
    return invocations.invoke(context, true, () -> records.snapshotMapped(command));
  }

  @Override
  public RecordCommandResult execute(DnaInvocationContext context, OwnedRecordCommand command) {
    return invocations.invoke(context, true, () -> records.execute(command));
  }

  @Override
  public RecordPageView pageCollection(DnaInvocationContext context, CollectionRecordPageQuery request) {
    return invocations.invoke(context, true, () -> records.pageCollection(request));
  }

  @Override
  public CollectionRecordObservationView observeCollection(DnaInvocationContext context, CollectionRecordObservationQuery request) {
    return invocations.invoke(context, true, () -> records.observeCollection(request));
  }

  @Override
  public CollectionRecordResult executeCollection(DnaInvocationContext context, CollectionRecordCommand request) {
    return invocations.invoke(context, true, () -> records.executeCollection(request));
  }

  @Override
  public CollectionRecordResult findCollectionResult(DnaInvocationContext context, CollectionRecordCommand request) {
    return invocations.invoke(context, true, () -> records.findCollectionResult(request));
  }
}
