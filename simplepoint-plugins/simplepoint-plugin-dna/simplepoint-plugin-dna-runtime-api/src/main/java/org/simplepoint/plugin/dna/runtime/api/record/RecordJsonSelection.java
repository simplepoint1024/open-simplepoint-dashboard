package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Bounded JSON string-array domain; UI labels and component implementation names are absent. */
public record RecordJsonSelection(Mode mode, List<List<String>> paths) {
  /** A unique subset of single keys, or one complete hierarchical leaf path. */
  public enum Mode { SET, PATH }

  /** Rejects duplicate, ambiguous and unbounded domains before model installation. */
  public RecordJsonSelection {
    Objects.requireNonNull(mode, "mode");
    paths = Objects.requireNonNull(paths, "paths").stream().map(List::copyOf).toList();
    if (paths.size() > 200 || new HashSet<>(paths).size() != paths.size()
        || paths.stream().anyMatch(path -> path.isEmpty() || path.size() > 6 || mode == Mode.SET && path.size() != 1
            || path.stream().anyMatch(key -> key.isBlank() || key.length() > 256))) {
      throw new IllegalArgumentException("Invalid bounded selection domain");
    }
    for (var left : paths) {
      if (paths.stream().anyMatch(right -> right.size() > left.size() && right.subList(0, left.size()).equals(left))) {
        throw new IllegalArgumentException("Selection leaf paths cannot be prefixes of other paths");
      }
    }
    if (paths.stream().flatMap(List::stream).mapToLong(String::length).sum() > 65_536) {
      throw new IllegalArgumentException("Selection domain text budget exceeded");
    }
  }

  long textBudget() {
    return paths.stream().flatMap(List::stream).mapToLong(String::length).sum();
  }
}
