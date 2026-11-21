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
  public RecordCalculationAccess(
      final RecordCalculationWriteSupport calculations,
      final RuntimeRecordAccessPolicy permissions) {
    this.calculations = calculations;
    this.permissions = permissions;
  }

  RecordCalculationWriteSupport.Binding binding(
      RuntimeRecordModel model,
      RecordAccessContext access,
      RecordCalculationStore.Installed installed) {
    return calculations.bind(access.actor(), model, installed);
  }

  Rules resolve(
      final RuntimeRecordModel requested,
      final RecordAccessContext access,
      final RecordCalculationStore.Installed installed) {
    return resolve(requested, access, installed, false);
  }

  private Rules resolve(
      final RuntimeRecordModel requested,
      final RecordAccessContext access,
      final RecordCalculationStore.Installed installed,
      final boolean reference) {
    var binding = calculations.bind(access.actor(), requested, installed);
    if (binding == null) {
      return new Rules(
          Map.of(), Map.of(), installed == null ? Map.of() : installed.policy().queryProtection());
    }
    Map<String, RuntimeRecordModel> scopes = new LinkedHashMap<>(binding.details());
    scopes.put("parent", binding.parent());
    var parentAccess =
        reference
            ? permissions.authorizeReference(
                access.actor(), binding.parent(), access.resourceCode())
            : permissions.authorize(access.actor(), binding.parent());
    Map<String, Boolean> readable = new LinkedHashMap<>();
    scopes.forEach(
        (scope, model) -> {
          var raw =
              scope.equals("parent")
                  ? parentAccess
                  : permissions.owned(access.actor(), model, parentAccess);
          // Grants use request-facing codes; dependency identities remain stable across renames.
          model
              .requestedSchema()
              .fields()
              .forEach(
                  field ->
                      readable.put(
                          scope + "." + field.fieldId(),
                          raw.readableFields().contains(field.code())
                              && !raw.maskedFields().contains(field.code())));
        });
    Map<String, Set<String>> hidden = new LinkedHashMap<>();
    Map<String, Set<String>> outputs = new LinkedHashMap<>();
    binding
        .dependencies()
        .forEach(
            (target, sources) -> {
              int separator = target.indexOf('.');
              String model = scopes.get(target.substring(0, separator)).physicalModelId();
              String field = target.substring(separator + 1);
              outputs.computeIfAbsent(model, ignored -> new LinkedHashSet<>()).add(field);
              if (sources.stream().anyMatch(source -> !Boolean.TRUE.equals(readable.get(source)))) {
                hidden.computeIfAbsent(model, ignored -> new LinkedHashSet<>()).add(field);
              }
            });
    return new Rules(hidden, outputs, installed.policy().queryProtection());
  }

  /** Related-record projections apply the target resource's own transitive field restrictions. */
  Rules resolveReference(
      final RuntimeRecordModel requested,
      final RecordAccessContext access,
      final RecordCalculationStore.Installed installed) {
    return resolve(requested, access, installed, true);
  }

  record Rules(
      Map<String, Set<String>> hidden,
      Map<String, Set<String>> outputs,
      Map<String, Set<String>> queryProtection) {
    Rules(Map<String, Set<String>> hidden, Map<String, Set<String>> outputs) {
      this(hidden, outputs, Map.of());
    }

    Rules {
      hidden = immutable(hidden);
      outputs = immutable(outputs);
      queryProtection = RecordQueryProtection.immutable(queryProtection);
    }

    RecordAccessContext apply(final RuntimeRecordModel model, final RecordAccessContext raw) {
      var readable = new LinkedHashSet<>(raw.readableFields());
      var writable = new LinkedHashSet<>(raw.writableFields());
      var masked = new LinkedHashSet<>(raw.maskedFields());
      model
          .requestedSchema()
          .fields()
          .forEach(
              field -> {
                if (hidden
                    .getOrDefault(model.physicalModelId(), Set.of())
                    .contains(field.fieldId())) {
                  readable.remove(field.code());
                  masked.remove(field.code());
                }
                if (outputs
                    .getOrDefault(model.physicalModelId(), Set.of())
                    .contains(field.fieldId())) {
                  writable.remove(field.code());
                }
              });
      return new RecordAccessContext(
              raw.actor(),
              raw.resourceCode(),
              readable,
              writable,
              masked,
              raw.rowScope(),
              raw.organizationId(),
              raw.resourceGeneration(),
              raw.queryProtectedFields())
          .withQueryProtection(queryProtection.getOrDefault(model.physicalModelId(), Set.of()));
    }

    private static Map<String, Set<String>> immutable(final Map<String, Set<String>> source) {
      Map<String, Set<String>> copy = new LinkedHashMap<>();
      source.forEach((model, fields) -> copy.put(model, Set.copyOf(fields)));
      return Map.copyOf(copy);
    }
  }
}
