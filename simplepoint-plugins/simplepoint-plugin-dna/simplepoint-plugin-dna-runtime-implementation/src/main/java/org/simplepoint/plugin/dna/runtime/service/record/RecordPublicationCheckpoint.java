package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPhase;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyProgress;

/** Aggregate counters and stable identity cursors committed with the corresponding target batch. */
record RecordPublicationCheckpoint(
    Phase phase, long sequence, long total, long staged, long applied, long restored,
    String stagingCursor, String applicationCursor, String restorationCursor, DnaRuntimeErrorCode failureCode
) {
  RecordPublicationCheckpoint {
    Objects.requireNonNull(phase, "phase");
    if (sequence < 1 || total < 0 || staged < 0 || staged > total || applied < 0 || applied > staged
        || restored < 0 || restored > applied
        || !cursor(staged, stagingCursor) || !cursor(applied, applicationCursor) || !cursor(restored, restorationCursor)
        || phase == Phase.STAGING && (applied != 0 || restored != 0)
        || (phase == Phase.APPLYING || phase == Phase.COMMITTED) && (staged != total || restored != 0)
        || phase == Phase.COMMITTED && applied != total || phase == Phase.CANCELLED && restored != applied
        || terminal(phase) && failureCode != null) {
      throw new IllegalArgumentException("Invalid publication checkpoint");
    }
  }

  static RecordPublicationCheckpoint start(final long total) {
    return new RecordPublicationCheckpoint(Phase.STAGING, 1, total, 0, 0, 0, null, null, null, null);
  }

  RecordPublicationCheckpoint batch(final long count, final String lastId) {
    if (count < 1 || terminal(phase)) {
      throw new IllegalArgumentException("A nonempty publication batch is required");
    }
    String previous = switch (phase) {
      case STAGING -> stagingCursor;
      case APPLYING -> applicationCursor;
      case RESTORING -> restorationCursor;
      default -> throw new IllegalStateException("Terminal publication");
    };
    if (Objects.equals(lastId, previous)) {
      throw new IllegalArgumentException("A publication batch must advance its cursor");
    }
    // Database collation owns cursor ordering; Java string ordering must not override it.
    return new RecordPublicationCheckpoint(phase, Math.incrementExact(sequence), total,
        phase == Phase.STAGING ? Math.addExact(staged, count) : staged,
        phase == Phase.APPLYING ? Math.addExact(applied, count) : applied,
        phase == Phase.RESTORING ? Math.addExact(restored, count) : restored,
        phase == Phase.STAGING ? lastId : stagingCursor,
        phase == Phase.APPLYING ? lastId : applicationCursor,
        phase == Phase.RESTORING ? lastId : restorationCursor, null);
  }

  RecordPublicationCheckpoint transition(final Phase next) {
    boolean allowed = switch (phase) {
      case STAGING -> next == Phase.RESTORING || next == Phase.APPLYING && staged == total;
      case APPLYING -> next == Phase.RESTORING || next == Phase.COMMITTED && applied == total;
      case RESTORING -> next == Phase.CANCELLED && restored == applied;
      case COMMITTED, CANCELLED -> false;
    };
    if (!allowed) {
      throw new IllegalArgumentException("Invalid publication phase transition");
    }
    return new RecordPublicationCheckpoint(next, Math.incrementExact(sequence), total, staged, applied, restored,
        stagingCursor, applicationCursor, restorationCursor, null);
  }

  RecordPublicationCheckpoint failed(final DnaRuntimeErrorCode code) {
    if (terminal(phase)) {
      throw new IllegalArgumentException("A terminal publication cannot fail again");
    }
    return new RecordPublicationCheckpoint(phase, Math.incrementExact(sequence), total, staged, applied, restored,
        stagingCursor, applicationCursor, restorationCursor, Objects.requireNonNull(code, "failureCode"));
  }

  /** Rejects forged counter jumps, phase regressions and changes to a frozen total before generating SQL. */
  void requireSuccessor(final RecordPublicationCheckpoint next) {
    Objects.requireNonNull(next, "next");
    RecordPublicationCheckpoint expected;
    if (phase != next.phase) {
      expected = transition(next.phase);
    } else if (next.failureCode != null) {
      expected = failed(next.failureCode);
    } else {
      expected = switch (phase) {
        case STAGING -> batch(next.staged - staged, next.stagingCursor);
        case APPLYING -> batch(next.applied - applied, next.applicationCursor);
        case RESTORING -> batch(next.restored - restored, next.restorationCursor);
        default -> throw new IllegalArgumentException("A terminal publication is immutable");
      };
    }
    if (!expected.equals(next)) {
      throw new IllegalArgumentException("Publication checkpoint does not follow the previous batch");
    }
  }

  RecordWritePolicyProgress progress() {
    if (terminal(phase)) {
      return null;
    }
    long completed = phase == Phase.STAGING ? staged : phase == Phase.APPLYING ? applied : restored;
    return new RecordWritePolicyProgress(1, RecordWritePolicyPhase.valueOf(phase.name()), sequence,
        completed, phase == Phase.RESTORING ? applied : total, failureCode);
  }

  private static boolean cursor(final long count, final String value) {
    return count == 0 ? value == null : value != null && !value.isBlank() && value.length() <= 64 && value.equals(value.trim());
  }

  private static boolean terminal(final Phase phase) {
    return phase == Phase.COMMITTED || phase == Phase.CANCELLED;
  }

  enum Phase { STAGING, APPLYING, RESTORING, COMMITTED, CANCELLED }
}
