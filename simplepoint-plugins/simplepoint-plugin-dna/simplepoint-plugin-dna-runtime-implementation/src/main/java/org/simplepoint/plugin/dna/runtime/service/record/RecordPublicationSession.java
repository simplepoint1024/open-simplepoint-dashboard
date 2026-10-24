package org.simplepoint.plugin.dna.runtime.service.record;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Borrowed publication target transaction. Only its backend owns commit/rollback and connection close. */
final class RecordPublicationSession {
  private final Connection connection;
  private final RecordWriteFence fence;
  private final RecordWriteFence.Snapshot admission;
  private final RecordExecutionDeadline deadline;
  private final Function<RecordSelectPlan, CalciteQueryResult> reader;
  private final Function<RecordSelectPlan, CalciteQueryResult> aggregateReader;
  private final ToIntFunction<RecordSqlStatement> writer;

  RecordPublicationSession(final Connection connection, final RecordWriteFence fence, final RecordWriteFence.Snapshot admission,
      final RecordExecutionDeadline deadline, final Function<RecordSelectPlan, CalciteQueryResult> reader,
      final Function<RecordSelectPlan, CalciteQueryResult> aggregateReader, final ToIntFunction<RecordSqlStatement> writer) {
    this.connection = connection;
    this.fence = fence;
    this.admission = admission;
    this.deadline = deadline;
    this.reader = reader;
    this.aggregateReader = aggregateReader;
    this.writer = writer;
  }

  RecordWriteFence.Snapshot admission() {
    return admission;
  }

  CalciteQueryResult read(final RecordSelectPlan plan) {
    deadline.remainingMillis();
    return reader.apply(plan);
  }

  CalciteQueryResult aggregate(final RecordSelectPlan plan) {
    deadline.remainingMillis();
    return aggregateReader.apply(plan);
  }

  void write(final RecordSqlStatement statement) {
    deadline.remainingMillis();
    if (writer.applyAsInt(statement) != 1) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED,
          "Publication batch mutation did not affect exactly one row");
    }
  }

  void activate(final RecordPublicationIntent intent) {
    try {
      fence.publish(connection, intent.key(), intent.command().expectedVersion(), intent.command().targetVersion(),
          intent.fingerprint(), intent.actorId(), deadline, borrowed -> {});
    } catch (SQLException failure) {
      throw failed(failure);
    }
  }

  void cancel(final RecordPublicationIntent intent, final String cancellationFingerprint) {
    try {
      var resolution = fence.cancel(connection, intent.key(), intent.command().expectedVersion(), intent.command().targetVersion(),
          intent.fingerprint(), cancellationFingerprint, intent.actorId(), deadline);
      if (!resolution.cancelled()) {
        throw failed(null);
      }
    } catch (SQLException failure) {
      throw failed(failure);
    }
  }

  private static DnaRuntimeException failed(final Throwable failure) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED, "Publication admission transition failed", failure);
  }
}
