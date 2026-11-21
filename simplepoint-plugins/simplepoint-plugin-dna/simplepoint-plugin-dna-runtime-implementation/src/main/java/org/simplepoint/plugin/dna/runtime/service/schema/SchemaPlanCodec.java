package org.simplepoint.plugin.dna.runtime.service.schema;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalTableSnapshot;
import org.springframework.stereotype.Component;

/** Versioned internal recovery material, bound to an immutable migration and exact storage target. */
@Component
public final class SchemaPlanCodec {
  private static final int MAX_JSON_LENGTH = 8 * 1024 * 1024;
  private final ObjectMapper mapper;
  private final PhysicalSchemaSnapshotCodec snapshots;

  public SchemaPlanCodec(ObjectMapper mapper, PhysicalSchemaSnapshotCodec snapshots) {
    this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    this.snapshots = snapshots;
  }

  public EncodedPlan encode(PlanBinding binding, InitialSchemaPlan plan) {
    validate(plan);
    try {
      String json = mapper.writeValueAsString(new Envelope(1, binding, plan));
      if (json.length() > MAX_JSON_LENGTH) throw invalid();
      return new EncodedPlan(json, checksum(json));
    } catch (JsonProcessingException failure) {
      throw invalid();
    }
  }

  public InitialSchemaPlan decode(PlanBinding binding, String json, String checksum) {
    if (json == null || json.length() > MAX_JSON_LENGTH || !checksum(json).equals(checksum)) throw invalid();
    try {
      Envelope envelope = mapper.readValue(json, Envelope.class);
      if (envelope == null || envelope.version() != 1 || !binding.equals(envelope.binding())) throw invalid();
      validate(envelope.plan());
      return envelope.plan();
    } catch (JsonProcessingException | IllegalArgumentException failure) {
      // Do not expose persisted SQL, submitted values or parser fragments in a public error.
      throw invalid();
    }
  }

  private void validate(InitialSchemaPlan plan) {
    if (plan == null || plan.checkpointChecksums().size() != plan.steps().size() + 1) throw invalid();
    validateSnapshot(plan.actualSnapshot());
    validateSnapshot(plan.targetSnapshot());
    for (int index = 0; index < plan.steps().size(); index++) {
      var step = plan.steps().get(index);
      if (step.sequence() != index + 1 || !snapshots.statementChecksum(step.operation().name(), step.statementSql())
          .equals(step.statementChecksum())) throw invalid();
    }
    var risk = plan.steps().stream().map(PlannedSchemaStep::risk).max(java.util.Comparator.naturalOrder())
        .orElse(org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk.SAFE);
    if (plan.risk() != risk) throw invalid();
  }

  private void validateSnapshot(PhysicalTableSnapshot snapshot) {
    if (!snapshot.checksum().equals(snapshots.snapshot(snapshot.exists(), snapshot.columns(), snapshot.indexes(),
        snapshot.tableComment(), snapshot.foreignKeys()).checksum())) throw invalid();
  }

  private static String checksum(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static DnaRuntimeException invalid() {
    return new DnaRuntimeException(DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED,
        "Persisted publication plan is incomplete, changed or belongs to another migration");
  }

  public record PlanBinding(String tenantId, String migrationId, PhysicalSchemaTarget target,
      Integer fromVersion, int targetVersion, String logicalChecksum) {
    public PlanBinding {
      if (tenantId == null || tenantId.isBlank() || migrationId == null || migrationId.isBlank()
          || target == null || targetVersion < 1 || fromVersion != null && (fromVersion < 1 || fromVersion >= targetVersion)
          || logicalChecksum == null || !logicalChecksum.matches("[0-9a-f]{64}")) {
        throw invalid();
      }
    }
  }

  public record EncodedPlan(String json, String checksum) {
  }

  private record Envelope(int version, PlanBinding binding, InitialSchemaPlan plan) {
  }
}
