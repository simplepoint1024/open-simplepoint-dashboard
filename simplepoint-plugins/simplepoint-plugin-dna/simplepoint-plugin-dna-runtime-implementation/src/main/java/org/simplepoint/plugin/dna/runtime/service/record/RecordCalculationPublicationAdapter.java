package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyCancellationView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationView;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.stereotype.Component;

/** Projects durable target decisions to the existing public protocol; authority and audit remain in the owning service. */
@Component
class RecordCalculationPublicationAdapter {
  private final RecordPublicationCoordinator coordinator;
  private final RecordRequestFingerprint fingerprints;

  RecordCalculationPublicationAdapter(final RecordPublicationCoordinator coordinator, final RecordRequestFingerprint fingerprints) {
    this.coordinator = coordinator;
    this.fingerprints = fingerprints;
  }

  RecordWritePolicyPublicationView publish(final RuntimeActor actor, final RecordWritePolicyPublicationCommand command) {
    try {
      var entry = coordinator.advance(actor, command, false);
      if (entry.checkpoint().phase() == RecordPublicationCheckpoint.Phase.CANCELLED) {
        throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT, "The publication was already cancelled");
      }
      boolean committed = entry.checkpoint().phase() == RecordPublicationCheckpoint.Phase.COMMITTED;
      return new RecordWritePolicyPublicationView(committed, committed ? command.targetVersion() : command.expectedVersion(),
          entry.intent().fingerprint(), Map.of(), Map.of(), entry.checkpoint().progress());
    } catch (RecordPublicationChecks.Rejected rejected) {
      if (rejected.progress() == null) {
        // No durable sequence (or a concurrent direction change): never present stale counts as current inspection.
        throw rejected;
      }
      return new RecordWritePolicyPublicationView(false, command.expectedVersion(), fingerprints.of(command),
          rejected.violations(), rejected.valueViolations(), rejected.progress());
    }
  }

  RecordWritePolicyCancellationView cancel(final RuntimeActor actor, final RecordWritePolicyPublicationCommand command) {
    var entry = coordinator.advance(actor, command, true);
    boolean cancelled = entry.checkpoint().phase() == RecordPublicationCheckpoint.Phase.CANCELLED;
    var progress = entry.checkpoint().progress();
    return new RecordWritePolicyCancellationView(cancelled, progress == null ? command.targetVersion() : command.expectedVersion(),
        entry.intent().fingerprint(),
        progress != null ? null : cancelled ? fingerprints.cancellationOf(command) : entry.intent().fingerprint(), progress);
  }
}
