package org.simplepoint.installation.host;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalServiceRetirementTest {
  @Test
  void acceptsOnlyStoppedMaskedServicesWithNoRemainingProcessesOnTheReviewedMachine() {
    var review = new LocalServiceRetirement.Review(1, "a".repeat(32), List.of("dna.service"));
    var state = new HashMap<>(stopped("dna.service"));
    var probe = probe(state, "a".repeat(32), false);
    assertThatCode(() -> new LocalServiceRetirement(review, probe).run())
        .doesNotThrowAnyException();
    for (var field :
        Map.of(
                "Id",
                "other.service",
                "LoadState",
                "not-found",
                "UnitFileState",
                "enabled",
                "ActiveState",
                "active",
                "MainPID",
                "100")
            .entrySet()) {
      var changed = new HashMap<>(state);
      changed.put(field.getKey(), field.getValue());
      assertThatThrownBy(
              () -> new LocalServiceRetirement(review, probe(changed, "a".repeat(32), false)).run())
          .hasMessageContaining("not established");
    }
    assertThatThrownBy(
            () -> new LocalServiceRetirement(review, probe(state, "b".repeat(32), false)).run())
        .hasMessageContaining("not established");
    state.put("ControlGroup", "/system.slice/dna.service");
    assertThatThrownBy(
            () -> new LocalServiceRetirement(review, probe(state, "a".repeat(32), true)).run())
        .hasMessageContaining("not established");
  }

  @Test
  void rejectsEmptyOrAmbiguousRetirementInventoriesAndCommandOptions() {
    for (var units :
        List.of(
            List.<String>of(),
            List.of("dna.service", "dna.service"),
            List.of("--host=remote"),
            List.of("../dna.service"))) {
      assertThatThrownBy(() -> new LocalServiceRetirement.Review(1, "a".repeat(32), units))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  static Map<String, String> stopped(String unit) {
    return Map.of(
        "Id",
        unit,
        "LoadState",
        "masked",
        "UnitFileState",
        "masked-runtime",
        "ActiveState",
        "inactive",
        "MainPID",
        "0",
        "ControlGroup",
        "");
  }

  static LocalServiceRetirement.Probe probe(
      Map<String, String> state, String machine, boolean populated) {
    return new LocalServiceRetirement.Probe() {
      @Override
      public String machineId() {
        return machine;
      }

      @Override
      public Map<String, String> state(String unit) {
        return state;
      }

      @Override
      public boolean populated(String cgroup) {
        return populated;
      }
    };
  }
}
