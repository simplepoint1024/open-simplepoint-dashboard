package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecordPageSizeTest {
  @Test
  void acceptsPlatformTableSizesWithoutTruncatingAndRejectsUnboundedRequests() {
    for (int size : new int[] {50, 100, 200, 500}) {
      assertThat(query(size).size()).isEqualTo(size);
    }
    for (int size : new int[] {0, -1, 501, Integer.MAX_VALUE}) {
      assertThatThrownBy(() -> query(size)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  private RecordPageQuery query(int size) {
    return new RecordPageQuery("model", new RecordSchemaReference(1, "a".repeat(64)), 0, size, List.of(), null, List.of());
  }
}
