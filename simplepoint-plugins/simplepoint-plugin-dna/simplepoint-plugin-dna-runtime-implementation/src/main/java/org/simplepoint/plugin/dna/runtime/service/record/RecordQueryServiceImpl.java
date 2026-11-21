package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCountQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.service.RecordQueryService;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.stereotype.Service;

/** Authorized structured record reads backed by Calcite. */
@Service
public class RecordQueryServiceImpl implements RecordQueryService {

  private static final int TIMEOUT_MS = 30_000;

  private final RuntimeRequestContext requestContext;
  private final RuntimeRecordModelResolver modelResolver;
  private final RuntimeRecordAccessPolicy accessPolicy;
  private final RecordSqlCompiler compiler;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordAuditSupport audit;
  private final RecordAdmissionController admission;
  private final RecordExecutionObserver observer;
  private final RecordCalculationAccess calculations;
  private final RecordReferenceCopyAuthorization copies;

  /** Creates the record query service. */
  public RecordQueryServiceImpl(
      final RuntimeRequestContext requestContext,
      final RuntimeRecordModelResolver modelResolver,
      final RuntimeRecordAccessPolicy accessPolicy,
      final RecordSqlCompiler compiler,
      final RuntimeCalciteRecordBackend backend,
      final RecordAuditSupport audit,
      final RecordAdmissionController admission,
      final RecordExecutionObserver observer,
      final RecordCalculationAccess calculations) {
    this.requestContext = requestContext;
    this.modelResolver = modelResolver;
    this.accessPolicy = accessPolicy;
    this.compiler = compiler;
    this.backend = backend;
    this.audit = audit;
    this.admission = admission;
    this.observer = observer;
    this.calculations = calculations;
    this.copies =
        new RecordReferenceCopyAuthorization(
            modelResolver, accessPolicy, compiler, backend, calculations);
  }

  @Override
  public RecordPageView page(final RecordPageQuery query) {
    return execute(
        "page",
        query.physicalModelId(),
        query.schemaReference(),
        invocation -> {
          RecordSelectPlan pagePlan = compiler.page(invocation.model(), invocation.access(), query);
          RecordSelectPlan countPlan =
              compiler.count(
                  invocation.model(), invocation.access(), query.filter(), query.condition());
          CalciteQueryResult pageResult = invocation.reader().apply(pagePlan);
          CalciteQueryResult countResult = invocation.reader().apply(countPlan);
          List<RecordView> records =
              views(pageResult, pagePlan, invocation.model(), invocation.access());
          long total = countValue(countResult);

          var page = new RecordPageView(query.page(), query.size(), total, records);
          return new Authorized<>(
              page,
              records.size(),
              current(invocation, records),
              values ->
                  new RecordPageView(
                      query.page(),
                      query.size(),
                      total,
                      values.stream()
                          .map(
                              org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView::parent)
                          .toList()));
        });
  }

  @Override
  public List<String> visibleReferences(
      final org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceVisibilityQuery query) {
    return execute(
        "reference-visibility",
        query.physicalModelId(),
        query.schemaReference(),
        query.timeoutMs(),
        invocation -> {
          var access = invocation.access();
          List<String> visible = List.of();
          if (RecordReferenceVisibility.readable(
              invocation.model(), access, query.labelFieldId())) {
            var ids =
                RecordReferenceVisibility.select(
                    invocation.model(), access, query.recordIds(), compiler, invocation.reader());
            visible = query.recordIds().stream().filter(ids::contains).toList();
          }

          return new Authorized<>(visible, visible.size(), null, null);
        });
  }

  @Override
  public long count(final RecordCountQuery query) {
    return execute(
        "count",
        query.physicalModelId(),
        query.schemaReference(),
        invocation -> {
          long count =
              countValue(
                  invocation
                      .reader()
                      .apply(
                          compiler.count(invocation.model(), invocation.access(), query.filter())));

          return new Authorized<>(count, count, null, null);
        });
  }

  @Override
  public RecordView get(final RecordLookupQuery query) {
    return execute(
        "get",
        query.physicalModelId(),
        query.schemaReference(),
        invocation -> {
          RecordSelectPlan plan = compiler.lookup(invocation.model(), invocation.access(), query);
          List<RecordView> records =
              views(invocation.reader().apply(plan), plan, invocation.model(), invocation.access());
          if (records.isEmpty()) {
            throw new DnaRuntimeException(
                DnaRuntimeErrorCode.RECORD_NOT_FOUND,
                "Record was not found in the active data scope");
          }

          return new Authorized<>(
              records.getFirst(),
              1,
              current(invocation, records),
              values -> values.getFirst().parent());
        });
  }

  private <T> T execute(
      final String operation,
      final String modelId,
      final org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference schemaReference,
      final Function<Invocation, Authorized<T>> callback) {
    return execute(operation, modelId, schemaReference, TIMEOUT_MS, callback);
  }

  private <T> T execute(
      String operation,
      String modelId,
      org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference schemaReference,
      int timeoutMs,
      Function<Invocation, Authorized<T>> callback) {
    var deadline = new RecordExecutionDeadline(timeoutMs);
    Invocation invocation = invocation(modelId, schemaReference);
    long startedAt = System.nanoTime();
    try (RecordAdmissionController.Permit ignored =
        admission.enter(invocation.access().actor().tenantId())) {
      Authorized<T> captured =
          backend.read(
              invocation.model(),
              List.of(),
              invocation.access(),
              deadline.remainingMillis(),
              session -> {
                var rules =
                    calculations.resolve(
                        invocation.model(), invocation.access(), session.calculation());
                return callback.apply(
                    new Invocation(
                        invocation.model(),
                        rules.apply(invocation.model(), invocation.access()),
                        session.query(),
                        session.calculation()));
              });
      final T result =
          captured.copies() == null
              ? captured.value()
              : captured.project().apply(captured.copies().authorize(copies, deadline));
      deadline.remainingMillis();
      audit.success(
          DnaRuntimeAuditEventType.RECORD_QUERIED,
          invocation.model(),
          invocation.access(),
          captured.count(),
          elapsed(startedAt));
      observer.record(
          operation, "succeeded", invocation.model(), invocation.access(), elapsed(startedAt));
      return result;
    } catch (RuntimeException exception) {
      DnaRuntimeErrorCode errorCode =
          exception instanceof DnaRuntimeException runtimeException
              ? runtimeException.errorCode()
              : DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED;
      audit.failure(
          DnaRuntimeAuditEventType.RECORD_QUERIED,
          invocation.model(),
          invocation.access(),
          elapsed(startedAt),
          errorCode);
      observer.record(
          operation, "failed", invocation.model(), invocation.access(), elapsed(startedAt));
      throw exception;
    }
  }

  private Invocation invocation(
      final String modelId,
      final org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference schemaReference) {
    RuntimeActor actor = requestContext.requireActor();
    RuntimeRecordModel model = modelResolver.resolve(actor, modelId, schemaReference);
    return new Invocation(model, accessPolicy.authorize(actor, model), null, null);
  }

  static List<RecordView> views(
      final CalciteQueryResult result,
      final RecordSelectPlan plan,
      final RuntimeRecordModel model,
      final RecordAccessContext access) {
    return result.rows().stream()
        .map(
            row -> {
              String id = row.get(0).toString();
              long version = ((Number) row.get(1)).longValue();
              Map<String, Object> values = new LinkedHashMap<>();
              for (int index = 0; index < plan.valueFields().size(); index++) {
                String field = plan.valueFields().get(index);
                Object value = row.get(index + 2);
                values.put(
                    field,
                    access.maskedFields().contains(field) && value != null
                        ? "***"
                        : RecordWireValues.wireValue(model, field, value));
              }
              return new RecordView(id, version, Collections.unmodifiableMap(values));
            })
        .toList();
  }

  static long countValue(final CalciteQueryResult result) {
    if (result.rows().isEmpty() || result.rows().getFirst().isEmpty()) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED, "Record count returned no value");
    }
    return ((Number) result.rows().getFirst().getFirst()).longValue();
  }

  private static long elapsed(final long startedAt) {
    return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
  }

  private record Invocation(
      RuntimeRecordModel model,
      RecordAccessContext access,
      Function<RecordSelectPlan, CalciteQueryResult> reader,
      RecordCalculationStore.Installed installed) {}

  private static RecordCurrentCopyRead current(Invocation invocation, List<RecordView> records) {
    return RecordCurrentCopyRead.capture(
        invocation.model(),
        invocation.access(),
        List.of(),
        records.stream()
            .map(
                row ->
                    new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView(
                        row, Map.of()))
            .toList(),
        invocation.installed(),
        invocation.reader());
  }

  private record Authorized<T>(
      T value,
      long count,
      RecordCurrentCopyRead copies,
      Function<List<org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView>, T> project) {}
}
