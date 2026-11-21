package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Objects;

/** Exact origin replacement and undo image, frozen with its owning business row patch. */
record RecordPublicationCopyChange(
    RecordReferenceCopyStore.Stored previous, RecordReferenceCopyStore.Stored candidate) {
  RecordPublicationCopyChange {
    Objects.requireNonNull(candidate, "candidate");
    if (candidate.sourceVersion() < 1
        || previous != null && previous.sourceVersion() != candidate.sourceVersion() - 1) {
      throw RecordReferenceAdmission.incompatible();
    }
  }

  void execute(
      RecordPublicationIntent intent,
      RecordPublicationAggregate.Row row,
      boolean restore,
      RecordPublicationSession session) {
    var key =
        new RecordReferenceCopyStore.Key(intent.key().tenantId(), row.modelId(), row.recordId());
    var store = new RecordReferenceCopyStore();
    var current = store.read(key, session.read(store.lookup(key)));
    if (!Objects.equals(current, restore ? candidate : previous)) {
      throw RecordReferenceAdmission.incompatible();
    }
    session.write(
        restore
            ? store.restorePublication(key, intent.actorId(), candidate, previous, intent.clock())
            : store.write(
                key,
                intent.actorId(),
                previous,
                candidate.sourceVersion(),
                candidate.origins(),
                candidate.derivedOrigins(),
                intent.clock()));
  }
}
