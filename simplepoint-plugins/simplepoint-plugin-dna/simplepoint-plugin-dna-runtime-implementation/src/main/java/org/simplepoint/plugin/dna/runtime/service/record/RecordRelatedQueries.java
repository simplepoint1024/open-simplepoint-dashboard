package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordPageQuery;

/** Installed-relation selection; all physical participants come from the owning policy. */
final class RecordRelatedQueries {
  private final RuntimeRecordModelResolver models;
  private final RuntimeRecordAccessPolicy permissions;
  private final RecordSqlCompiler sql;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordCalculationWriteSupport calculations;
  private final RecordCalculationAccess visibility;

  RecordRelatedQueries(
      RuntimeRecordModelResolver models,
      RuntimeRecordAccessPolicy permissions,
      RecordSqlCompiler sql,
      RuntimeCalciteRecordBackend backend,
      RecordCalculationWriteSupport calculations,
      RecordCalculationAccess visibility) {
    this.models = models;
    this.permissions = permissions;
    this.sql = sql;
    this.backend = backend;
    this.calculations = calculations;
    this.visibility = visibility;
  }

  RecordPageView page(
      RuntimeRecordModel parent,
      RecordAccessContext access,
      RelatedRecordPageQuery query,
      RecordExecutionDeadline deadline) {
    var captured = capture(parent, access, query, deadline);
    var authorization =
        new RecordReferenceCopyAuthorization(models, permissions, sql, backend, visibility);
    return new RecordPageView(
        captured.page().page(),
        captured.page().size(),
        captured.page().total(),
        captured.copies().authorize(authorization, deadline).stream()
            .map(org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView::parent)
            .toList());
  }

  Page capture(
      RuntimeRecordModel parent,
      RecordAccessContext access,
      RelatedRecordPageQuery query,
      RecordExecutionDeadline deadline) {
    // Discover immutable participants before opening the query transaction. Recheck the policy
    // under admission below; a concurrent publication cannot replace the selected relation.
    var binding =
        backend.read(
            parent,
            List.of(),
            access,
            deadline.remainingMillis(),
            session -> calculations.bind(access.actor(), parent, session.calculation()));
    if (binding == null) {
      throw unavailable();
    }
    var rule =
        binding.installed().policy().definition().referencesOrEmpty().stream()
            .filter(
                candidate ->
                    !candidate.retired() && candidate.relationId().equals(query.relationId()))
            .findFirst()
            .orElseThrow(RecordRelatedQueries::unavailable);
    if (!rule.targetModelId().equals(query.targetModelId())) {
      throw RecordReferenceAdmission.incompatible();
    }
    var source =
        rule.source().scope().equals("parent")
            ? parent
            : binding.details().get(rule.source().scope());
    if (source == null) {
      throw RecordReferenceAdmission.incompatible();
    }
    boolean owned = !source.physicalModelId().equals(parent.physicalModelId());
    if (owned) {
      OwnedRecordBinding.parentCode(parent, source);
    }
    var sourceAccess = owned ? permissions.owned(access.actor(), source, access) : access;
    // Check the FK grant before target discovery, and again after calculated-field restrictions.
    new RecordRelatedSelection(source, sourceAccess, rule, query.targetRecordId()).sourceCode();
    var targetGuard =
        new RecordRelatedTargetRead(models, permissions, sql, backend, visibility)
            .guard(source, access, rule, query.targetRecordId(), deadline);
    var captured =
        backend.read(
            parent,
            owned ? List.of(source) : List.of(),
            access,
            List.of(targetGuard),
            deadline.remainingMillis(),
            session -> {
              if (!Objects.equals(binding.installed(), session.calculation())) {
                throw RecordReferenceAdmission.incompatible();
              }
              var rules = visibility.resolve(parent, access, session.calculation());
              var visibleParent = rules.apply(parent, access);
              var selected =
                  new RecordRelatedSelection(
                      source, rules.apply(source, sourceAccess), rule, query.targetRecordId());
              var request = query.records();
              var page = sql.page(parent, visibleParent, request, selected);
              var count =
                  sql.count(parent, visibleParent, request.filter(), request.condition(), selected);
              var records =
                  RecordQueryServiceImpl.views(
                      session.query().apply(page), page, parent, visibleParent);
              long total = RecordQueryServiceImpl.countValue(session.query().apply(count));
              return new Page(
                  new RecordPageView(request.page(), request.size(), total, records),
                  RecordCurrentCopyRead.capture(
                      parent,
                      visibleParent,
                      List.of(),
                      records.stream()
                          .map(
                              row ->
                                  new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView(
                                      row, java.util.Map.of()))
                          .toList(),
                      session.calculation(),
                      session.query()));
            });
    return captured;
  }

  private static DnaRuntimeException unavailable() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
        "The inverse relation or its target record is unavailable");
  }

  record Page(RecordPageView page, RecordCurrentCopyRead copies) {}
}
