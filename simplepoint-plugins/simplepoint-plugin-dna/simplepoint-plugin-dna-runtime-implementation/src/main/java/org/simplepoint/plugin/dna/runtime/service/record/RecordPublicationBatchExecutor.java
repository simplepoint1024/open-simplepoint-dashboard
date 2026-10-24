package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.springframework.stereotype.Component;

/**
 * Recoverable target batch state machine. Every call runs under control model locks and one backend
 * publication session; business mutations, manifests and checkpoints share that physical commit.
 */
@Component
public class RecordPublicationBatchExecutor {
  private final PhysicalSchemaCodec schemas;
  private final RecordSqlCompiler sql;
  private final RecordPublicationPatchCompiler patches;
  private final RecordPublicationStore journal = new RecordPublicationStore();
  private final RecordPublicationStages stages = new RecordPublicationStages();
  private final RecordCalculationStore calculations = new RecordCalculationStore();

  /** Reuses the ordinary Record compiler and the immutable calculation/staging contracts. */
  public RecordPublicationBatchExecutor(final PhysicalSchemaCodec schemas, final RecordSqlCompiler sql) {
    this.schemas = schemas;
    this.sql = sql;
    this.patches = new RecordPublicationPatchCompiler(schemas, sql);
  }

  RecordPublicationStore.Entry start(final RecordPublicationIntent intent,
      final Map<String, RecordPublicationPatchCompiler.Resolved> models, final RecordPublicationSession session,
      final String cancellationFingerprint) {
    var existing = journal.read(intent.key(), intent.command().targetVersion(), 
        session.read(journal.lookup(intent.key(), intent.command().targetVersion())));
    if (existing != null) {
      return validate(intent, existing, session, cancellationFingerprint);
    }
    requireOriginal(intent, session);
    if (journal.readActive(intent.key(), session.read(journal.active(intent.key()))) != null) {
      throw conflict();
    }
    var rows = rows(intent, models);
    long count = RecordPublicationRows.count(session.aggregate(rows.count()));
    var entry = new RecordPublicationStore.Entry(intent, RecordPublicationCheckpoint.start(count));
    session.write(journal.insert(entry));
    return entry;
  }

  /** Stale sequence replay returns the committed checkpoint instead of executing another batch. */
  RecordPublicationStore.Entry step(final RecordPublicationIntent intent,
      final Map<String, RecordPublicationPatchCompiler.Resolved> models, final Map<RecordSchemaReference, RuntimeRecordModel> historical,
      final long expectedSequence, final int limit, final String cancellationFingerprint, final RecordPublicationSession session) {
    if (limit < 1 || limit > 10 || expectedSequence < 1) {
      throw new IllegalArgumentException("Invalid publication batch budget or sequence");
    }
    var entry = existing(intent, session, cancellationFingerprint);
    if (expectedSequence > entry.checkpoint().sequence()) {
      throw conflict();
    }
    if (entry.checkpoint().progress() == null || expectedSequence < entry.checkpoint().sequence()) {
      return entry;
    }
    var rows = rows(intent, models);
    return switch (entry.checkpoint().phase()) {
      case STAGING -> stage(entry, rows, session, limit);
      case APPLYING -> apply(entry, models, historical, rows, session, limit);
      case RESTORING -> restore(entry, models, rows, session, limit, cancellationFingerprint);
      default -> throw invalid();
    };
  }

  /** Persist the restoration decision before any undo work; repeats never regress progress. */
  RecordPublicationStore.Entry requestCancellation(final RecordPublicationIntent intent, final String cancellationFingerprint,
      final RecordPublicationSession session) {
    var entry = existing(intent, session, cancellationFingerprint);
    if (entry.checkpoint().progress() == null || entry.checkpoint().phase() == RecordPublicationCheckpoint.Phase.RESTORING) {
      return entry;
    }
    return advance(entry, entry.checkpoint().transition(RecordPublicationCheckpoint.Phase.RESTORING), session);
  }

  /** A separate attempt records a safe diagnostic only if the failed batch did not commit. */
  RecordPublicationStore.Entry failed(final RecordPublicationIntent intent, final long expectedSequence,
      final DnaRuntimeErrorCode code, final String cancellationFingerprint, final RecordPublicationSession session) {
    var entry = existing(intent, session, cancellationFingerprint);
    if (entry.checkpoint().progress() == null || expectedSequence != entry.checkpoint().sequence()) {
      return entry;
    }
    return advance(entry, entry.checkpoint().failed(code), session);
  }

  private RecordPublicationStore.Entry stage(final RecordPublicationStore.Entry entry, final RecordPublicationRows rows,
      final RecordPublicationSession session, final int limit) {
    var checkpoint = entry.checkpoint();
    var ids = rows.identities(checkpoint.stagingCursor(), limit, session::read);
    if (checkpoint.staged() == checkpoint.total()) {
      if (!ids.isEmpty() || RecordPublicationRows.count(session.aggregate(rows.count())) != checkpoint.total()) {
        throw invalid();
      }
      requireCounts(entry, session);
      return advance(entry, checkpoint.transition(RecordPublicationCheckpoint.Phase.APPLYING), session);
    }
    if (ids.isEmpty() || ids.size() > checkpoint.total() - checkpoint.staged()) {
      throw invalid();
    }
    for (String id : ids) {
      var aggregate = rows.calculate(id, session::read);
      var prepared = stages.prepare(entry.intent(), aggregate);
      for (var statement : stages.inserts(prepared)) {
        session.write(statement);
      }
    }
    return advance(entry, checkpoint.batch(ids.size(), ids.getLast()), session);
  }

  private RecordPublicationStore.Entry apply(final RecordPublicationStore.Entry entry,
      final Map<String, RecordPublicationPatchCompiler.Resolved> models, final Map<RecordSchemaReference, RuntimeRecordModel> historical,
      final RecordPublicationRows rows, final RecordPublicationSession session, final int limit) {
    var checkpoint = entry.checkpoint();
    if (checkpoint.applied() < checkpoint.total()) {
      return mutate(entry, models, session, limit, false);
    }
    requireCounts(entry, session);
    if (RecordPublicationRows.count(session.aggregate(rows.count())) != checkpoint.total()) {
      throw invalid();
    }
    var intent = entry.intent();
    RecordPublicationChecks.verify(intent, models.get(intent.key().modelId()).model(), historical, sql, schemas, session);
    session.write(calculations.insert(intent.key(), intent.command().targetVersion(), intent.actorId(), now(), policy(intent)));
    session.activate(intent);
    return advance(entry, checkpoint.transition(RecordPublicationCheckpoint.Phase.COMMITTED), session);
  }

  private RecordPublicationStore.Entry restore(final RecordPublicationStore.Entry entry,
      final Map<String, RecordPublicationPatchCompiler.Resolved> models, final RecordPublicationRows rows,
      final RecordPublicationSession session, final int limit, final String cancellationFingerprint) {
    if (entry.checkpoint().restored() < entry.checkpoint().applied()) {
      return mutate(entry, models, session, limit, true);
    }
    requireCounts(entry, session);
    if (RecordPublicationRows.count(session.aggregate(rows.count())) != entry.checkpoint().total()) {
      throw invalid();
    }
    session.cancel(entry.intent(), cancellationFingerprint);
    return advance(entry, entry.checkpoint().transition(RecordPublicationCheckpoint.Phase.CANCELLED), session);
  }

  private RecordPublicationStore.Entry mutate(final RecordPublicationStore.Entry entry,
      final Map<String, RecordPublicationPatchCompiler.Resolved> models, final RecordPublicationSession session,
      final int limit, final boolean restore) {
    var intent = entry.intent();
    var checkpoint = entry.checkpoint();
    String cursor = restore ? checkpoint.restorationCursor() : checkpoint.applicationCursor();
    var phase = restore ? RecordPublicationStages.Phase.APPLIED : RecordPublicationStages.Phase.STAGED;
    var headers = stages.readPage(intent, phase, cursor, limit, session.read(stages.page(intent, phase, cursor, limit)));
    long remaining = restore ? checkpoint.applied() - checkpoint.restored() : checkpoint.total() - checkpoint.applied();
    if (headers.isEmpty() || headers.size() > remaining) {
      throw invalid();
    }
    for (var header : headers) {
      var aggregate = stages.payload(intent, header, session::read);
      for (var statement : patches.compile(intent, aggregate, models,
          restore ? RecordPublicationPatchCompiler.Direction.RESTORE : RecordPublicationPatchCompiler.Direction.APPLY)) {
        session.write(statement);
      }
      session.write(stages.advance(intent, header, restore ? RecordPublicationStages.Phase.RESTORED : RecordPublicationStages.Phase.APPLIED, now()));
    }
    return advance(entry, checkpoint.batch(headers.size(), headers.getLast().parentRecordId()), session);
  }

  private void requireCounts(final RecordPublicationStore.Entry entry, final RecordPublicationSession session) {
    var counts = stages.readCounts(session.aggregate(stages.counts(entry.intent())));
    var checkpoint = entry.checkpoint();
    if (counts.getOrDefault(RecordPublicationStages.Phase.STAGED, 0L) != checkpoint.staged() - checkpoint.applied()
        || counts.getOrDefault(RecordPublicationStages.Phase.APPLIED, 0L) != checkpoint.applied() - checkpoint.restored()
        || counts.getOrDefault(RecordPublicationStages.Phase.RESTORED, 0L) != checkpoint.restored()) {
      throw invalid();
    }
  }

  /** Metadata checks also precede a cancellation decision, before its first restoration batch. */
  void validateMetadata(final RecordPublicationIntent intent, final Map<String, RecordPublicationPatchCompiler.Resolved> models) {
    patches.validate(intent, models);
  }

  private RecordPublicationRows rows(final RecordPublicationIntent intent, final Map<String, RecordPublicationPatchCompiler.Resolved> models) {
    patches.validate(intent, models);
    return new RecordPublicationRows(intent, models, sql, schemas);
  }

  private RecordPublicationStore.Entry existing(final RecordPublicationIntent intent, final RecordPublicationSession session,
      final String cancellationFingerprint) {
    var entry = journal.read(intent.key(), intent.command().targetVersion(), 
        session.read(journal.lookup(intent.key(), intent.command().targetVersion())));
    if (entry == null) {
      throw invalid();
    }
    return validate(intent, entry, session, cancellationFingerprint);
  }

  private RecordPublicationStore.Entry validate(final RecordPublicationIntent intent, final RecordPublicationStore.Entry entry,
      final RecordPublicationSession session, final String cancellationFingerprint) {
    if (!entry.intent().equals(intent)) {
      throw conflict();
    }
    if (entry.checkpoint().progress() != null) {
      requireOriginal(intent, session);
      return entry;
    }
    var admission = session.admission();
    boolean committed = entry.checkpoint().phase() == RecordPublicationCheckpoint.Phase.COMMITTED;
    if (admission.revision() < intent.command().targetVersion()
        || admission.revision() == intent.command().targetVersion()
            && (admission.previousRevision() != intent.command().expectedVersion()
                || !admission.fingerprint().equals(committed ? intent.fingerprint() : cancellationFingerprint))) {
      throw invalid();
    }
    var installed = calculations.read(intent.key(), intent.command().targetVersion(),
        session.read(calculations.lookup(intent.key(), intent.command().targetVersion())));
    if (committed ? installed == null || installed.revision() != intent.command().targetVersion() || !installed.policy().equals(policy(intent))
        : !Objects.equals(original(installed), intent.previousCalculation())) {
      throw invalid();
    }
    return entry;
  }

  private void requireOriginal(final RecordPublicationIntent intent, final RecordPublicationSession session) {
    if (session.admission().revision() != intent.command().expectedVersion()
        || !session.admission().fingerprint().equals(intent.previousFingerprint())) {
      throw conflict();
    }
    var installed = calculations.read(intent.key(), intent.command().expectedVersion(),
        session.read(calculations.lookup(intent.key(), intent.command().expectedVersion())));
    if (!Objects.equals(original(installed), intent.previousCalculation())) {
      throw invalid();
    }
  }

  private static RecordPublicationIntent.InstalledCalculation original(final RecordCalculationStore.Installed installed) {
    return installed == null ? null : new RecordPublicationIntent.InstalledCalculation(installed.revision(),
        RecordCalculationStore.policyChecksum(installed.policy()));
  }

  private RecordPublicationStore.Entry advance(final RecordPublicationStore.Entry previous,
      final RecordPublicationCheckpoint next, final RecordPublicationSession session) {
    session.write(journal.advance(previous, next, now()));
    return new RecordPublicationStore.Entry(previous.intent(), next);
  }

  private static RecordCalculationPolicy policy(final RecordPublicationIntent intent) {
    var command = intent.command();
    return new RecordCalculationPolicy(1, command.appId(), command.formId(), command.schemaReference(), command.calculation());
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT, "Publication intent or admission version changed");
  }

  private static DnaRuntimeException invalid() {
    return new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        "Publication checkpoint, aggregate counts or installed policy is inconsistent");
  }
}
