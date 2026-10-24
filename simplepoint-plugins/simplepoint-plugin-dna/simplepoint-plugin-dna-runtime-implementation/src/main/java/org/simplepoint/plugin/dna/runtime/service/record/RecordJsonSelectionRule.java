package org.simplepoint.plugin.dna.runtime.service.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.calculation.FormulaException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordJsonSelection;

/** Model-bound selection checks for ordinary inputs and calculated JSON values. */
final class RecordJsonSelectionRule {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final RecordJsonSelection.Mode mode;
  private final Set<List<String>> paths;
  private final Set<String> keys;

  RecordJsonSelectionRule(final RecordJsonSelection domain) {
    mode = domain.mode();
    paths = Set.copyOf(domain.paths());
    keys = mode == RecordJsonSelection.Mode.SET
        ? paths.stream().map(List::getFirst).collect(Collectors.toUnmodifiableSet()) : Set.of();
  }

  void validate(final Object value) {
    if (!(value instanceof String text)) {
      throw invalid();
    }
    try {
      var parsed = JSON.readTree(text);
      if (parsed == null || !parsed.isArray() || parsed.size() > 200) {
        throw invalid();
      }
      List<String> selected = new ArrayList<>();
      for (var item : parsed) {
        if (!item.isTextual()) {
          throw invalid();
        }
        selected.add(item.textValue());
      }
      if (!selected.isEmpty() && (mode == RecordJsonSelection.Mode.PATH ? !paths.contains(selected)
          : new HashSet<>(selected).size() != selected.size() || !keys.containsAll(selected))) {
        throw invalid();
      }
    } catch (JsonProcessingException failure) {
      throw invalid();
    }
  }

  private static FormulaException invalid() {
    return new FormulaException("RESULT_CHOICE");
  }
}
