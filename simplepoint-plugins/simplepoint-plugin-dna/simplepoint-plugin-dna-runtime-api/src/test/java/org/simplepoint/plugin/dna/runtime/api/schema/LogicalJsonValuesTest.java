package org.simplepoint.plugin.dna.runtime.api.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogicalJsonValuesTest {

  @Test
  void sharesLosslessCanonicalValuesWithTheBrowser() throws Exception {
    try (var input = getClass().getResourceAsStream("/json-value-cases.json")) {
      assertThat(input).isNotNull();
      for (var item : new ObjectMapper().readTree(input)) {
        String source = item.path("source").asText();
        if (item.path("invalid").asBoolean()) {
          assertThatThrownBy(() -> LogicalJsonValues.canonicalize(source)).as(item.path("name").asText())
              .isInstanceOf(IllegalArgumentException.class);
        } else {
          String canonical = LogicalJsonValues.canonicalize(source);
          assertThat(canonical).as(item.path("name").asText()).isEqualTo(item.path("canonical").asText());
          assertThat(LogicalJsonValues.canonicalize(canonical)).isEqualTo(canonical);
          assertThat(LogicalJsonValues.empty(source)).isEqualTo(item.path("empty").asBoolean());
        }
      }
    }
  }

  @Test
  void allEntryPointsEnforceTheSameComplexityLimits() {
    String depth = "[".repeat(64) + "0" + "]".repeat(64);
    assertThat(LogicalJsonValues.canonicalize(depth)).isEqualTo(depth);
    String nodes = "[" + "0,".repeat(9998) + "0]";
    assertThat(LogicalJsonValues.numbers(nodes)).hasSize(9999);
    for (String invalid : List.of("[" + depth + "]", "[" + "0,".repeat(9999) + "0]",
        "\"" + "x".repeat(1_048_575) + "\"", "1".repeat(2001))) {
      assertThatThrownBy(() -> LogicalJsonValues.canonicalize(invalid)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> LogicalJsonValues.empty(invalid)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> LogicalJsonValues.numbers(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
