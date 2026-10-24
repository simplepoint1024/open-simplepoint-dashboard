package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/**
 * Admission after acquiring the root policy lock, on the same physical transaction as the work.
 * A durable nonterminal publication fences ordinary access between worker batches as well as
 * during a batch. Only the publication worker may inspect or mutate its intermediate state.
 */
final class RecordMaintenanceAdmission {
  private final RecordPublicationStore publications = new RecordPublicationStore();

  void requireAvailable(final RecordWriteFenceKey key, final long admittedRevision,
      final Function<RecordSelectPlan, CalciteQueryResult> reader) {
    if (publications.readActive(key, reader.apply(publications.unresolved(key, admittedRevision))) != null) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS,
          "Record calculation publication is in progress; retry after it finishes");
    }
  }

  /** Owned-model inspection must share the root's durable maintenance ownership. */
  static RecordWriteFenceKey key(final RuntimeRecordModel model, final String tenantId) {
    var owner = model.activeSchema().owner();
    return new RecordWriteFenceKey(tenantId, owner == null ? model.physicalModelId() : owner.parentModelId());
  }
}
