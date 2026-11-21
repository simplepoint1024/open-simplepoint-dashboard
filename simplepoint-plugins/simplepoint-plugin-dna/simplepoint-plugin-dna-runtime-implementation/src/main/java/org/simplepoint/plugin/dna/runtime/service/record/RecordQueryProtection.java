package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;

/**
 * Durable stable-field query restrictions; removing a provider does not declassify stored values.
 */
final class RecordQueryProtection {
  private RecordQueryProtection() {}

  static Map<String, Set<String>> immutable(final Map<String, Set<String>> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    if (source.size() > 64) {
      throw new IllegalArgumentException("Query protection model budget exceeded");
    }
    var copy = new TreeMap<String, Set<String>>();
    int count = 0;
    for (var entry : source.entrySet()) {
      identity(entry.getKey());
      if (entry.getValue() == null || entry.getValue().isEmpty()) {
        throw new IllegalArgumentException("Query protection requires explicit field identities");
      }
      entry.getValue().forEach(RecordQueryProtection::identity);
      count += entry.getValue().size();
      if (count > 4096) {
        throw new IllegalArgumentException("Query protection field budget exceeded");
      }
      copy.put(entry.getKey(), Collections.unmodifiableSortedSet(new TreeSet<>(entry.getValue())));
    }
    return Collections.unmodifiableSortedMap(copy);
  }

  /** Uses the already compiled graph; never parses expressions or infers storage-column names. */
  static Map<String, Set<String>> derive(
      final Map<String, Set<String>> previous,
      final String parentModelId,
      final RecordCalculationDefinition definition,
      final Map<String, Set<String>> dependencies) {
    Map<String, Set<String>> result = new LinkedHashMap<>();
    immutable(previous).forEach((model, fields) -> result.put(model, new LinkedHashSet<>(fields)));
    Map<String, String> scopes = new LinkedHashMap<>();
    scopes.put("parent", parentModelId);
    definition.details().forEach((scope, detail) -> scopes.put(scope, detail.physicalModelId()));
    for (var rule : definition.referencesOrEmpty()) {
      for (var mapping : rule.mappings()) {
        add(result, scopes, rule.source().scope() + "." + mapping.destinationFieldId());
      }
    }
    // Iterate at most once per output addition; also handles graphs containing direct edges.
    boolean changed;
    do {
      changed = false;
      for (var entry : dependencies.entrySet()) {
        if (entry.getValue().stream().anyMatch(source -> contains(result, scopes, source))) {
          changed |= add(result, scopes, entry.getKey());
        }
      }
    } while (changed);
    return immutable(result);
  }

  private static boolean add(
      final Map<String, Set<String>> fields, final Map<String, String> scopes, final String path) {
    var identity = resolve(scopes, path);
    return fields.computeIfAbsent(identity[0], ignored -> new LinkedHashSet<>()).add(identity[1]);
  }

  private static boolean contains(
      final Map<String, Set<String>> fields, final Map<String, String> scopes, final String path) {
    var identity = resolve(scopes, path);
    return fields.getOrDefault(identity[0], Set.of()).contains(identity[1]);
  }

  private static String[] resolve(final Map<String, String> scopes, final String path) {
    int separator = path.indexOf('.');
    String model = separator < 1 ? null : scopes.get(path.substring(0, separator));
    if (model == null) {
      throw new IllegalArgumentException("Query protection dependency has an unknown scope");
    }
    String field = path.substring(separator + 1);
    identity(field);
    return new String[] {model, field};
  }

  private static void identity(final String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
      throw new IllegalArgumentException("Invalid query protection identity");
    }
  }
}
