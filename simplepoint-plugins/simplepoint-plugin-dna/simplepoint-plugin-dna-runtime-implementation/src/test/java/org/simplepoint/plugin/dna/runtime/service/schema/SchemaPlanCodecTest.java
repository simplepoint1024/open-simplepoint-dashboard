package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationOperation;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.SchemaPlanCodec.PlanBinding;

class SchemaPlanCodecTest {
  private final PhysicalSchemaSnapshotCodec snapshots =
      new PhysicalSchemaSnapshotCodec(new ObjectMapper());
  private final SchemaPlanCodec codec = new SchemaPlanCodec(new ObjectMapper(), snapshots);
  private final PlanBinding binding =
      new PlanBinding(
          "tenant",
          "migration",
          new PhysicalSchemaTarget(
              "model", new RuntimeMetadataTarget("ds", "db", "public", "records")),
          1,
          2,
          "a".repeat(64));

  @Test
  void roundTripsWithoutDependingOnTheCurrentLiveTable() {
    var plan = plan();
    var saved = codec.encode(binding, plan);
    assertThat(codec.decode(binding, saved.json(), saved.checksum())).isEqualTo(plan);
    assertThat(codec.encode(binding, plan)).isEqualTo(saved);
  }

  @Test
  void rejectsChangedSqlAndChangedOwnershipOrTarget() {
    var saved = codec.encode(binding, plan());
    assertThatThrownBy(
            () ->
                codec.decode(binding, saved.json().replace("COMMENT ON", "DROP"), saved.checksum()))
        .isInstanceOf(DnaRuntimeException.class);
    for (var changed :
        List.of(
            new PlanBinding(
                "other", binding.migrationId(), binding.target(), 1, 2, binding.logicalChecksum()),
            new PlanBinding(
                binding.tenantId(), "other", binding.target(), 1, 2, binding.logicalChecksum()),
            new PlanBinding(
                binding.tenantId(),
                binding.migrationId(),
                new PhysicalSchemaTarget(
                    "model", new RuntimeMetadataTarget("other", "db", "public", "records")),
                1,
                2,
                binding.logicalChecksum()),
            new PlanBinding(
                binding.tenantId(),
                binding.migrationId(),
                binding.target(),
                1,
                3,
                binding.logicalChecksum()),
            new PlanBinding(
                binding.tenantId(),
                binding.migrationId(),
                binding.target(),
                1,
                2,
                "b".repeat(64)))) {
      assertThatThrownBy(() -> codec.decode(changed, saved.json(), saved.checksum()))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  @Test
  void persistedPlanCannotMoveToAnotherNamespaceOwnership() {
    var first =
        new PlanBinding(
            binding.tenantId(),
            binding.migrationId(),
            binding
                .target()
                .withOwnership(java.util.UUID.randomUUID().toString(), binding.tenantId()),
            binding.fromVersion(),
            binding.targetVersion(),
            binding.logicalChecksum());
    var saved = codec.encode(first, plan());
    assertThat(codec.decode(first, saved.json(), saved.checksum())).isEqualTo(plan());
    var replaced =
        new PlanBinding(
            first.tenantId(),
            first.migrationId(),
            first.target().withOwnership(java.util.UUID.randomUUID().toString(), first.tenantId()),
            first.fromVersion(),
            first.targetVersion(),
            first.logicalChecksum());
    assertThatThrownBy(() -> codec.decode(replaced, saved.json(), saved.checksum()))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> codec.decode(binding, saved.json(), saved.checksum()))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void refusesLegacyPlansWithoutCheckpointsAndWrongStatementHashes() {
    var plan = plan();
    assertThatThrownBy(
            () ->
                codec.encode(
                    binding,
                    new InitialSchemaPlan(
                        plan.dialectId(),
                        plan.database(),
                        plan.actualSnapshot(),
                        plan.targetSnapshot(),
                        plan.risk(),
                        plan.steps())))
        .isInstanceOf(DnaRuntimeException.class);
    var step = plan.steps().getFirst();
    var invalid =
        new PlannedSchemaStep(
            1,
            step.operation(),
            step.logicalObject(),
            step.satisfactionKey(),
            step.risk(),
            step.payloadJson(),
            step.statementSql(),
            "f".repeat(64));
    assertThatThrownBy(
            () ->
                codec.encode(
                    binding,
                    new InitialSchemaPlan(
                        plan.dialectId(),
                        plan.database(),
                        plan.actualSnapshot(),
                        plan.targetSnapshot(),
                        plan.risk(),
                        List.of(invalid),
                        plan.checkpointChecksums())))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private InitialSchemaPlan plan() {
    var before = snapshots.snapshot(true, List.of(), List.of(), "旧");
    var after = snapshots.snapshot(true, List.of(), List.of(), "新");
    String sql = "COMMENT ON TABLE records IS '新'";
    var step =
        new PlannedSchemaStep(
            1,
            SchemaMigrationOperation.COMMENT_TABLE,
            "table",
            "table",
            SchemaMigrationRisk.SAFE,
            snapshots.payload("table"),
            sql,
            snapshots.statementChecksum("COMMENT_TABLE", sql));
    return new InitialSchemaPlan(
        "postgresql",
        new DatabaseIdentity("PostgreSQL", "17.0", 17, 0),
        before,
        after,
        SchemaMigrationRisk.SAFE,
        List.of(step),
        List.of(before.checksum(), after.checksum()));
  }
}
