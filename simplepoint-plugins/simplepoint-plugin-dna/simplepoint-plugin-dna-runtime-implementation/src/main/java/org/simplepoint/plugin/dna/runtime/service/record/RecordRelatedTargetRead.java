package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;

/** Shared inverse-list and preview anchor admission, with its own current target policy. */
final class RecordRelatedTargetRead {
  private final RuntimeRecordModelResolver models;
  private final RuntimeRecordAccessPolicy permissions;
  private final RecordSqlCompiler sql;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordCalculationAccess visibility;

  RecordRelatedTargetRead(
      RuntimeRecordModelResolver models,
      RuntimeRecordAccessPolicy permissions,
      RecordSqlCompiler sql,
      RuntimeCalciteRecordBackend backend,
      RecordCalculationAccess visibility) {
    this.models = models;
    this.permissions = permissions;
    this.sql = sql;
    this.backend = backend;
    this.visibility = visibility;
  }

  RecordResourceGuard guard(
      RuntimeRecordModel source,
      RecordAccessContext sourceAccess,
      RecordReferenceRule rule,
      String recordId,
      RecordExecutionDeadline deadline) {
    var actor = sourceAccess.actor();
    var target = models.resolveCurrent(actor, rule.targetModelId());
    RecordReferenceRules.requireTargetIdentity(rule, source, target);
    var access = permissions.authorizeReference(actor, target, rule.targetResourceCode());
    if (access.resourceGeneration() < 1) {
      throw unavailable();
    }
    boolean found =
        backend.read(
            target,
            List.of(),
            access,
            deadline.remainingMillis(),
            session -> {
              var visible =
                  visibility
                      .resolveReference(target, access, session.calculation())
                      .apply(target, access);
              // Existing relationships are governed by current read permission, not candidate
              // eligibility.
              return RecordReferenceVisibility.select(
                      target, visible, List.of(recordId), sql, session.query())
                  .contains(recordId);
            });
    if (!found) {
      throw unavailable();
    }
    return new RecordResourceGuard(
        target.physicalModelId(), access.resourceCode(), access.resourceGeneration(), true);
  }

  private static DnaRuntimeException unavailable() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
        "The inverse relation or its target record is unavailable");
  }
}
