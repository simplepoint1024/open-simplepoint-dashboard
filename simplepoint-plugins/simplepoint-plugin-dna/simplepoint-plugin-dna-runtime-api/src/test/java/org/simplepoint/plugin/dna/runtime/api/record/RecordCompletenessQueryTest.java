package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RecordCompletenessQueryTest {

  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));

  @Test
  void rejectsUnboundedAmbiguousAndExecutableFieldRequests() {
    for (List<String> fields : List.of(List.<String>of(), List.of("title", "title"), List.of("title OR 1=1"),
        IntStream.range(0, 101).mapToObj(index -> "field_" + index).toList())) {
      assertThatThrownBy(() -> query("app", "form", fields)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> query(" ", "form", List.of("title"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> query("app", "x".repeat(65), List.of("title")))
        .isInstanceOf(IllegalArgumentException.class);
    RecordFilter leaf = new RecordFilter(RecordFilterOperator.IS_NULL, "title", List.of(), List.of());
    RecordFilter oversized = new RecordFilter(RecordFilterOperator.AND, null, List.of(),
        IntStream.range(0, 33).mapToObj(index -> leaf).toList());
    assertThatThrownBy(() -> new RecordCompletenessQuery("model", "app", "form", SCHEMA,
        List.of("title"), oversized)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keepsImmutablePortableWireContracts() throws Exception {
    List<String> fields = new ArrayList<>(List.of("title"));
    RecordCompletenessQuery query = query("app", "form", fields);
    fields.add("later");
    assertThat(query.fields()).containsExactly("title");
    ObjectMapper mapper = new ObjectMapper();
    assertThat(mapper.readValue(mapper.writeValueAsString(query), RecordCompletenessQuery.class)).isEqualTo(query);
    var result = new RecordCompletenessView(3, Map.of("title", 2L));
    assertThat(mapper.readValue(mapper.writeValueAsString(result), RecordCompletenessView.class)).isEqualTo(result);
    assertThatThrownBy(() -> result.emptyCounts().put("title", 0L)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void explicitJsonSemanticsDoNotRewritePersistedLegacyCommands() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var legacy = query("app", "form", List.of("payload"));
    assertThat(mapper.readTree(mapper.writeValueAsString(legacy)).has("jsonSemantics")).isFalse();
    var structural = new RecordCompletenessQuery("model", "app", "form", SCHEMA,
        List.of("payload"), null, null, RecordJsonSemantics.STRUCTURAL_V1);
    assertThat(mapper.readValue(mapper.writeValueAsString(legacy), RecordCompletenessQuery.class).jsonSemantics()).isNull();
    assertThat(mapper.readValue(mapper.writeValueAsString(structural), RecordCompletenessQuery.class)).isEqualTo(structural);
    assertThat(mapper.readTree(mapper.writeValueAsString(structural)).path("jsonSemantics").asText()).isEqualTo("STRUCTURAL_V1");
  }

  @Test
  void invalidAggregatesCannotBeInterpretedAsSafe() {
    assertThatThrownBy(() -> new RecordCompletenessView(-1, Map.of("title", 0L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordCompletenessView(1, Map.of("title", 2L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordCompletenessView(1, Map.of("title", -1L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordCompletenessView(1, Map.of())).isInstanceOf(IllegalArgumentException.class);
  }

  private static RecordCompletenessQuery query(final String app, final String form, final List<String> fields) {
    return new RecordCompletenessQuery("model", app, form, SCHEMA, fields, null);
  }
}
