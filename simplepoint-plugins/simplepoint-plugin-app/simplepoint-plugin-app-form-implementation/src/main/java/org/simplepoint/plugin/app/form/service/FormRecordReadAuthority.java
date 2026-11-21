package org.simplepoint.plugin.app.form.service;

import java.util.LinkedHashSet;
import java.util.List;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;
import org.springframework.stereotype.Component;

/** Discovers only the original permission entrances of an exact, authorized current read. */
@Component
public class FormRecordReadAuthority {
  private final DnaOwnedRecordService dna;
  private final DnaInvocationContextFactory contexts;
  private final FormRelationTargetResolver relations;

  /**
   * Uses persisted proof, independently resolved publications and the current viewer's policies.
   */
  public FormRecordReadAuthority(
      DnaOwnedRecordService dna,
      DnaInvocationContextFactory contexts,
      FormRelationTargetResolver relations) {
    this.dna = dna;
    this.contexts = contexts;
    this.relations = relations;
  }

  /** Inverse membership depends on its one published anchor, not unrelated page relations. */
  DnaInvocationContext related(PublishedApplicationForm form, String relationId) {
    return contexts.forRelations(form, List.of(relations.resolve(form, relationId).target()));
  }

  /** A discovery result is not a grant; the subsequent DNA read rechecks every current proof. */
  DnaInvocationContext current(
      PublishedApplicationForm form,
      DnaInvocationContext bootstrap,
      RecordCurrentTargetsQuery query,
      long deadline) {
    var participants = new LinkedHashSet<>(originals(form, bootstrap, query, deadline));
    if (query.related() != null) {
      // Inverse reads must also retain their currently published anchor, even without copies.
      participants.add(relations.resolve(form, query.related().relationId()).target());
    }
    remaining(deadline);
    return contexts.forRelations(form, participants);
  }

  /** Mapping input discovery uses the selected target as the primary authorized resource. */
  List<PublishedApplicationForm> originals(
      PublishedApplicationForm form,
      DnaInvocationContext bootstrap,
      RecordCurrentTargetsQuery query,
      long deadline) {
    return resolve(
        form, dna.currentTargets(bootstrap, query.withTimeout(remaining(deadline))), deadline);
  }

  /** Replay derives grants from the committed proof, never from a mutable mapping observation. */
  DnaInvocationContext snapshot(
      PublishedApplicationForm form,
      DnaInvocationContext viewer,
      OwnedRecordHistoryQuery query,
      long deadline) {
    var targets = dna.snapshotTargets(viewer, query.withTimeout(remaining(deadline)));
    return contexts.forRelations(form, resolve(form, targets, deadline));
  }

  private List<PublishedApplicationForm> resolve(
      PublishedApplicationForm form, List<RecordSnapshotTarget> targets, long deadline) {
    if (targets == null
        || targets.size() > 50
        || targets.stream().anyMatch(java.util.Objects::isNull)
        || targets.stream().distinct().count() != targets.size()) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PERSISTENCE_FAILED,
          "Record permission evidence is absent or inconsistent");
    }
    var participants = relations.snapshotTargets(form, targets);
    remaining(deadline);
    return participants;
  }

  static long deadline() {
    return System.nanoTime() + 30_000_000_000L;
  }

  static int remaining(long deadline) {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0 || Thread.currentThread().isInterrupted()) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RUNTIME_UNAVAILABLE, "Current record read budget exhausted");
    }
    return (int) Math.max(1, Math.min(30_000, nanos / 1_000_000));
  }
}
