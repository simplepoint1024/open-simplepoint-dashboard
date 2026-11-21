package org.simplepoint.plugin.dna.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationOperation;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStepStatus;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalIndexSnapshot;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPublicationStateService.PersistedStep;
import org.simplepoint.plugin.dna.runtime.service.schema.InitialSchemaPlan;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaSnapshotCodec;
import org.simplepoint.plugin.dna.runtime.service.schema.PlannedSchemaStep;

class SchemaPublicationRecoveryTest {
  private final InitialSchemaPlan plan = replacement();

  @Test
  void acceptsEveryCompletedPrefixAndItsSingleLostAcknowledgement() {
    for (int completed = 0; completed <= 2; completed++) {
      var states = progress(completed, false);
      assertThat(SchemaPublicationRecovery.reflectedSteps(plan, states, plan.checkpointChecksums().get(completed)))
          .isEqualTo(completed);
      if (completed < 2) {
        int next = completed + 1;
        assertThat(SchemaPublicationRecovery.reflectedSteps(plan, progress(completed, true), plan.checkpointChecksums().get(next)))
            .isEqualTo(next);
        assertThatThrownBy(() -> SchemaPublicationRecovery.reflectedSteps(plan, states, plan.checkpointChecksums().get(next)))
            .isInstanceOf(DnaRuntimeException.class);
      }
    }
  }

  @Test
  void acceptsCompletedIndexReplacementEvenThoughEarlierDropIsNoLongerVisible() {
    assertThat(SchemaPublicationRecovery.reflectedSteps(plan, progress(2, false), plan.targetSnapshot().checksum())).isEqualTo(2);
  }

  @Test
  void rejectsRollbackUnknownDriftAndNonSequentialExecution() {
    assertThatThrownBy(() -> SchemaPublicationRecovery.reflectedSteps(plan, progress(2, false), plan.actualSnapshot().checksum()))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> SchemaPublicationRecovery.reflectedSteps(plan, progress(0, true), "f".repeat(64)))
        .isInstanceOf(DnaRuntimeException.class);
    var first = progress(0, false).getFirst();
    var completedSecond = progress(2, false).getLast();
    assertThatThrownBy(() -> SchemaPublicationRecovery.reflectedSteps(plan, List.of(first, completedSecond), plan.targetSnapshot().checksum()))
        .isInstanceOf(DnaRuntimeException.class);
    var attemptedSecond = progress(1, true).getLast();
    assertThatThrownBy(() -> SchemaPublicationRecovery.reflectedSteps(plan, List.of(first, attemptedSecond), plan.actualSnapshot().checksum()))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private List<PersistedStep> progress(int completed, boolean nextAttempted) {
    return plan.steps().stream().map(step -> new PersistedStep("step" + step.sequence(), step.sequence(),
        step.operation().name(), step.statementChecksum(), step.sequence() <= completed
            ? SchemaMigrationStepStatus.SUCCEEDED : SchemaMigrationStepStatus.PENDING,
        step.sequence() <= completed || step.sequence() == completed + 1 && nextAttempted ? 1 : 0)).toList();
  }

  private static InitialSchemaPlan replacement() {
    var codec = new PhysicalSchemaSnapshotCodec(new ObjectMapper());
    var before = codec.snapshot(true, List.of(), List.of(new PhysicalIndexSnapshot("lookup", List.of("old"), false, false)), null);
    var middle = codec.snapshot(true, List.of(), List.of(), null);
    var after = codec.snapshot(true, List.of(), List.of(new PhysicalIndexSnapshot("lookup", List.of("new"), true, false)), null);
    var steps = List.of(step(codec, 1, SchemaMigrationOperation.DROP_INDEX, "DROP INDEX lookup"),
        step(codec, 2, SchemaMigrationOperation.CREATE_INDEX, "CREATE UNIQUE INDEX lookup ON records(new)"));
    return new InitialSchemaPlan("postgresql", new DatabaseIdentity("PostgreSQL", "17.0", 17, 0), before, after,
        SchemaMigrationRisk.CONDITIONAL, steps, List.of(before.checksum(), middle.checksum(), after.checksum()));
  }

  private static PlannedSchemaStep step(PhysicalSchemaSnapshotCodec codec, int sequence, SchemaMigrationOperation operation, String sql) {
    return new PlannedSchemaStep(sequence, operation, "lookup", "lookup", SchemaMigrationRisk.CONDITIONAL,
        codec.payload("lookup"), sql, codec.statementChecksum(operation.name(), sql));
  }
}
