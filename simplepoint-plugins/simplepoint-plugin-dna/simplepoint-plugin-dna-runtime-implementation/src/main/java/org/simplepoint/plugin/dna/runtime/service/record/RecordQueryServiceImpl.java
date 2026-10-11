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

  /** Creates the record query service. */
  public RecordQueryServiceImpl(
      final RuntimeRequestContext requestContext,
      final RuntimeRecordModelResolver modelResolver,
      final RuntimeRecordAccessPolicy accessPolicy,
      final RecordSqlCompiler compiler,
      final RuntimeCalciteRecordBackend backend,
      final RecordAuditSupport audit,
      final RecordAdmissionController admission,
      final RecordExecutionObserver observer
  ) {
    this.requestContext = requestContext;
    this.modelResolver = modelResolver;
    this.accessPolicy = accessPolicy;
    this.compiler = compiler;
    this.backend = backend;
    this.audit = audit;
    this.admission = admission;
    this.observer = observer;
  }

  @Override
  public RecordPageView page(final RecordPageQuery query) {
    return execute("page", query.physicalModelId(), invocation -> {
      long startedAt = System.nanoTime();
      RecordSelectPlan pagePlan = compiler.page(invocation.model(), invocation.access(), query);
      RecordSelectPlan countPlan = compiler.count(
          invocation.model(),
          invocation.access(),
          query.filter()
      );
      CalciteQueryResult pageResult = backend.query(invocation.model(), pagePlan, TIMEOUT_MS);
      CalciteQueryResult countResult = backend.query(invocation.model(), countPlan, TIMEOUT_MS);
      List<RecordView> records = views(pageResult, pagePlan, invocation.access());
      long total = countValue(countResult);
      audit.success(
          DnaRuntimeAuditEventType.RECORD_QUERIED,
          invocation.model(),
          invocation.access(),
          records.size(),
          elapsed(startedAt)
      );
      return new RecordPageView(query.page(), query.size(), total, records);
    });
  }

  @Override
  public long count(final RecordCountQuery query) {
    return execute("count", query.physicalModelId(), invocation -> {
      long startedAt = System.nanoTime();
      long count = countValue(backend.query(
          invocation.model(),
          compiler.count(invocation.model(), invocation.access(), query.filter()),
          TIMEOUT_MS
      ));
      audit.success(
          DnaRuntimeAuditEventType.RECORD_QUERIED,
          invocation.model(),
          invocation.access(),
          count,
          elapsed(startedAt)
      );
      return count;
    });
  }

  @Override
  public RecordView get(final RecordLookupQuery query) {
    return execute("get", query.physicalModelId(), invocation -> {
      long startedAt = System.nanoTime();
      RecordSelectPlan plan = compiler.lookup(invocation.model(), invocation.access(), query);
      List<RecordView> records = views(
          backend.query(invocation.model(), plan, TIMEOUT_MS),
          plan,
          invocation.access()
      );
      if (records.isEmpty()) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.RECORD_NOT_FOUND,
            "Record was not found in the active data scope"
        );
      }
      audit.success(
          DnaRuntimeAuditEventType.RECORD_QUERIED,
          invocation.model(),
          invocation.access(),
          1L,
          elapsed(startedAt)
      );
      return records.getFirst();
    });
  }

  private <T> T execute(
      final String operation,
      final String modelId,
      final Function<Invocation, T> callback
  ) {
    Invocation invocation = invocation(modelId);
    long startedAt = System.nanoTime();
    try (RecordAdmissionController.Permit ignored = admission.enter(
        invocation.access().actor().tenantId()
    )) {
      T result = callback.apply(invocation);
      observer.record(
          operation,
          "succeeded",
          invocation.model(),
          invocation.access(),
          elapsed(startedAt)
      );
      return result;
    } catch (RuntimeException exception) {
      DnaRuntimeErrorCode errorCode = exception instanceof DnaRuntimeException runtimeException
          ? runtimeException.errorCode() : DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED;
      audit.failure(
          DnaRuntimeAuditEventType.RECORD_QUERIED,
          invocation.model(),
          invocation.access(),
          elapsed(startedAt),
          errorCode
      );
      observer.record(
          operation,
          "failed",
          invocation.model(),
          invocation.access(),
          elapsed(startedAt)
      );
      throw exception;
    }
  }

  private Invocation invocation(final String modelId) {
    RuntimeActor actor = requestContext.requireActor();
    RuntimeRecordModel model = modelResolver.resolve(actor, modelId);
    return new Invocation(model, accessPolicy.authorize(actor, model));
  }

  private static List<RecordView> views(
      final CalciteQueryResult result,
      final RecordSelectPlan plan,
      final RecordAccessContext access
  ) {
    return result.rows().stream().map(row -> {
      String id = row.get(0).toString();
      long version = ((Number) row.get(1)).longValue();
      Map<String, Object> values = new LinkedHashMap<>();
      for (int index = 0; index < plan.valueFields().size(); index++) {
        String field = plan.valueFields().get(index);
        Object value = row.get(index + 2);
        values.put(field, access.maskedFields().contains(field) && value != null ? "***" : value);
      }
      return new RecordView(
          id,
          version,
          Collections.unmodifiableMap(values)
      );
    }).toList();
  }

  private static long countValue(final CalciteQueryResult result) {
    if (result.rows().isEmpty() || result.rows().getFirst().isEmpty()) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED,
          "Record count returned no value"
      );
    }
    return ((Number) result.rows().getFirst().getFirst()).longValue();
  }

  private static long elapsed(final long startedAt) {
    return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
  }

  private record Invocation(RuntimeRecordModel model, RecordAccessContext access) {
  }
}
