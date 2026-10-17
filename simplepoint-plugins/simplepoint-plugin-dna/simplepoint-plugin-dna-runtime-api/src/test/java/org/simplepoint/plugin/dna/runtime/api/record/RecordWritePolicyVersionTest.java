package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordWritePolicyVersionTest {

  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));

  @Test
  void oldWireRequestsExplicitlySelectTheInitialVersionAndNewVersionsRoundTrip() throws Exception {
    ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    var create = new RecordCreateCommand("model", SCHEMA, "request", Map.of("title", "Value"), 7);
    assertThat(mapper.readValue(mapper.writeValueAsString(create), RecordCreateCommand.class)).isEqualTo(create);
    ObjectNode old = mapper.valueToTree(create);
    old.remove("writePolicyVersion");
    assertThat(mapper.treeToValue(old, RecordCreateCommand.class).writePolicyVersion()).isZero();
    var update = new RecordUpdateCommand("model", SCHEMA, "record", 0, Map.of("title", "Value"), "request", 7);
    assertThat(mapper.readValue(mapper.writeValueAsString(update), RecordUpdateCommand.class)).isEqualTo(update);
    old = mapper.valueToTree(update);
    old.remove("writePolicyVersion");
    assertThat(mapper.treeToValue(old, RecordUpdateCommand.class).writePolicyVersion()).isZero();
    var delete = new RecordDeleteCommand("model", SCHEMA, "record", 0, "request", 7);
    assertThat(mapper.readValue(mapper.writeValueAsString(delete), RecordDeleteCommand.class)).isEqualTo(delete);
    old = mapper.valueToTree(delete);
    old.remove("writePolicyVersion");
    assertThat(mapper.treeToValue(old, RecordDeleteCommand.class).writePolicyVersion()).isZero();
    var group = new RecordTransactionCommand("model", SCHEMA,
        List.of(new RecordCreateMutation("create", Map.of("title", "Value"))), 1000, "request", 7);
    assertThat(mapper.readValue(mapper.writeValueAsString(group), RecordTransactionCommand.class)).isEqualTo(group);
    old = mapper.valueToTree(group);
    old.remove("writePolicyVersion");
    assertThat(mapper.treeToValue(old, RecordTransactionCommand.class).writePolicyVersion()).isZero();
  }

  @Test
  void rejectsNegativeAdmissionVersionsForEveryWriteContract() {
    assertThatThrownBy(() -> new RecordCreateCommand("model", SCHEMA, "request", Map.of(), -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordUpdateCommand("model", SCHEMA, "record", 0, Map.of("title", "Value"), "request", -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordDeleteCommand("model", SCHEMA, "record", 0, "request", -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordTransactionCommand("model", SCHEMA,
        List.of(new RecordDeleteMutation("delete", "record", 0)), 1000, "request", -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
