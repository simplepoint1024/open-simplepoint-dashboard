package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Trusted domain lifecycle, independent of form enums, storage names and caller instructions. */
public record RecordStateTransitions(String fieldId, List<String> initialStates, Map<String, List<String>> transitions) {
  /** Canonicalizes unordered state sets and rejects unknown targets or unbounded graphs. */
  public RecordStateTransitions {
    RecordCalculationDefinition.identity(fieldId);
    initialStates = states(initialStates);
    Map<String, List<String>> copy = new TreeMap<>();
    Objects.requireNonNull(transitions, "transitions").forEach((from, targets) -> {
      state(from);
      copy.put(from, states(targets));
    });
    transitions = Collections.unmodifiableMap(copy);
    if (initialStates.isEmpty() || transitions.isEmpty() || transitions.size() > 32
        || !transitions.keySet().containsAll(initialStates)
        || transitions.values().stream().anyMatch(targets -> !copy.keySet().containsAll(targets))) {
      throw new IllegalArgumentException("Invalid bounded lifecycle graph");
    }
  }

  long textBudget() {
    return fieldId.length() + initialStates.stream().mapToLong(String::length).sum()
        + transitions.keySet().stream().mapToLong(String::length).sum()
        + transitions.values().stream().flatMap(List::stream).mapToLong(String::length).sum();
  }

  private static List<String> states(final List<String> source) {
    Objects.requireNonNull(source, "states");
    if (source.size() > 32 || new HashSet<>(source).size() != source.size()) {
      throw new IllegalArgumentException("Invalid lifecycle state set");
    }
    source.forEach(RecordStateTransitions::state);
    return source.stream().sorted().toList();
  }

  private static void state(final String value) {
    if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")) {
      throw new IllegalArgumentException("Invalid lifecycle state identity");
    }
  }
}
