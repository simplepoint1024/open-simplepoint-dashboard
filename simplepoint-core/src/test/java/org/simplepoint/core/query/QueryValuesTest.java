package org.simplepoint.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueryValuesTest {
  enum State {
    READY,
    DONE
  }

  @Test
  void bindsActualJavaTypes() {
    assertThat(QueryValues.convert(int.class, "10")).isEqualTo(10);
    assertThat(QueryValues.convert(Long.class, "2147483648")).isEqualTo(2147483648L);
    assertThat(QueryValues.convert(BigDecimal.class, "10.25")).isEqualTo(new BigDecimal("10.25"));
    assertThat(QueryValues.convert(Boolean.class, "false")).isEqualTo(false);
    assertThat(QueryValues.convert(State.class, "READY")).isEqualTo(State.READY);
    UUID id = UUID.randomUUID();
    assertThat(QueryValues.convert(UUID.class, id.toString())).isEqualTo(id);
    assertThat(QueryValues.convert(Instant.class, "2026-09-18"))
        .isEqualTo(Instant.parse("2026-09-18T00:00:00Z"));
    assertThat(QueryValues.convert(Instant.class, "2026-09-18T10:20:30+08:00"))
        .isEqualTo(Instant.parse("2026-09-18T02:20:30Z"));
  }

  @Test
  void rejectsOverflowInvalidDatesAndNonFiniteValuesWithoutLeakingInput() {
    for (var pair :
        List.of(
            new Object[] {int.class, "2147483648"},
            new Object[] {int.class, "1.2"},
            new Object[] {Boolean.class, "yes"},
            new Object[] {Double.class, "NaN"},
            new Object[] {Float.class, "Infinity"},
            new Object[] {Instant.class, "2026-02-30"},
            new Object[] {State.class, "MISSING"},
            new Object[] {UUID.class, "1-1-1-1-1"})) {
      assertThatThrownBy(() -> QueryValues.convert((Class<?>) pair[0], (String) pair[1]))
          .isInstanceOf(InvalidQueryException.class)
          .hasMessage("筛选值与字段类型不匹配");
    }
  }
}
