package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;

/** Transaction-bound reads; neither policy identity nor connection ownership comes from a client. */
record RecordReadSession(RecordWriteFenceKey key, long revision, RecordCalculationStore.Installed calculation,
    Function<RecordSelectPlan, CalciteQueryResult> query) {

  RecordCalculationStore.Installed historical(final RecordStoredSnapshot snapshot) {
    if (snapshot.calculationRevision() == 0) {
      return null;
    }
    if (snapshot.calculationRevision() > revision) {
      throw invalid();
    }
    var store = new RecordCalculationStore();
    var installed = store.read(key, snapshot.calculationRevision(),
        query.apply(store.lookup(key, snapshot.calculationRevision())));
    if (installed == null || installed.revision() != snapshot.calculationRevision()
        || !RecordCalculationStore.policyChecksum(installed.policy()).equals(snapshot.calculationChecksum())) {
      throw invalid();
    }
    return installed;
  }

  List<RecordCommandResult> receipt(final RecordOperationIdentity operation) {
    requireOwner(operation);
    var journal = new RecordOperationJournal();
    return journal.read(operation, query.apply(journal.lookup(operation)));
  }

  RecordStoredSnapshot snapshot(final RecordOperationIdentity operation) {
    requireOwner(operation);
    var store = new RecordSnapshotStore();
    return store.read(operation, query.apply(store.lookup(operation)));
  }

  private void requireOwner(final RecordOperationIdentity operation) {
    if (!key.tenantId().equals(operation.tenantId()) || !key.modelId().equals(operation.modelId())) {
      throw invalid();
    }
  }

  private static DnaRuntimeException invalid() {
    return new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        "Snapshot calculation provenance is absent or inconsistent");
  }
}
