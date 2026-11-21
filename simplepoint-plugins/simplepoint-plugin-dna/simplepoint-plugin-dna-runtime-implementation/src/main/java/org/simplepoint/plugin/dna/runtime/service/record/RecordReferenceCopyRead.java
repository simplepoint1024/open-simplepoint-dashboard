package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/** Detached source projection; all target checks run after the owning source read has closed. */
record RecordReferenceCopyRead(
    OwnedRecordView values,
    List<RecordReferenceCopySnapshot> origins,
    RecordReferenceCopyProjection projection,
    RuntimeRecordModel parent,
    RecordAccessContext access) {
  RecordReferenceCopyRead(
      OwnedRecordView values,
      List<RecordReferenceCopySnapshot> origins,
      RecordReferenceCopyProjection projection) {
    this(values, origins, projection, null, null);
  }

  RecordReferenceCopyRead {
    origins = List.copyOf(origins);
    if (!origins.isEmpty() && projection == null) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
  }

  static RecordReferenceCopyRead ordinary(OwnedRecordView values) {
    return new RecordReferenceCopyRead(values, List.of(), null);
  }

  OwnedRecordView authorize(
      RuntimeActor actor,
      RecordReferenceCopyAuthorization authorization,
      RecordExecutionDeadline deadline) {
    if (origins.isEmpty()) {
      return values;
    }
    var visible = projection.visibleOrigins(values, origins);
    var derived = projection.visibleDerived(values, origins);
    if (!derived.isEmpty() && (parent == null || access == null || !actor.equals(access.actor()))) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    var localAllowed =
        derived.isEmpty()
            ? java.util.Set.<RecordDerivedCopyOrigin>of()
            : authorization.readableDerived(
                parent, access, values.parent().id(), derived, deadline);
    var required =
        new java.util.LinkedHashSet<
            org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement>();
    visible.forEach(origin -> required.addAll(origin.requirements()));
    localAllowed.forEach(origin -> required.addAll(origin.requirements()));
    var allowed = authorization.authorizeRequirements(actor, required, deadline);
    return projection.project(
        values,
        origins,
        visible.stream()
            .filter(origin -> allowed.containsAll(origin.requirements()))
            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
        localAllowed.stream()
            .filter(origin -> allowed.containsAll(origin.requirements()))
            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
  }
}
