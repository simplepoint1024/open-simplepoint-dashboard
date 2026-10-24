package org.simplepoint.plugin.dna.runtime.service.record;

import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;

/** Immutable internal envelope; public snapshot responses remain authorized record projections. */
record RecordStoredSnapshot(int contractVersion, long calculationRevision, String calculationChecksum,
    OwnedRecordView values) {
  RecordStoredSnapshot {
    if (contractVersion != 2 || calculationRevision < 0 || values == null
        || (calculationRevision == 0 ? calculationChecksum != null
            : calculationChecksum == null || !calculationChecksum.matches("[a-f0-9]{64}"))) {
      throw new IllegalArgumentException("Snapshot provenance is invalid");
    }
  }

  static RecordStoredSnapshot capture(final OwnedRecordView values, final RecordCalculationStore.Installed installed) {
    return new RecordStoredSnapshot(2, installed == null ? 0 : installed.revision(),
        installed == null ? null : RecordCalculationStore.policyChecksum(installed.policy()), values);
  }
}
