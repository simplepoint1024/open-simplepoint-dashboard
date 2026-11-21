package org.simplepoint.plugin.dna.runtime.api.service;

import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorDecoder;
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
import org.simplepoint.remoting.RemoteContract;

/** Same-datasource aggregate boundary; never permits independent child-row access. */
@RemoteContract(
    name = "dna.owned-record",
    version = "1",
    errorDecoder = DnaRuntimeErrorDecoder.class)
public interface DnaOwnedRecordService {
  /** Selects authorized distinct parents through an installed single-valued relation. */
  org.simplepoint.plugin.dna.runtime.api.record.RecordPageView pageRelated(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordPageQuery query);

  /** Reads an authorized parent and its owned child collections with consistent versions. */
  OwnedRecordView get(DnaInvocationContext context, OwnedRecordQuery query);

  /**
   * Evaluates a create/update using the installed policy without writing data, receipts or
   * snapshots.
   */
  OwnedRecordPreview preview(DnaInvocationContext context, OwnedRecordCommand command);

  /** Returns the original confirmation for an exact tenant, actor and request identity. */
  RecordCommandResult findResult(DnaInvocationContext context, OwnedRecordCommand command);

  /**
   * Reads the immutable result of this exact captured operation; null means it has not committed.
   */
  OwnedRecordView snapshot(DnaInvocationContext context, OwnedRecordCommand command);

  /** Executes a fixed relation intent; older providers cannot fall back to an unscoped write. */
  RecordCommandResult executeRelated(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand command);

  /** Recovers the exact relation-scoped receipt, including successful deletions. */
  RecordCommandResult findRelatedResult(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand command);

  /** Reads the immutable snapshot identified by the exact relation-scoped operation. */
  OwnedRecordView snapshotRelated(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand command);

  /**
   * Reads workflow evidence after the trusted APP owner has verified exact participation. The
   * original operation owner is not an impersonation context; current viewer policy always wins.
   */
  OwnedRecordView snapshotForParticipant(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery query);

  /** Bounded original permission entrances; never returns business values or row identities. */
  java.util.List<org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget>
      snapshotTargets(
          DnaInvocationContext context,
          org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery query);

  /**
   * Discovers bounded original entrances from authorized current rows, without returning values.
   */
  java.util.List<org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget> currentTargets(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery query);

  /**
   * A mapping observation is not a permission grant or a promise that a later save will succeed.
   */
  org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingView observeMapping(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingQuery query);

  /** Dedicated mapped operation identities prevent fallback to ordinary unconfirmed writes. */
  RecordCommandResult executeMapped(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand command);

  /** Looks up the receipt before attempting mutable mapping observations again. */
  RecordCommandResult findMappedResult(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand command);

  /** Previews confirmed target inputs through the same calculation and permission path as save. */
  OwnedRecordPreview previewMapped(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand command);

  /** Returns only the snapshot bound to the complete confirmed mapping command. */
  OwnedRecordView snapshotMapped(
      DnaInvocationContext context,
      org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand command);

  /** Commits the parent and child mutations, receipt and optional snapshot atomically. */
  RecordCommandResult execute(DnaInvocationContext context, OwnedRecordCommand command);

  @org.simplepoint.remoting.RemoteOperation(idempotent = true)
  RecordPageView pageCollection(DnaInvocationContext context, CollectionRecordPageQuery request);

  @org.simplepoint.remoting.RemoteOperation(idempotent = true)
  CollectionRecordObservationView observeCollection(DnaInvocationContext context, CollectionRecordObservationQuery request);

  @org.simplepoint.remoting.RemoteOperation(idempotent = true)
  CollectionRecordResult executeCollection(DnaInvocationContext context, CollectionRecordCommand request);

  @org.simplepoint.remoting.RemoteOperation(idempotent = true)
  CollectionRecordResult findCollectionResult(DnaInvocationContext context, CollectionRecordCommand request);
}
