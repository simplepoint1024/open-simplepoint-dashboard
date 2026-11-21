package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;

class PhysicalSchemaTargetTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void legacyTargetsRetainTheirExactSerializedShape() throws Exception {
    String old =
        "{\"physicalModelId\":\"model\",\"metadataTarget\":{\"dataSourceId\":\"ds\","
            + "\"catalogName\":\"db\",\"schemaName\":\"public\",\"tableName\":\"records\"}}";
    var decoded = mapper.readValue(old, PhysicalSchemaTarget.class);
    assertThat(decoded.ownership()).isNull();
    assertThat(mapper.writeValueAsString(decoded)).isEqualTo(old);
    assertThat(decoded)
        .isEqualTo(
            new PhysicalSchemaTarget(
                "model", new RuntimeMetadataTarget("ds", "db", "public", "records")));
  }

  @Test
  void persistedOwnershipRoundTripsAndRejectsNonCanonicalIdentities() throws Exception {
    var target =
        new PhysicalSchemaTarget(
                "model", new RuntimeMetadataTarget("ds", "db", "public", "records"))
            .withOwnership(UUID.randomUUID().toString(), "tenant");
    assertThat(mapper.readValue(mapper.writeValueAsString(target), PhysicalSchemaTarget.class))
        .isEqualTo(target);
    assertThatThrownBy(() -> target.withOwnership("not-a-uuid", "tenant"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> target.withOwnership(UUID.randomUUID().toString(), ""))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
