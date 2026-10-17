package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RecordWritePolicyPublicationTest {

  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));

  @Test
  void preservesTheCompletePublicationIntentAcrossSerialization() throws Exception {
    var query = new RecordCompletenessQuery("model", "app", "form", SCHEMA, List.of("title"), null);
    var command = command(0, 7, Map.of("old-page", query));
    var mapper = new ObjectMapper();
    assertThat(mapper.readValue(mapper.writeValueAsString(command), RecordWritePolicyPublicationCommand.class)).isEqualTo(command);
    var blocked = new RecordWritePolicyPublicationView(false, 0, "b".repeat(64),
        Map.of("old-page", new RecordCompletenessView(3, Map.of("title", 2L))));
    assertThat(mapper.readValue(mapper.writeValueAsString(blocked), RecordWritePolicyPublicationView.class)).isEqualTo(blocked);
  }

  @Test
  void rejectsUnboundedChecksForeignOwnersAndNonMonotonicVersions() {
    var query = new RecordCompletenessQuery("model", "app", "form", SCHEMA, List.of("title"), null);
    assertThatThrownBy(() -> command(7, 7, Map.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> command(-1, 7, Map.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> command(0, 7, Map.of("other", new RecordCompletenessQuery(
        "model", "other-app", "form", SCHEMA, List.of("title"), null)))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> command(0, 7, IntStream.range(0, 101).boxed().collect(
        Collectors.toMap(index -> "check-" + index, ignored -> query)))).isInstanceOf(IllegalArgumentException.class);
    var wide = new RecordCompletenessQuery("model", "app", "form", SCHEMA,
        IntStream.range(0, 100).mapToObj(index -> "field_" + index).toList(), null);
    assertThatThrownBy(() -> command(0, 7, IntStream.range(0, 11).boxed().collect(
        Collectors.toMap(index -> "check-" + index, ignored -> wide)))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordWritePolicyPublicationView(true, 7, "a".repeat(64),
        Map.of("missing", new RecordCompletenessView(1, Map.of("title", 1L))))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keepsLegacyWireShapeAndRoundTripsExplicitValueChecksAndReports() throws Exception {
    var mapper = new ObjectMapper();
    var legacy = command(0, 1, Map.of());
    assertThat(mapper.writeValueAsString(legacy)).doesNotContain("valueChecks");
    var query = new RecordViolationQuery("model", "app", "form", SCHEMA, null,
        new RecordValueCondition(RecordValueConditionOperator.TRUE, null, List.of(), List.of()), RecordJsonSemantics.STRUCTURAL_V1);
    var command = new RecordWritePolicyPublicationCommand("model", "app", "form", SCHEMA, 0, 1,
        "publish", "a".repeat(64), Map.of(), 15_000, Map.of("choices", query));
    assertThat(mapper.readValue(mapper.writeValueAsString(command), RecordWritePolicyPublicationCommand.class)).isEqualTo(command);
    var blocked = new RecordWritePolicyPublicationView(false, 0, "b".repeat(64), Map.of(), Map.of("choices", 2L));
    assertThat(mapper.readValue(mapper.writeValueAsString(blocked), RecordWritePolicyPublicationView.class)).isEqualTo(blocked);
    assertThatThrownBy(() -> new RecordWritePolicyPublicationView(true, 1, "b".repeat(64), Map.of(), Map.of("choices", 2L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordWritePolicyPublicationView(false, 0, "b".repeat(64), Map.of(), Map.of("choices", 0L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordWritePolicyPublicationCommand("model", "other", "form", SCHEMA, 0, 1,
        "publish", "a".repeat(64), Map.of(), 15_000, Map.of("choices", query))).isInstanceOf(IllegalArgumentException.class);
    var completeness = new RecordCompletenessQuery("model", "app", "form", SCHEMA, List.of("title"), null);
    var many = IntStream.range(0, 100).boxed().collect(Collectors.toMap(index -> "check-" + index, ignored -> completeness));
    assertThatThrownBy(() -> new RecordWritePolicyPublicationCommand("model", "app", "form", SCHEMA, 0, 1,
        "publish", "a".repeat(64), many, 15_000, Map.of("choices", query))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordWritePolicyPublicationCommand("model", "app", "form", SCHEMA, 0, 1,
        "publish", "a".repeat(64), Map.of("same", completeness), 15_000, Map.of("same", query))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresUnambiguousCancellationResolutionAndPreservesItAcrossSerialization() throws Exception {
    var mapper = new ObjectMapper();
    var cancelled = new RecordWritePolicyCancellationView(true, 7, "a".repeat(64), "b".repeat(64));
    var published = new RecordWritePolicyCancellationView(false, 7, "a".repeat(64), "a".repeat(64));
    for (var result : List.of(cancelled, published)) {
      assertThat(mapper.readValue(mapper.writeValueAsString(result), RecordWritePolicyCancellationView.class)).isEqualTo(result);
    }
    assertThatThrownBy(() -> new RecordWritePolicyCancellationView(true, 7, "a".repeat(64), "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordWritePolicyCancellationView(false, 7, "a".repeat(64), "b".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordWritePolicyCancellationView(true, 0, "a".repeat(64), "b".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void boundsTypedConditionCompilationAcrossTheWholePublication() {
    var leaf = new RecordValueCondition(RecordValueConditionOperator.TRUE, null, List.of(), List.of());
    var condition = new RecordValueCondition(RecordValueConditionOperator.OR, null, List.of(),
        java.util.Collections.nCopies(4095, leaf));
    var query = new RecordCompletenessQuery("model", "app", "form", SCHEMA, List.of("title"), null, condition);
    assertThatThrownBy(() -> command(0, 7, IntStream.range(0, 5).boxed().collect(
        Collectors.toMap(index -> "check-" + index, ignored -> query)))).isInstanceOf(IllegalArgumentException.class);
  }

  private static RecordWritePolicyPublicationCommand command(final long previous, final long next,
      final Map<String, RecordCompletenessQuery> checks) {
    return new RecordWritePolicyPublicationCommand("model", "app", "form", SCHEMA, previous, next,
        "publish", "a".repeat(64), checks, 15_000);
  }
}
