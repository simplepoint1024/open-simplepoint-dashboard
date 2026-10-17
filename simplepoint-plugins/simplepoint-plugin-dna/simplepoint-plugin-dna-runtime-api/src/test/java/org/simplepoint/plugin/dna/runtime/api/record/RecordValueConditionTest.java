package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

class RecordValueConditionTest {
  @Test
  void roundTripsTypedConditionsAndKeepsLegacyInspectionWireShape() throws Exception {
    var mapper = new ObjectMapper();
    var schema = new RecordSchemaReference(1, "a".repeat(64));
    var legacy = new RecordCompletenessQuery("model", "app", "form", schema, List.of("name"), null);
    String json = mapper.writeValueAsString(legacy);
    assertThat(json).doesNotContain("condition");
    assertThat(mapper.readValue(json, RecordCompletenessQuery.class)).isEqualTo(legacy);
    var condition = new RecordValueCondition(RecordValueConditionOperator.NOT_EQUALS, "name",
        List.of(new LogicalValue(LogicalValueKind.STRING, "日本")), List.of());
    var query = new RecordCompletenessQuery("model", "app", "form", schema, List.of("name"), null, condition);
    assertThat(mapper.readValue(mapper.writeValueAsString(query), RecordCompletenessQuery.class)).isEqualTo(query);
  }

  @Test
  void rejectsMalformedAndUnboundedConditionsBeforeCompilation() {
    var always = new RecordValueCondition(RecordValueConditionOperator.TRUE, null, List.of(), List.of());
    assertThatThrownBy(() -> new RecordValueCondition(RecordValueConditionOperator.AND, null, List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordValueCondition(RecordValueConditionOperator.NOT, null, List.of(), List.of(always, always)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordValueCondition(RecordValueConditionOperator.IS_EMPTY, "name;drop", List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordValueCondition(RecordValueConditionOperator.EQUALS, "name",
        List.of(LogicalValue.nullValue()), List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordValueCondition(RecordValueConditionOperator.EQUALS, "name",
        List.of(new LogicalValue(LogicalValueKind.STRING, "x".repeat(2001))), List.of())).isInstanceOf(IllegalArgumentException.class);
    var many = new RecordValueCondition(RecordValueConditionOperator.OR, null, List.of(), Collections.nCopies(4096, always));
    assertThatThrownBy(() -> RecordValueCondition.bounded(many)).isInstanceOf(IllegalArgumentException.class);
    var deep = always;
    for (int i = 0; i < 32; i++) {
      deep = new RecordValueCondition(RecordValueConditionOperator.NOT, null, List.of(), List.of(deep));
    }
    final var tooDeep = deep;
    assertThatThrownBy(() -> RecordValueCondition.bounded(tooDeep)).isInstanceOf(IllegalArgumentException.class);
  }
}
