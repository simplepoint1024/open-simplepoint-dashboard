package org.simplepoint.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FTest {

  @Test
  void processing_appliesFunctionAndReturnsValue() {
    StringBuilder sb = new StringBuilder();
    String result = F.processing("hello", v -> sb.append(v).append("!"));
    assertThat(result).isEqualTo("hello");
    assertThat(sb.toString()).isEqualTo("hello!");
  }

  @Test
  void canInstantiate() {
    assertThat(new F()).isNotNull();
  }

  @Test
  void processing_withMutableObject_mutatesAndReturns() {
    java.util.List<String> list = new java.util.ArrayList<>();
    java.util.List<String> result = F.processing(list, l -> {
      l.add("a");
      l.add("b");
    });
    assertThat(result).isSameAs(list);
    assertThat(result).containsExactly("a", "b");
  }

  @Test
  void processing_withNull_handlesGracefully() {
    String result = F.processing(null, v -> { /* no-op */ });
    assertThat(result).isNull();
  }
}
