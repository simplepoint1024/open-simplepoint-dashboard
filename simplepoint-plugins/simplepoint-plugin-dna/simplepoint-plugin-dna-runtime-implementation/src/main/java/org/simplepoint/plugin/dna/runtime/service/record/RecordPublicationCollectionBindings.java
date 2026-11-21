package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;

/** Collection storage is frozen independently of formula backfill's owned record scope. */
final class RecordPublicationCollectionBindings {
  private RecordPublicationCollectionBindings() {}

  static Map<String, RecordPublicationIntent.Binding> validate(
      int version,
      RecordWritePolicyPublicationCommand command,
      Map<String, RecordPublicationIntent.Binding> owned,
      Map<String, RecordPublicationIntent.Binding> collections) {
    List<RecordCollectionRule> rules =
        command.calculation() == null ? List.of() : command.calculation().collectionsOrEmpty();
    if (version < 3) {
      if (collections != null || !rules.isEmpty()) {
        throw new IllegalArgumentException(
            "Collection declarations require version three bindings");
      }
      return null;
    }
    if (version == 4 && rules.isEmpty() && collections == null) {
      return null;
    }
    if (rules.isEmpty()) {
      throw new IllegalArgumentException("Version three requires active collection declarations");
    }
    return validateBindings(command, owned, null, collections, rules);
  }

  static Map<String, RecordPublicationIntent.Binding> validateRetirement(
      int version,
      RecordWritePolicyPublicationCommand command,
      Map<String, RecordPublicationIntent.Binding> owned,
      Map<String, RecordPublicationIntent.Binding> active,
      Map<String, RecordPublicationIntent.Binding> retired) {
    var rules = command.retiringCollections();
    if (version < 4) {
      if (retired != null || !rules.isEmpty()) {
        throw new IllegalArgumentException("Collection retirement requires version four bindings");
      }
      return null;
    }
    if (version == 5 && rules.isEmpty() && retired == null) {
      return null;
    }
    if (rules.isEmpty()) {
      throw new IllegalArgumentException("Version four requires an exact retirement plan");
    }
    return validateBindings(command, owned, active, retired, rules);
  }

  private static Map<String, RecordPublicationIntent.Binding> validateBindings(
      RecordWritePolicyPublicationCommand command,
      Map<String, RecordPublicationIntent.Binding> owned,
      Map<String, RecordPublicationIntent.Binding> occupied,
      Map<String, RecordPublicationIntent.Binding> collections,
      List<RecordCollectionRule> rules) {
    if (collections == null
        || !collections
            .keySet()
            .equals(
                rules.stream()
                    .map(RecordCollectionRule::relationId)
                    .collect(java.util.stream.Collectors.toSet()))) {
      throw new IllegalArgumentException(
          "Collection bindings must match their declarations exactly");
    }
    var root = owned.get(command.physicalModelId()).target();
    var targets =
        new HashSet<>(
            owned.values().stream().map(RecordPublicationIntent.Binding::target).toList());
    if (occupied != null) {
      occupied.values().forEach(binding -> targets.add(binding.target()));
    }
    for (var rule : rules) {
      var binding =
          Objects.requireNonNull(collections.get(rule.relationId()), "collection binding");
      var location = binding.target();
      if (owned.containsKey(rule.edgeModelId())
          || owned.containsKey(rule.targetModelId())
              && !rule.targetModelId().equals(command.physicalModelId())
          || !rule.edgeSchema().equals(binding.requestedSchema())
          || binding.resourceGeneration() != 0
          || !targets.add(location)
          || !root.dataSourceId().equals(location.dataSourceId())
          || !Objects.equals(root.catalogName(), location.catalogName())
          || !Objects.equals(root.schemaName(), location.schemaName())) {
        throw new IllegalArgumentException(
            "Collection storage binding differs from its declaration");
      }
    }
    return Map.copyOf(collections);
  }
}
