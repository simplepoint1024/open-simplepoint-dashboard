package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/**
 * Carries immutable evidence outside the source transaction; never exposes proof in browser DTOs.
 */
record RecordSnapshotRead(RecordReferenceCopyRead copied, List<RecordReferenceLabelOrigin> labels) {
  RecordSnapshotRead {
    labels = labels == null ? List.of() : List.copyOf(labels);
  }

  java.util.List<org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget> targets() {
    var required = new LinkedHashSet<RecordReferenceReadRequirement>();
    if (!copied.origins().isEmpty()) {
      copied
          .projection()
          .visibleOrigins(copied.values(), copied.origins())
          .forEach(origin -> required.addAll(origin.requirements()));
      copied
          .projection()
          .visibleDerived(copied.values(), copied.origins())
          .forEach(origin -> required.addAll(origin.requirements()));
    }
    var visibleLabels =
        copied.values().referenceSnapshots().stream()
            .map(RecordReferenceLabelOrigin::key)
            .collect(java.util.stream.Collectors.toSet());
    labels.stream()
        .filter(origin -> visibleLabels.contains(origin.key()))
        .forEach(origin -> required.addAll(origin.requirements()));
    var targets =
        required.stream()
            .map(
                requirement ->
                    new org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget(
                        requirement.modelId(),
                        requirement.appId(),
                        requirement.formId(),
                        requirement.resourceCode()))
            .distinct()
            .sorted(
                java.util.Comparator.comparing(
                        org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget
                            ::resourceCode)
                    .thenComparing(
                        org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget
                            ::modelId))
            .toList();
    if (targets.size() > 50) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    return targets;
  }

  OwnedRecordView authorize(
      RuntimeActor actor,
      RecordReferenceCopyAuthorization authorization,
      RecordExecutionDeadline deadline) {
    var values = copied.authorize(actor, authorization, deadline);
    return authorizeLabels(values, labels, actor, authorization, deadline);
  }

  static OwnedRecordView authorizeLabels(
      OwnedRecordView values,
      List<RecordReferenceLabelOrigin> labels,
      RuntimeActor actor,
      RecordReferenceCopyAuthorization authorization,
      RecordExecutionDeadline deadline) {
    if (values.referenceSnapshots().isEmpty()) {
      return values;
    }
    var indexed = new LinkedHashMap<List<String>, RecordReferenceLabelOrigin>();
    for (var origin : labels) {
      if (indexed.putIfAbsent(origin.key(), origin) != null) {
        throw RecordReferenceSnapshotProvenance.invalid();
      }
    }
    var required = new LinkedHashSet<RecordReferenceReadRequirement>();
    for (var label : values.referenceSnapshots()) {
      var origin = indexed.get(RecordReferenceLabelOrigin.key(label));
      if (origin != null) {
        origin.validate(label);
        required.addAll(origin.requirements());
      }
    }
    // Older immutable snapshots cannot prove the original transitive inputs. Preserve their
    // ordinary data, but do not replace missing evidence with the target's current formula.
    var allowed =
        required.isEmpty()
            ? java.util.Set.<RecordReferenceReadRequirement>of()
            : authorization.authorizeRequirements(actor, required, deadline);
    return new OwnedRecordView(
        values.parent(),
        values.details(),
        values.referenceSnapshots().stream()
            .filter(
                label -> {
                  var origin = indexed.get(RecordReferenceLabelOrigin.key(label));
                  return origin != null && allowed.containsAll(origin.requirements());
                })
            .toList());
  }
}
