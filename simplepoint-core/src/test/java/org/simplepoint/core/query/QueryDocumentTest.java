package org.simplepoint.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QueryDocumentTest {
  @Test
  void preservesArraysAndTypedValuesAndRejectsUnknownGrammar() {
    var document =
        QueryDocument.parse(
            """
            {"version":1,"where":{"field":"title","op":"in","value":["a,b","x:y"]}}
            """);
    assertThat(document.where().value().get(0).textValue()).isEqualTo("a,b");
    for (String invalid :
        java.util.List.of(
            "{\"version\":2,\"where\":{}}",
            "{\"version\":4294967297,\"where\":{}}",
            "{\"version\":1,\"version\":1,\"where\":{}}",
            "{} {}",
            "{\"version\":1,\"where\":{\"op\":\"or\",\"children\":[]}}",
            "{\"version\":1,\"where\":{\"field\":\"a.b\",\"op\":\"equals\",\"value\":1}}",
            "{\"version\":1,\"where\":{\"field\":\"a\",\"op\":\"in\",\"value\":\"a,b\"}}",
            "{\"version\":1,\"where\":{\"field\":\"a\",\"op\":\"is:null\",\"sql\":\"1=1\"}}")) {
      assertThatThrownBy(() -> QueryDocument.parse(invalid))
          .isInstanceOf(InvalidQueryException.class);
    }
  }

  @Test
  void rejectsDepthNodeAndWireSizeAbuse() {
    String leaf = "{\"field\":\"a\",\"op\":\"equals\",\"value\":1}";
    String deep = leaf;
    for (int i = 0; i < 6; i++) deep = "{\"op\":\"not\",\"children\":[" + deep + "]}";
    String json = "{\"version\":1,\"where\":" + deep + "}";
    assertThatThrownBy(() -> QueryDocument.parse(json)).isInstanceOf(InvalidQueryException.class);
    String many =
        "{\"version\":1,\"where\":{\"op\":\"or\",\"children\":["
            + (leaf + ",").repeat(32)
            + leaf
            + "]}}";
    assertThatThrownBy(() -> QueryDocument.parse(many)).isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(() -> QueryDocument.parse(" ".repeat(8193)))
        .isInstanceOf(InvalidQueryException.class);
  }
}
