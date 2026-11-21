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
  public RecordPublicationBatchExecutor(
      final PhysicalSchemaCodec schemas, final RecordSqlCompiler sql) {
    this.schemas = schemas;
    this.sql = sql;
    this.patches = new RecordPublicationPatchCompiler(schemas, sql);
  }

  RecordCollectionRebind rebind(RecordWriteFenceKey key,
      org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand command,
      RecordPublicationMetadata.Rebind scope) {
    return new RecordCollectionRebind(key, command, scope.source().binding(), scope.destination().binding(), sql);
  }

  /** Freeze query restrictions with the same graph and prior immutable policy as publication. */
  RecordPublicationIntent protect(
      final RecordPublicationIntent intent,
      final Map<String, RecordPublicationPatchCompiler.Resolved> models,
      final RecordCalculationStore.Installed previous) {
    if (!Objects.equals(original(previous), intent.previousCalculation())) {
      throw invalid();
    }
    return intent.withQueryProtection(rows(intent, models).queryProtection(previous));
  }

  RecordPublicationStore.Entry start(
      final RecordPublicationIntent intent,
      final Map<String, RecordPublicationPatchCompiler.Resolved> models,
      final RecordPublicationSession session,
      final String cancellationFingerprint) {
    var existing =
        journal.read(
            intent.key(),
            intent.command().targetVersion(),
            session.read(journal.lookup(intent.key(), intent.command().targetVersion())));
    if (existing != null) {
      return validate(intent, existing, session, cancellationFingerprint);
    }
    requireOriginal(intent, session);
    if (journal.readActive(intent.key(), session.read(journal.active(intent.key()))) != null) {
      throw conflict();
    }
    long count =
        intent.rebind() == null
            ? RecordPublicationRows.count(session.aggregate(rows(intent, models).count()))
            : intent.rebind().edgeCount();
    var entry = new RecordPublicationStore.Entry(intent, RecordPublicationCheckpoint.start(count));
    session.write(journal.insert(entry));
    return entry;
  }

  /** Stale sequence replay returns the committed checkpoint instead of executing another batch. */
  RecordPublicationStore.Entry step(
      final RecordPublicationIntent intent,
      final Map<String, RecordPublicationPatchCompiler.Resolved> models,
      final Map<RecordSchemaReference, RuntimeRecordModel> historical,
      final long expectedSequence,
      final int limit,
      final String cancellationFingerprint,
      final RecordPublicationSession session) {
    return step(
        intent,
        models,
        historical,
        expectedSequence,
        limit,
        cancellationFingerprint,
        session,
        null);
  }

  RecordPublicationStore.Entry step(
      RecordPublicationIntent intent,
      Map<String, RecordPublicationPatchCompiler.Resolved> models,
      Map<RecordSchemaReference, RuntimeRecordModel> historical,
      long expectedSequence,
      int limit,
      String cancellationFingerprint,
      RecordPublicationSession session,
      RecordReferenceCollectionConversion conversion) {
    return step(intent, models, historical, expectedSequence, limit, cancellationFingerprint,
        session, conversion, null);
  }

  RecordPublicationStore.Entry step(
      RecordPublicationIntent intent,
      Map<String, RecordPublicationPatchCompiler.Resolved> models,
      Map<RecordSchemaReference, RuntimeRecordModel> historical,
      long expectedSequence,
      int limit,
      String cancellationFingerprint,
      RecordPublicationSession session,
      RecordReferenceCollectionConversion conversion,
      RecordCollectionRebind rebind) {
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
    if ((intent.conversion() != null) != (conversion != null)
        || (intent.rebind() != null) != (rebind != null)) {
      throw invalid();
    }
    if (rebind != null) {
      return stepRebind(entry, rebind, models, historical, session, limit, cancellationFingerprint);
    }
    var rows = rows(intent, models);
    return switch (entry.checkpoint().phase()) {
      case STAGING -> stage(entry, rows, session, limit);
      case APPLYING -> apply(entry, models, historical, rows, session, limit, conversion);
      case RESTORING ->
          restore(entry, models, rows, session, limit, cancellationFingerprint, conversion);
      default -> throw invalid();
    };
  }

  private RecordPublicationStore.Entry stepRebind(
      RecordPublicationStore.Entry entry, RecordCollectionRebind rebind,
      Map<String, RecordPublicationPatchCompiler.Resolved> models,
      Map<RecordSchemaReference, RuntimeRecordModel> historical, RecordPublicationSession session,
      int limit, String cancellationFingerprint) {
    var intent = entry.intent();
    var checkpoint = entry.checkpoint();
    if (checkpoint.phase() == RecordPublicationCheckpoint.Phase.STAGING) {
      var edges = rebind.page(checkpoint.stagingCursor(), limit, intent, session);
      if (checkpoint.staged() == checkpoint.total()) {
        if (!edges.isEmpty()) {
          throw invalid();
        }
        requireCounts(entry, session);
        return advance(entry, checkpoint.transition(RecordPublicationCheckpoint.Phase.APPLYING), session);
      }
      if (edges.isEmpty() || edges.size() > checkpoint.total() - checkpoint.staged()) {
        throw invalid();
      }
      for (var edge : edges) {
        stages.inserts(stages.prepare(intent, RecordPublicationAggregate.edge(edge))).forEach(session::write);
      }
      return advance(entry, checkpoint.batch(edges.size(), edges.getLast().id()), session);
    }
    boolean restore = checkpoint.phase() == RecordPublicationCheckpoint.Phase.RESTORING;
    long remaining = restore ? checkpoint.applied() - checkpoint.restored()
        : checkpoint.total() - checkpoint.applied();
    if (remaining > 0) {
      String cursor = restore ? checkpoint.restorationCursor() : checkpoint.applicationCursor();
      var phase = restore ? RecordPublicationStages.Phase.APPLIED : RecordPublicationStages.Phase.STAGED;
      var headers = stages.readPage(intent, phase, cursor, limit,
          session.read(stages.page(intent, phase, cursor, limit)));
      if (headers.isEmpty() || headers.size() > remaining) {
        throw invalid();
      }
      var edges = headers.stream().map(header -> stages.payload(intent, header, session::read).reboundEdge()).toList();
      var plan = rebind.prepare(intent, edges, restore, session);
      session.lockRows(plan);
      plan.statements().forEach(session::write);
      for (var header : headers) {
        session.write(stages.advance(intent, header,
            restore ? RecordPublicationStages.Phase.RESTORED : RecordPublicationStages.Phase.APPLIED, now()));
      }
      return advance(entry, checkpoint.batch(headers.size(), headers.getLast().parentRecordId()), session);
    }
    requireCounts(entry, session);
    if (rebind.activeSourceCount(session) != checkpoint.total()
        || rebind.destinationCount(session) != (restore ? 0 : checkpoint.total())) {
      throw RecordCollectionRebind.conflict();
    }
    if (restore) {
      session.cancel(intent, cancellationFingerprint);
      return advance(entry, checkpoint.transition(RecordPublicationCheckpoint.Phase.CANCELLED), session);
    }
    RecordPublicationChecks.verify(intent, models.get(intent.key().modelId()).model(), historical, sql, schemas, session);
    rebind.retireSource(intent, checkpoint.total(), session);
    return install(entry, session);
  }

  /** Persist the restoration decision before any undo work; repeats never regress progress. */
  RecordPublicationStore.Entry requestCancellation(
      final RecordPublicationIntent intent,
      final String cancellationFingerprint,
      final RecordPublicationSession session) {
    var entry = existing(intent, session, cancellationFingerprint);
    if (entry.checkpoint().progress() == null
        || entry.checkpoint().phase() == RecordPublicationCheckpoint.Phase.RESTORING) {
      return entry;
    }
    return advance(
        entry, entry.checkpoint().transition(RecordPublicationCheckpoint.Phase.RESTORING), session);
  }

  /** A separate attempt records a safe diagnostic only if the failed batch did not commit. */
  RecordPublicationStore.Entry failed(
      final RecordPublicationIntent intent,
      final long expectedSequence,
      final DnaRuntimeErrorCode code,
      final String cancellationFingerprint,
      final RecordPublicationSession session) {
    var entry = existing(intent, session, cancellationFingerprint);
    if (entry.checkpoint().progress() == null
        || expectedSequence != entry.checkpoint().sequence()) {
      return entry;
    }
    return advance(entry, entry.checkpoint().failed(code), session);
  }

  private RecordPublicationStore.Entry stage(
      final RecordPublicationStore.Entry entry,
      final RecordPublicationRows rows,
      final RecordPublicationSession session,
      final int limit) {
    var checkpoint = entry.checkpoint();
    var ids = rows.identities(checkpoint.stagingCursor(), limit, session::read);
    if (checkpoint.staged() == checkpoint.total()) {
      if (!ids.isEmpty()
          || RecordPublicationRows.count(session.aggregate(rows.count())) != checkpoint.total()) {
        throw invalid();
      }
      requireCounts(entry, session);
      return advance(
          entry, checkpoint.transition(RecordPublicationCheckpoint.Phase.APPLYING), session);
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

  private RecordPublicationStore.Entry apply(
      final RecordPublicationStore.Entry entry,
      final Map<String, RecordPublicationPatchCompiler.Resolved> models,
      final Map<RecordSchemaReference, RuntimeRecordModel> historical,
      final RecordPublicationRows rows,
      final RecordPublicationSession session,
      final int limit,
      RecordReferenceCollectionConversion conversion) {
    var checkpoint = entry.checkpoint();
    if (checkpoint.applied() < checkpoint.total()) {
      return mutate(entry, models, session, limit, false, conversion);
    }
    requireCounts(entry, session);
    if (RecordPublicationRows.count(session.aggregate(rows.count())) != checkpoint.total()) {
      throw invalid();
    }
    var intent = entry.intent();
    if (conversion != null) {
      conversion.verifyResult(intent.conversion().sourceCount(), false, session);
    }
    RecordPublicationChecks.verify(
        intent, models.get(intent.key().modelId()).model(), historical, sql, schemas, session);
    return install(entry, session);
  }

  private RecordPublicationStore.Entry install(
      RecordPublicationStore.Entry entry, RecordPublicationSession session) {
    var intent = entry.intent();
    session.write(
        calculations.insert(
            intent.key(),
            intent.command().targetVersion(),
            intent.actorId(),
            now(),
            policy(intent, session)));
    session.activate(intent);
    return advance(
        entry, entry.checkpoint().transition(RecordPublicationCheckpoint.Phase.COMMITTED), session);
  }

  private RecordPublicationStore.Entry restore(
      final RecordPublicationStore.Entry entry,
      final Map<String, RecordPublicationPatchCompiler.Resolved> models,
      final RecordPublicationRows rows,
      final RecordPublicationSession session,
      final int limit,
      final String cancellationFingerprint,
      RecordReferenceCollectionConversion conversion) {
    if (entry.checkpoint().restored() < entry.checkpoint().applied()) {
      return mutate(entry, models, session, limit, true, conversion);
    }
    requireCounts(entry, session);
    if (RecordPublicationRows.count(session.aggregate(rows.count()))
        != entry.checkpoint().total()) {
      throw invalid();
    }
    if (conversion != null) {
      conversion.verifyResult(entry.intent().conversion().sourceCount(), true, session);
    }
    session.cancel(entry.intent(), cancellationFingerprint);
    return advance(
        entry, entry.checkpoint().transition(RecordPublicationCheckpoint.Phase.CANCELLED), session);
  }

  private RecordPublicationStore.Entry mutate(
      final RecordPublicationStore.Entry entry,
      final Map<String, RecordPublicationPatchCompiler.Resolved> models,
      final RecordPublicationSession session,
      final int limit,
      final boolean restore,
      RecordReferenceCollectionConversion conversion) {
    var intent = entry.intent();
    var checkpoint = entry.checkpoint();
    String cursor = restore ? checkpoint.restorationCursor() : checkpoint.applicationCursor();
    var phase =
        restore ? RecordPublicationStages.Phase.APPLIED : RecordPublicationStages.Phase.STAGED;
    var headers =
        stages.readPage(
            intent, phase, cursor, limit, session.read(stages.page(intent, phase, cursor, limit)));
    long remaining =
        restore
            ? checkpoint.applied() - checkpoint.restored()
            : checkpoint.total() - checkpoint.applied();
    if (headers.isEmpty() || headers.size() > remaining) {
      throw invalid();
    }
    final var discovery = new RecordPublicationLockDiscovery(sql, schemas);
    final var locked = new java.util.ArrayList<RecordPublicationLockPlan>();
    final var selected = new java.util.ArrayList<RecordPublicationStages.Header>();
    final var originImages = new java.util.ArrayList<Boolean>();
    final var statements = new java.util.ArrayList<RecordSqlStatement>();
    final var references = new java.util.ArrayList<RecordReferenceCheck>();
    int payloadBytes = 0;
    for (var header : headers) {
      // Preparing locks must not retain ten maximum-sized aggregate payloads at once.
      if (!selected.isEmpty()
          && header.payloadBytes() > RecordPublicationPayload.MAX_BYTES - payloadBytes) {
        break;
      }
      var aggregate = stages.payload(intent, header, session::read);
      var compiled =
          patches.compile(
              intent,
              aggregate,
              models,
              restore
                  ? RecordPublicationPatchCompiler.Direction.RESTORE
                  : RecordPublicationPatchCompiler.Direction.APPLY);
      var discovered = discovery.discover(intent, models, compiled, session::read);
      var plan = discovered;
      if (conversion != null) {
        var work = new java.util.ArrayList<>(compiled);
        var checks = new java.util.ArrayList<>(discovered.references());
        conversion.appendChange(intent, aggregate, restore, work, checks, session);
        plan = new RecordPublicationLockPlan(work, checks, discovered.verifyReads());
      }
      // Keep complete aggregates and cursor order. A large next aggregate belongs to the next
      // batch.
      if (!RecordPublicationLockPlan.fits(
          statements.size() + plan.statements().size(),
          references.size() + plan.references().size())) {
        if (selected.isEmpty()) {
          throw invalid();
        }
        break;
      }
      selected.add(header);
      originImages.add(aggregate.contractVersion() == 2);
      payloadBytes += header.payloadBytes();
      locked.add(plan);
      statements.addAll(plan.statements());
      references.addAll(plan.references());
    }
    session.lockRows(
        new RecordPublicationLockPlan(
            statements, references, () -> locked.forEach(plan -> plan.verifyReads().run())));
    for (int index = 0; index < selected.size(); index++) {
      locked.get(index).statements().forEach(session::write);
      // Reload the frozen payload within the same fenced transaction rather than retaining all
      // aggregate origin images while discovering a complete business lock set.
      if (originImages.get(index)) {
        var aggregate = stages.payload(intent, selected.get(index), session::read);
        for (var row : aggregate.rows()) {
          if (row.copyChange() != null) {
            row.copyChange().execute(intent, row, restore, session);
          }
        }
      }
      session.write(
          stages.advance(
              intent,
              selected.get(index),
              restore
                  ? RecordPublicationStages.Phase.RESTORED
                  : RecordPublicationStages.Phase.APPLIED,
              now()));
    }
    return advance(
        entry, checkpoint.batch(selected.size(), selected.getLast().parentRecordId()), session);
  }

  private void requireCounts(
      final RecordPublicationStore.Entry entry, final RecordPublicationSession session) {
    var counts = stages.readCounts(session.aggregate(stages.counts(entry.intent())));
    var checkpoint = entry.checkpoint();
    if (counts.getOrDefault(RecordPublicationStages.Phase.STAGED, 0L)
            != checkpoint.staged() - checkpoint.applied()
        || counts.getOrDefault(RecordPublicationStages.Phase.APPLIED, 0L)
            != checkpoint.applied() - checkpoint.restored()
        || counts.getOrDefault(RecordPublicationStages.Phase.RESTORED, 0L)
            != checkpoint.restored()) {
      throw invalid();
    }
  }

  /** Metadata checks also precede a cancellation decision, before its first restoration batch. */
  void validateMetadata(
      final RecordPublicationIntent intent,
      final Map<String, RecordPublicationPatchCompiler.Resolved> models) {
    patches.validate(intent, models);
  }

  private RecordPublicationRows rows(
      final RecordPublicationIntent intent,
      final Map<String, RecordPublicationPatchCompiler.Resolved> models) {
    patches.validate(intent, models);
    return new RecordPublicationRows(intent, models, sql, schemas);
  }

  private RecordPublicationStore.Entry existing(
      final RecordPublicationIntent intent,
      final RecordPublicationSession session,
      final String cancellationFingerprint) {
    var entry =
        journal.read(
            intent.key(),
            intent.command().targetVersion(),
            session.read(journal.lookup(intent.key(), intent.command().targetVersion())));
    if (entry == null) {
      throw invalid();
    }
    return validate(intent, entry, session, cancellationFingerprint);
  }

  private RecordPublicationStore.Entry validate(
      final RecordPublicationIntent intent,
      final RecordPublicationStore.Entry entry,
      final RecordPublicationSession session,
      final String cancellationFingerprint) {
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
                || !admission
                    .fingerprint()
                    .equals(committed ? intent.fingerprint() : cancellationFingerprint))) {
      throw invalid();
    }
    var installed =
        calculations.read(
            intent.key(),
            intent.command().targetVersion(),
            session.read(calculations.lookup(intent.key(), intent.command().targetVersion())));
    if (committed
        ? installed == null
            || installed.revision() != intent.command().targetVersion()
            || !installed.policy().equals(policy(intent, session))
        : !Objects.equals(original(installed), intent.previousCalculation())) {
      throw invalid();
    }
    return entry;
  }

  private void requireOriginal(
      final RecordPublicationIntent intent, final RecordPublicationSession session) {
    if (session.admission().revision() != intent.command().expectedVersion()
        || !session.admission().fingerprint().equals(intent.previousFingerprint())) {
      throw conflict();
    }
    var installed =
        calculations.read(
            intent.key(),
            intent.command().expectedVersion(),
            session.read(calculations.lookup(intent.key(), intent.command().expectedVersion())));
    if (!Objects.equals(original(installed), intent.previousCalculation())) {
      throw invalid();
    }
  }

  private static RecordPublicationIntent.InstalledCalculation original(
      final RecordCalculationStore.Installed installed) {
    return installed == null
        ? null
        : new RecordPublicationIntent.InstalledCalculation(
            installed.revision(), RecordCalculationStore.policyChecksum(installed.policy()));
  }

  private RecordPublicationStore.Entry advance(
      final RecordPublicationStore.Entry previous,
      final RecordPublicationCheckpoint next,
      final RecordPublicationSession session) {
    session.write(journal.advance(previous, next, now()));
    return new RecordPublicationStore.Entry(previous.intent(), next);
  }

  private RecordCalculationPolicy policy(
      RecordPublicationIntent intent, RecordPublicationSession session) {
    var command = intent.command();
    var previous = calculations.read(intent.key(), command.expectedVersion(),
        session.read(calculations.lookup(intent.key(), command.expectedVersion())));
    if (!Objects.equals(original(previous), intent.previousCalculation())) {
      throw invalid();
    }
    var retired = RecordCollectionRetirement.validateTransition(command, previous);
    RecordReferenceCollectionConversion.requireTransition(command, previous);
    if (command.collectionRebind() != null) {
      RecordCollectionRebind.requireInstalled(command, previous);
    }
    return new RecordCalculationPolicy(
        1,
        command.appId(),
        command.formId(),
        command.schemaReference(),
        command.calculation(),
        intent.queryProtection(), retired);
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT,
        "Publication intent or admission version changed");
  }

  private static DnaRuntimeException invalid() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        "Publication checkpoint, aggregate counts or installed policy is inconsistent");
  }
}
