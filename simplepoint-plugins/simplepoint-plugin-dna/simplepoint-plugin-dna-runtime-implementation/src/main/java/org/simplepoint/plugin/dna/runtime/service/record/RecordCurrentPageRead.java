package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;

/** Bounded proof discovery uses the same page compiler, row scope and query protection as reads. */
final class RecordCurrentPageRead {
  private final RecordSqlCompiler compiler;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordCalculationAccess calculations;

  RecordCurrentPageRead(
      RecordSqlCompiler compiler,
      RuntimeCalciteRecordBackend backend,
      RecordCalculationAccess calculations) {
    this.compiler = compiler;
    this.backend = backend;
    this.calculations = calculations;
  }

  RecordCurrentCopyRead capture(
      RuntimeRecordModel parent,
      RecordAccessContext access,
      RecordPageQuery query,
      RecordExecutionDeadline deadline) {
    return backend.read(
        parent,
        List.of(),
        access,
        deadline.remainingMillis(),
        session -> {
          var rules = calculations.resolve(parent, access, session.calculation());
          var visible = rules.apply(parent, access);
          var plan = compiler.page(parent, visible, query);
          var records =
              RecordQueryServiceImpl.views(session.query().apply(plan), plan, parent, visible);
          return RecordCurrentCopyRead.capture(
              parent,
              visible,
              List.of(),
              records.stream().map(row -> new OwnedRecordView(row, Map.of())).toList(),
              session.calculation(),
              session.query());
        });
  }
}
