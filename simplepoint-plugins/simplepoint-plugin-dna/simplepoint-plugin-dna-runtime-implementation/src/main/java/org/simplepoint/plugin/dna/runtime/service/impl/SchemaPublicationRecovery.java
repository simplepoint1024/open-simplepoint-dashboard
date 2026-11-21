package org.simplepoint.plugin.dna.runtime.service.impl;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStepStatus;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPublicationStateService.PersistedStep;
import org.simplepoint.plugin.dna.runtime.service.schema.InitialSchemaPlan;

/** Accepts only the durable completed prefix, or its one attempted statement with a lost acknowledgement. */
final class SchemaPublicationRecovery {
  private SchemaPublicationRecovery() {
  }

  static int reflectedSteps(InitialSchemaPlan plan, List<PersistedStep> persisted, String actualChecksum) {
    if (persisted.size() != plan.steps().size() || plan.checkpointChecksums().size() != plan.steps().size() + 1) throw drift();
    int completed = 0;
    boolean pending = false;
    for (int index = 0; index < persisted.size(); index++) {
      var saved = persisted.get(index);
      var planned = plan.steps().get(index);
      if (saved.sequence() != index + 1 || planned.sequence() != saved.sequence()
          || !planned.operation().name().equals(saved.operation())
          || !planned.statementChecksum().equals(saved.statementChecksum()) || saved.attempts() < 0) throw drift();
      if (completed(saved)) {
        if (pending) throw drift();
        completed++;
      } else {
        if (pending && saved.attempts() != 0) throw drift();
        if (saved.status() != SchemaMigrationStepStatus.PENDING) throw drift();
        pending = true;
      }
    }
    if (actualChecksum.equals(plan.checkpointChecksums().get(completed))) return completed;
    if (completed < persisted.size() && persisted.get(completed).attempts() > 0
        && actualChecksum.equals(plan.checkpointChecksums().get(completed + 1))) return completed + 1;
    throw drift();
  }

  static boolean completed(PersistedStep step) {
    return step.status() == SchemaMigrationStepStatus.SUCCEEDED || step.status() == SchemaMigrationStepStatus.SKIPPED;
  }

  private static DnaRuntimeException drift() {
    return new DnaRuntimeException(DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED,
        "Publication progress and live structure do not match the original recovery checkpoints");
  }
}
