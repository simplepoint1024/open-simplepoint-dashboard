package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.stereotype.Component;

/** Composes control locks, fresh trusted metadata and one committed target batch; no background authorization snapshot. */
@Component
public class RecordPublicationCoordinator {
  private final RecordPublicationControlFence control;
  private final RecordPublicationMetadata metadata;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordPublicationBatchExecutor batches;
  private final RecordRequestFingerprint fingerprints;
  private final RecordPublicationStore journal = new RecordPublicationStore();
  private final RecordCalculationStore calculations = new RecordCalculationStore();

  /** The authorized service supplies the current trusted actor; recovery reuses the durable original command and clock. */
  public RecordPublicationCoordinator(final RecordPublicationControlFence control, final RecordPublicationMetadata metadata,
      final RuntimeCalciteRecordBackend backend, final RecordPublicationBatchExecutor batches, final RecordRequestFingerprint fingerprints) {
    this.control = control;
    this.metadata = metadata;
    this.backend = backend;
    this.batches = batches;
    this.fingerprints = fingerprints;
  }

  RecordPublicationStore.Entry advance(final RuntimeActor actor, final RecordWritePolicyPublicationCommand command, final boolean cancel) {
    var attempted = new AtomicReference<RecordPublicationStore.Entry>();
    try {
      return execute(actor, command, (snapshot, session) -> {
        var key = new RecordWriteFenceKey(actor.tenantId(), command.physicalModelId());
        String fingerprint = fingerprints.of(command);
        String cancellation = fingerprints.cancellationOf(command);
        var entry = journal.read(key, command.targetVersion(), session.read(journal.lookup(key, command.targetVersion())));
        if (entry == null) {
          var previous = calculations.read(key, command.expectedVersion(), session.read(calculations.lookup(key, command.expectedVersion())));
          var identity = previous == null ? null : new RecordPublicationIntent.InstalledCalculation(previous.revision(),
              RecordCalculationStore.policyChecksum(previous.policy()));
          var intent = new RecordPublicationIntent(2, key, command, fingerprint, session.admission().fingerprint(), actor.actorId(),
              Instant.now().truncatedTo(ChronoUnit.MICROS).toString(), snapshot.bindings(), identity, snapshot.resources());
          entry = batches.start(intent, snapshot.models(), session, cancellation);
          // Commit maintenance ownership before any potentially expensive calculation work.
          return cancel ? batches.requestCancellation(intent, cancellation, session) : entry;
        }
        var intent = entry.intent();
        if (!intent.command().equals(command) || !intent.fingerprint().equals(fingerprint)) {
          throw conflict();
        }
        if (entry.checkpoint().progress() == null) {
          return batches.start(intent, snapshot.models(), session, cancellation);
        }
        if (intent.contractVersion() != 2) {
          throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
              "Legacy publication maintenance has no trusted resource snapshot; explicit recovery is required");
        }
        // A delayed publish request must not overwrite restoration progress with a false failure.
        if (!cancel && entry.checkpoint().phase() == RecordPublicationCheckpoint.Phase.RESTORING) {
          throw conflict();
        }
        attempted.set(entry);
        batches.validateMetadata(intent, snapshot.models());
        if (cancel && entry.checkpoint().phase() != RecordPublicationCheckpoint.Phase.RESTORING) {
          return batches.requestCancellation(intent, cancellation, session);
        }
        return batches.step(intent, snapshot.models(), snapshot.historical(), entry.checkpoint().sequence(), 10, cancellation, session);
      });
    } catch (RuntimeException failure) {
      var entry = attempted.get();
      if (entry != null) {
        var recorded = recordFailure(actor, command, entry, failure);
        if (failure instanceof RecordPublicationChecks.Rejected rejected && recorded != null
            && recorded.checkpoint().phase() == RecordPublicationCheckpoint.Phase.APPLYING
            && recorded.checkpoint().sequence() == entry.checkpoint().sequence() + 1
            && recorded.checkpoint().failureCode() == DnaRuntimeErrorCode.RECORD_WRITE_POLICY_PRECHECK_FAILED) {
          throw new RecordPublicationChecks.Rejected(rejected.violations(), rejected.valueViolations(), recorded.checkpoint().progress());
        }
      }
      throw failure;
    }
  }

  private RecordPublicationStore.Entry recordFailure(final RuntimeActor actor, final RecordWritePolicyPublicationCommand command,
      final RecordPublicationStore.Entry attempted, final RuntimeException failure) {
    var code = failure instanceof DnaRuntimeException dna ? dna.errorCode() : DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED;
    try {
      return execute(actor, command, (snapshot, session) -> batches.failed(attempted.intent(), attempted.checkpoint().sequence(),
          code, fingerprints.cancellationOf(command), session));
    } catch (RuntimeException diagnosticFailure) {
      // The original error remains authoritative. Failure of diagnostics never changes the business outcome.
      failure.addSuppressed(diagnosticFailure);
      return null;
    }
  }

  private <T> T execute(final RuntimeActor actor, final RecordWritePolicyPublicationCommand command,
      final BiFunction<RecordPublicationMetadata.Snapshot, RecordPublicationSession, T> work) {
    if (command.calculation() == null) {
      throw new IllegalArgumentException("Calculation publication requires an explicit definition");
    }
    var deadline = new RecordExecutionDeadline(Math.min(command.timeoutMs(), 30_000));
    var ids = new LinkedHashSet<String>();
    ids.add(command.physicalModelId());
    command.calculation().details().values().forEach(scope -> ids.add(scope.physicalModelId()));
    return control.execute(actor.tenantId(), ids, deadline, locked -> {
      var snapshot = metadata.resolve(actor, command, locked);
      var root = snapshot.models().get(command.physicalModelId()).model();
      var children = snapshot.models().values().stream().map(RecordPublicationPatchCompiler.Resolved::model)
          .filter(model -> !model.physicalModelId().equals(root.physicalModelId())).toList();
      return backend.publication(root, children, new RecordWriteFenceKey(actor.tenantId(), root.physicalModelId()),
          deadline.remainingMillis(), session -> work.apply(snapshot, session));
    });
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT, "Publication identity or recovery direction changed");
  }
}
