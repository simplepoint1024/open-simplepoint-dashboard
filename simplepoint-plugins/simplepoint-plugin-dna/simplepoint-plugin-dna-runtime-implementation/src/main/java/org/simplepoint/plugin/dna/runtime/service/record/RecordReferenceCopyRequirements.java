package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

/**
 * Freezes the complete permission footprint of selected target values. Formula inputs use the
 * compiled graph and copied inputs retain their original flattened footprint, including after a
 * mapping was removed. This operation reads no replacement values and accepts no client proof.
 */
final class RecordReferenceCopyRequirements {
  private RecordReferenceCopyRequirements() {}

  static Map<String, List<RecordReferenceReadRequirement>> capture(
      RuntimeRecordModel parent,
      RecordCalculationWriteSupport.Binding binding,
      OwnedRecordView values,
      List<RecordReferenceCopySnapshot> origins,
      String resource,
      Set<String> selectedFields) {
    if (selectedFields.isEmpty()
        || selectedFields.size() > 128
        || binding != null
            && (!binding.parent().physicalModelId().equals(parent.physicalModelId())
                || !binding.parent().appId().equals(parent.appId())
                || !binding.parent().formId().equals(parent.formId()))) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    Map<String, RuntimeRecordModel> scopes = new LinkedHashMap<>();
    scopes.put("parent", parent);
    if (binding != null) {
      binding
          .details()
          .forEach(
              (scope, model) -> {
                if (scopes.putIfAbsent(scope, model) != null
                    || model.requestedSchema().owner() == null
                    || !model
                        .requestedSchema()
                        .owner()
                        .parentModelId()
                        .equals(parent.physicalModelId())) {
                  throw RecordReferenceSnapshotProvenance.invalid();
                }
              });
    }
    Map<List<String>, RecordReferenceCopySnapshot> copiedRows = new LinkedHashMap<>();
    for (var origin : origins) {
      String model =
          origin.detailModelId() == null ? parent.physicalModelId() : origin.detailModelId();
      if (copiedRows.putIfAbsent(List.of(model, origin.sourceRecordId()), origin) != null) {
        throw RecordReferenceSnapshotProvenance.invalid();
      }
    }
    Map<String, List<RecordReferenceReadRequirement>> result = new LinkedHashMap<>();
    for (String field : selectedFields) {
      var inputs = new LinkedHashSet<String>();
      inputs.add("parent." + field);
      if (binding != null) {
        var pending = new java.util.ArrayDeque<>(inputs);
        while (!pending.isEmpty()) {
          for (var dependency :
              binding.dependencies().getOrDefault(pending.removeFirst(), Set.of())) {
            if (inputs.add(dependency)) {
              pending.add(dependency);
            }
          }
          if (inputs.size() > 2048) {
            throw RecordReferenceSnapshotProvenance.invalid();
          }
        }
      }
      var collected = new LinkedHashMap<List<String>, RecordReferenceReadRequirement>();
      for (String input : inputs) {
        int separator = input.indexOf('.');
        String scope = separator < 1 ? "" : input.substring(0, separator);
        String inputField = separator < 1 ? "" : input.substring(separator + 1);
        var model = scopes.get(scope);
        var definition =
            model == null
                ? null
                : model.requestedSchema().fields().stream()
                    .filter(value -> value.fieldId().equals(inputField))
                    .findFirst()
                    .orElse(null);
        if (definition == null) {
          throw RecordReferenceSnapshotProvenance.invalid();
        }
        var rows =
            scope.equals("parent")
                ? List.of(values.parent())
                : values.details().get(model.physicalModelId());
        if (rows == null || !scope.equals("parent") && rows.size() > 40) {
          throw RecordReferenceSnapshotProvenance.invalid();
        }
        if (rows.isEmpty()) {
          // Empty SUM/count inputs still depend on this field's permission. A missing child row
          // is not a reason to discard its historical field scope after a formula is changed.
          merge(
              collected,
              requirement(parent, values.parent().id(), resource, model, null, inputField));
        }
        for (RecordView row : rows) {
          if (!row.values().containsKey(definition.code())
              || "***".equals(row.values().get(definition.code()))) {
            throw RecordReferenceSnapshotProvenance.invalid();
          }
          merge(
              collected,
              requirement(parent, values.parent().id(), resource, model, row.id(), inputField));
          var previous = copiedRows.get(List.of(model.physicalModelId(), row.id()));
          if (previous != null) {
            if (previous.sourceVersion() != row.version()) {
              throw RecordReferenceSnapshotProvenance.invalid();
            }
            previous.origins().stream()
                .filter(origin -> origin.destinationFieldId().equals(inputField))
                .forEach(
                    origin ->
                        origin
                            .requirements()
                            .forEach(requirement -> merge(collected, requirement)));
            previous.derivedOrigins().stream()
                .filter(origin -> origin.destinationFieldId().equals(inputField))
                .forEach(
                    origin -> {
                      origin.requirements().forEach(requirement -> merge(collected, requirement));
                      for (var local : origin.localRequirements()) {
                        boolean root = local.modelId().equals(parent.physicalModelId());
                        if (root && !values.parent().id().equals(local.recordId())) {
                          throw RecordReferenceSnapshotProvenance.invalid();
                        }
                        merge(
                            collected,
                            new RecordReferenceReadRequirement(
                                parent.physicalModelId(),
                                parent.appId(),
                                parent.formId(),
                                resource,
                                values.parent().id(),
                                root ? null : local.modelId(),
                                root ? null : local.recordId(),
                                local.fieldIds()));
                      }
                    });
          }
        }
      }
      result.put(field, List.copyOf(collected.values()));
    }
    return Map.copyOf(result);
  }

  private static RecordReferenceReadRequirement requirement(
      RuntimeRecordModel parent,
      String rootId,
      String resource,
      RuntimeRecordModel model,
      String recordId,
      String field) {
    boolean root = model.physicalModelId().equals(parent.physicalModelId());
    return new RecordReferenceReadRequirement(
        parent.physicalModelId(),
        parent.appId(),
        parent.formId(),
        resource,
        rootId,
        root ? null : model.physicalModelId(),
        root ? null : recordId,
        List.of(field));
  }

  private static void merge(
      Map<List<String>, RecordReferenceReadRequirement> collected,
      RecordReferenceReadRequirement value) {
    var previous = collected.get(value.rowIdentity());
    if (previous != null) {
      var fields = new LinkedHashSet<>(previous.fieldIds());
      fields.addAll(value.fieldIds());
      value =
          new RecordReferenceReadRequirement(
              value.modelId(),
              value.appId(),
              value.formId(),
              value.resourceCode(),
              value.recordId(),
              value.detailModelId(),
              value.detailRecordId(),
              new ArrayList<>(fields));
    }
    collected.put(value.rowIdentity(), value);
    if (collected.size() > 256) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
  }
}
