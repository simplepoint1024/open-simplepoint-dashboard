package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;

/**
 * Do not commit an aggregate whose original proof already exceeds its single-detail read limits.
 */
final class RecordReferenceCopyBudget {
  private RecordReferenceCopyBudget() {}

  static void validate(
      String parentModel,
      List<RecordReferenceCopySnapshot> snapshots,
      Map<String, Set<String>> queryProtection) {
    if (snapshots.size() > 201) {
      throw RecordReferenceAdmission.incompatible();
    }
    int origins = 0;
    int requirements = 0;
    int bytes = 0;
    Set<String> localModels = new HashSet<>();
    Map<List<String>, Set<String>> targets = new HashMap<>();
    for (var snapshot : snapshots) {
      String model = snapshot.detailModelId() == null ? parentModel : snapshot.detailModelId();
      var protectedFields = queryProtection.getOrDefault(model, Set.of());
      var stored =
          new RecordReferenceCopyStore.Stored(
              snapshot.sourceVersion(), snapshot.origins(), snapshot.derivedOrigins());
      bytes += RecordReferenceCopyStore.encodedBytes(stored);
      origins += snapshot.origins().size() + snapshot.derivedOrigins().size();
      for (var origin : snapshot.origins()) {
        if (!protectedFields.contains(origin.destinationFieldId())) {
          throw RecordReferenceAdmission.incompatible();
        }
        requirements += origin.requirements().size();
        origin.requirements().forEach(requirement -> add(targets, requirement));
      }
      for (var origin : snapshot.derivedOrigins()) {
        if (!protectedFields.contains(origin.destinationFieldId())) {
          throw RecordReferenceAdmission.incompatible();
        }
        origin.localRequirements().forEach(requirement -> localModels.add(requirement.modelId()));
        requirements += origin.requirements().size();
        origin.requirements().forEach(requirement -> add(targets, requirement));
      }
      if (origins > 10050
          || requirements > 16384
          || bytes > 8 * 1024 * 1024
          || localModels.size() > 6) {
        throw RecordReferenceAdmission.incompatible();
      }
    }
  }

  private static void add(
      Map<List<String>, Set<String>> targets, RecordReferenceReadRequirement requirement) {
    var group =
        List.of(
            requirement.modelId(),
            requirement.appId(),
            requirement.formId(),
            requirement.resourceCode());
    var details = targets.computeIfAbsent(group, ignored -> new HashSet<>());
    if (requirement.detailModelId() != null) {
      details.add(requirement.detailModelId());
    }
    if (targets.size() > 128 || details.size() > 5) {
      throw RecordReferenceAdmission.incompatible();
    }
  }
}
