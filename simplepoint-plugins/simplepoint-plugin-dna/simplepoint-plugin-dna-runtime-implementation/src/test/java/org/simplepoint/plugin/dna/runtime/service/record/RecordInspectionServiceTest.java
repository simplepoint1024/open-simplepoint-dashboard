package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;

class RecordInspectionServiceTest {

  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));
  private static final RecordCompletenessQuery QUERY = new RecordCompletenessQuery(
      "model", "app", "form", SCHEMA, List.of("title"), null);
  private final RuntimeRecordModelResolver resolver = mock(RuntimeRecordModelResolver.class);
  private final RuntimeCalciteRecordBackend backend = mock(RuntimeCalciteRecordBackend.class);
  private final RecordSqlCompiler compiler = mock(RecordSqlCompiler.class);
  private final RecordInspectionServiceImpl service = new RecordInspectionServiceImpl(new RuntimeRequestContext(),
      resolver, compiler, backend, new RecordAdmissionController(new DnaRuntimeExecutionProperties()),
      mock(RecordAuditSupport.class), mock(RecordExecutionObserver.class));

  @AfterEach
  void clearContext() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void rejectsUnauthenticatedAndNonManagementCallsBeforeResolvingModels() {
    assertDenied(() -> service.inspectCompleteness(QUERY));
    authorize(false);
    assertDenied(() -> service.inspectCompleteness(QUERY));
    verifyNoInteractions(resolver, compiler, backend);
  }

  @Test
  void preservesSchemaAndTenantResolutionFailuresWithoutInspectingData() {
    authorize(true);
    when(resolver.resolve(any(), any(), any())).thenThrow(new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE, "Requested version is unavailable"));
    assertThatThrownBy(() -> service.inspectCompleteness(QUERY)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE));
    verifyNoInteractions(compiler, backend);
  }

  @Test
  void rejectsIncompleteTruncatedOrImpossibleAggregatesRatherThanReportingNoImpact() {
    prepare();
    List<List<List<Object>>> rows = List.of(List.of(), List.of(List.of(1L)),
        List.of(List.of(1L, 2L)), List.of(List.of(-1L, 0L)), List.of(List.of(1L, -1L)),
        List.of(Arrays.asList(1L, null)), List.of(List.of(1L, "0")),
        List.of(List.of(1L, new BigDecimal("0.5"))),
        List.of(List.of(new BigDecimal("9223372036854775808"), 0L)));
    for (var invalid : rows) {
      when(backend.aggregateQuery(any(), any(), anyInt())).thenReturn(new CalciteQueryResult(
          List.of(), invalid, false, invalid.size(), 0, null));
      assertExecutionFailure();
    }
    when(backend.aggregateQuery(any(), any(), anyInt())).thenReturn(new CalciteQueryResult(
        List.of(), List.of(List.of(1L, 0L)), true, 1, 0, null));
    assertExecutionFailure();
  }

  @Test
  void propagatesExecutionFailureAndCanRetryTheReadWithoutReservedState() {
    prepare();
    var timeout = new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED, "Inspection timed out");
    when(backend.aggregateQuery(any(), any(), anyInt())).thenThrow(timeout).thenReturn(new CalciteQueryResult(
        List.of(), List.of(List.of(3L, new BigDecimal("2"))), false, 1, 0, null));
    assertThatThrownBy(() -> service.inspectCompleteness(QUERY)).isSameAs(timeout);
    assertThat(service.inspectCompleteness(QUERY).emptyCounts()).containsExactlyEntriesOf(Map.of("title", 2L));
  }

  private void assertExecutionFailure() {
    assertThatThrownBy(() -> service.inspectCompleteness(QUERY)).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED));
  }

  private void prepare() {
    authorize(true);
    var schema = new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(new LogicalFieldDefinition(
        "field-title", "title", "Title", LogicalFieldType.STRING, true, 100, null, null, null)), List.of());
    var model = new RuntimeRecordModel("model", "app", "form", 1, schema,
        new PhysicalSchemaTarget("model", new RuntimeMetadataTarget("source", null, null, "records")));
    when(resolver.resolve(any(), any(), any())).thenReturn(model);
    when(compiler.completeness(any(), any(), any())).thenReturn(new RecordSelectPlan("aggregate", List.of(),
        List.of("title"), 1));
  }

  private static void assertDenied(final Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
  }

  private static void authorize(final boolean administrator) {
    var authorization = new AuthorizationContext();
    authorization.setIsAdministrator(administrator);
    authorization.setUserId("publisher");
    authorization.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
  }
}
