package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Intersects trusted transitive dependencies with current grants, without evaluating values. */
@Component
public class RecordCalculationAccess {
  private final RecordCalculationWriteSupport calculations;
  private final RuntimeRecordAccessPolicy permissions;

  /** Uses the same trusted graph as writes and the platform's current field authorization. */
  public RecordCalculationAccess(final RecordCalculationWriteSupport calculations,
      final RuntimeRecordAccessPolicy permissions) {
    this.calculations = calculations;
    this.permissions = permissions;
  }

  Rules resolve(final RuntimeRecordModel requested, final RecordAccessContext access,
      final RecordCalculationStore.Installed installed) {
    var binding = calculations.bind(access.actor(), requested, installed);
    if (binding == null) {
      return new Rules(Map.of(), Map.of());
    }
    Map<String, RuntimeRecordModel> scopes = new LinkedHashMap<>(binding.details());
    scopes.put("parent", binding.parent());
    var parentAccess = permissions.authorize(access.actor(), binding.parent());
    Map<String, Boolean> readable = new LinkedHashMap<>();
    scopes.forEach((scope, model) -> {
      var raw = scope.equals("parent") ? parentAccess : permissions.owned(access.actor(), model, parentAccess);
      // Grants use request-facing codes; dependency identities remain stable across renames.
      model.requestedSchema().fields().forEach(field -> readable.put(scope + "." + field.fieldId(),
          raw.readableFields().contains(field.code()) && !raw.maskedFields().contains(field.code())));
    });
    Map<String, Set<String>> hidden = new LinkedHashMap<>();
    Map<String, Set<String>> outputs = new LinkedHashMap<>();
    binding.dependencies().forEach((target, sources) -> {
      int separator = target.indexOf('.');
      String model = scopes.get(target.substring(0, separator)).physicalModelId();
      String field = target.substring(separator + 1);
      outputs.computeIfAbsent(model, ignored -> new LinkedHashSet<>()).add(field);
      if (sources.stream().anyMatch(source -> !Boolean.TRUE.equals(readable.get(source)))) {
        hidden.computeIfAbsent(model, ignored -> new LinkedHashSet<>()).add(field);
      }
    });
    return new Rules(hidden, outputs);
  }

  record Rules(Map<String, Set<String>> hidden, Map<String, Set<String>> outputs) {
    Rules {
      hidden = immutable(hidden);
      outputs = immutable(outputs);
    }

    RecordAccessContext apply(final RuntimeRecordModel model, final RecordAccessContext raw) {
      var readable = new LinkedHashSet<>(raw.readableFields());
      var writable = new LinkedHashSet<>(raw.writableFields());
      var masked = new LinkedHashSet<>(raw.maskedFields());
      model.requestedSchema().fields().forEach(field -> {
        if (hidden.getOrDefault(model.physicalModelId(), Set.of()).contains(field.fieldId())) {
          readable.remove(field.code());
          masked.remove(field.code());
        }
        if (outputs.getOrDefault(model.physicalModelId(), Set.of()).contains(field.fieldId())) {
          writable.remove(field.code());
        }
      });
      return new RecordAccessContext(raw.actor(), raw.resourceCode(), readable, writable, masked,
          raw.rowScope(), raw.organizationId(), raw.resourceGeneration());
    }

    private static Map<String, Set<String>> immutable(final Map<String, Set<String>> source) {
      Map<String, Set<String>> copy = new LinkedHashMap<>();
      source.forEach((model, fields) -> copy.put(model, Set.copyOf(fields)));
      return Map.copyOf(copy);
    }
  }
}
