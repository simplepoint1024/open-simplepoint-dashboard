package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordReferenceReadRequirementTest {
  private static final String RECORD = "11111111-1111-1111-1111-111111111111";
  private static final String CHILD = "22222222-2222-2222-2222-222222222222";

  @Test
  void frozenDependenciesRetainRootAndOwnedScopeWithoutValuesOrGrants() throws Exception {
    var fields = new ArrayList<>(List.of("price", "amount"));
    var owned = requirement("lines", CHILD, fields);
    fields.clear();
    assertThat(owned.fieldIds()).containsExactly("amount", "price");
    assertThat(requirement("lines", null, List.of("amount")).rowIdentity())
        .isNotEqualTo(owned.rowIdentity());
    assertThat(requirement(null, null, List.of("amount")).rowIdentity())
        .isNotEqualTo(owned.rowIdentity());
    var json = new ObjectMapper();
    assertThat(json.readValue(json.writeValueAsString(owned), RecordReferenceReadRequirement.class))
        .isEqualTo(owned);
    assertThatThrownBy(() -> owned.fieldIds().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void invalidOwnedCoordinatesDuplicatesAndFieldBudgetsAreRejected() {
    assertThatThrownBy(() -> requirement(null, CHILD, List.of("amount")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> requirement("parent", CHILD, List.of("amount")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> requirement("lines", "1-1-1-1-1", List.of("amount")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> requirement("lines", CHILD, List.of("amount", "amount")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> requirement(null, null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                requirement(
                    null,
                    null,
                    java.util.stream.IntStream.range(0, 129).mapToObj(i -> "f" + i).toList()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void copiedOriginsRequireDirectSourceAndCanonicalUniqueFrozenDependencies() throws Exception {
    var root = requirement(null, null, List.of("amount"));
    var detail = requirement("lines", CHILD, List.of("price"));
    var first = origin(List.of(root, detail));
    var second = origin(List.of(detail, root));
    assertThat(first).isEqualTo(second);
    var json = new ObjectMapper();
    assertThat(json.writeValueAsString(first)).isEqualTo(json.writeValueAsString(second));
    assertThat(json.readValue(json.writeValueAsString(first), RecordReferenceCopyOrigin.class))
        .isEqualTo(first);
    assertThatThrownBy(() -> origin(List.of(detail))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> origin(List.of(root, root)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> origin(List.of(requirement(null, null, List.of("other")))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> origin(List.of())).isInstanceOf(IllegalArgumentException.class);
  }

  private static RecordReferenceReadRequirement requirement(
      String model, String row, List<String> fields) {
    return new RecordReferenceReadRequirement(
        "parent", "app", "form", "resource", RECORD, model, row, fields);
  }

  private static RecordReferenceCopyOrigin origin(
      List<RecordReferenceReadRequirement> requirements) {
    return new RecordReferenceCopyOrigin(
        "relation",
        "mapping",
        RecordReferenceMapping.Mode.SERVER_SNAPSHOT,
        "destination",
        "a".repeat(64),
        "parent",
        "app",
        "form",
        "resource",
        RECORD,
        3,
        "amount",
        requirements);
  }
}
