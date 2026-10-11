package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.HashSet;
import java.util.List;

/** Shared validation budgets for public record queries. */
final class RecordQueryLimits {

  static final int MAX_PAGE_SIZE = 100;
  static final int MAX_FILTER_NODES = 32;
  static final int MAX_FILTER_DEPTH = 4;

  private RecordQueryLimits() {
  }

  static List<String> fields(final List<String> requested) {
    List<String> fields = requested == null ? List.of() : List.copyOf(requested);
    if (fields.size() > 100 || new HashSet<>(fields).size() != fields.size()
        || fields.stream().anyMatch(value ->
        value == null || !value.matches("[a-z][a-z0-9_]{0,62}"))) {
      throw new IllegalArgumentException("Record projection is invalid");
    }
    return fields;
  }

  static List<RecordSort> sorts(final List<RecordSort> requested) {
    List<RecordSort> sorts = requested == null ? List.of() : List.copyOf(requested);
    if (sorts.size() > 3
        || new HashSet<>(sorts.stream().map(RecordSort::field).toList()).size() != sorts.size()) {
      throw new IllegalArgumentException("Record sort is invalid");
    }
    return sorts;
  }

  static RecordFilter filter(final RecordFilter filter) {
    if (filter != null) {
      int[] nodes = {0};
      visit(filter, 0, nodes);
    }
    return filter;
  }

  static String modelId(final String value) {
    if (value == null || value.isBlank() || value.length() > 64) {
      throw new IllegalArgumentException("physicalModelId is required");
    }
    return value.trim();
  }

  static String recordId(final String value) {
    if (value == null || value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException("recordId is required");
    }
    return value.trim();
  }

  private static void visit(final RecordFilter node, final int depth, final int[] count) {
    if (depth > MAX_FILTER_DEPTH || ++count[0] > MAX_FILTER_NODES) {
      throw new IllegalArgumentException("Record filter exceeds the complexity budget");
    }
    node.children().forEach(child -> visit(child, depth + 1, count));
  }
}
