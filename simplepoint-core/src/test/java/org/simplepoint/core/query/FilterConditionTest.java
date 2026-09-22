package org.simplepoint.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class FilterConditionTest {
  @Test
  void parsesFullPrefixesWithoutSplittingValues() {
    for (String op : FilterCondition.OPERATORS) {
      String value = op.startsWith("is:") ? "" : "2026-09-18T10:20:30Z";
      assertThat(FilterCondition.parse(op + (value.isEmpty() ? "" : ":" + value)))
          .isEqualTo(new FilterCondition(op, value));
    }
    assertThat(FilterCondition.parse("equals:https://host:8000/path"))
        .isEqualTo(new FilterCondition("equals", "https://host:8000/path"));
    assertThat(FilterCondition.parse("name")).isEqualTo(new FilterCondition("equals", "name"));
    assertThat(FilterCondition.parse("is:null:null")).isEqualTo(new FilterCondition("is:null", ""));
    assertThat(FilterCondition.parse("is:not:null:old-value"))
        .isEqualTo(new FilterCondition("is:not:null", ""));
    assertThat(FilterCondition.parse("custom:long:a:b", List.of("custom", "custom:long")))
        .isEqualTo(new FilterCondition("custom:long", "a:b"));
  }

  @Test
  void invalidInputsNeverBecomeEmptyPredicates() {
    for (String value : List.of("", " ", "equals:", "unknown:x", "x".repeat(8193))) {
      assertThatThrownBy(() -> FilterCondition.parse(value))
          .isInstanceOf(InvalidQueryException.class);
    }
    assertThatThrownBy(() -> FilterCondition.parse(null)).isInstanceOf(InvalidQueryException.class);
  }
}
