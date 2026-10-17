package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordCreateCommandTest {

  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(
      1,
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  );

  @Test
  void requiresBoundedPortableIdempotencyKey() {
    assertThatThrownBy(() -> new RecordCreateCommand(
        "model-a",
        SCHEMA,
        null,
        Map.of()
    )).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordCreateCommand(
        "model-a",
        SCHEMA,
        "contains whitespace",
        Map.of()
    )).isInstanceOf(IllegalArgumentException.class);
  }
}
