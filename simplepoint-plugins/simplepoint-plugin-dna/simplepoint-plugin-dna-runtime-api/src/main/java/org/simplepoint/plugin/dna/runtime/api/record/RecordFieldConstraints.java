package org.simplepoint.plugin.dna.runtime.api.record;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/** Neutral business constraints keyed by stable field identity; no UI text or executable code. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordFieldConstraints(Integer minLength, Integer maxLength, String pattern, List<LogicalValue> choices,
    RecordJsonSelection selection) {
  /** Existing immutable constraints preserve the absence of a JSON selection domain. */
  public RecordFieldConstraints(final Integer minLength, final Integer maxLength, final String pattern, final List<LogicalValue> choices) {
    this(minLength, maxLength, pattern, choices, null);
  }

  /** Bounds wire data; model-aware type and RE2 program checks run before installation/execution. */
  public RecordFieldConstraints {
    choices = choices == null ? List.of() : List.copyOf(choices);
    if (minLength != null && (minLength < 0 || minLength > 1_048_576)
        || maxLength != null && (maxLength < 1 || maxLength > 1_048_576)
        || minLength != null && maxLength != null && minLength > maxLength
        || pattern != null && (pattern.isBlank() || pattern.length() > 512)) {
      throw new IllegalArgumentException("Invalid bounded text constraints");
    }
    if (choices.size() > 200 || choices.stream().anyMatch(value -> value.kind() == LogicalValueKind.NULL
        || value.kind() == LogicalValueKind.JSON || value.kind() == LogicalValueKind.BINARY
        || value.canonicalValue().length() > 2000)) {
      throw new IllegalArgumentException("Invalid bounded scalar choices");
    }
    if (selection != null && (minLength != null || maxLength != null || pattern != null || !choices.isEmpty())) {
      throw new IllegalArgumentException("Selection domains cannot also declare scalar or text constraints");
    }
  }

  long textBudget() {
    return (pattern == null ? 0 : pattern.length()) + choices.stream().mapToLong(value -> value.canonicalValue().length()).sum()
        + (selection == null ? 0 : selection.textBudget());
  }
}
