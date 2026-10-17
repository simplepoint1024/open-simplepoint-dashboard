package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordJsonSemantics;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalJsonValues;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

/** Native JSON round trips use the same production compiler, backend and query service as temporal values. */
final class RecordJsonDatabaseAssertions {
  private RecordJsonDatabaseAssertions() {
  }

  static void verify(final RuntimeRecordModel model, final RecordAccessContext access, final RecordSqlCompiler compiler,
      final RuntimeCalciteRecordBackend backend, final RecordQueryServiceImpl queries,
      final RecordSchemaReference schema, final boolean exactDecimals) {
    String input = "{\"z\":9007199254740993, \"a\": [true,\"日本\",1.25]}";
    var created = compiler.mutation(model, access, new RecordCreateMutation("json-create", Map.of("payload", input)), Instant.now());
    var admission = new RecordWriteAdmission(access.actor().tenantId(), 0);
    backend.execute(model, admission, List.of(created), 15_000);
    var lookup = new RecordLookupQuery(model.physicalModelId(), schema, created.recordId(), List.of("payload"));
    var values = queries.get(lookup).values();
    assertThat(values.get("payload")).isEqualTo("{\"a\":[true,\"日本\",1.25],\"z\":9007199254740993}");
    backend.execute(model, admission, List.of(compiler.mutation(model, access,
        new RecordUpdateMutation("json-update", created.recordId(), 0, values), Instant.now())), 15_000);
    assertThat(queries.get(lookup).values()).isEqualTo(values);
    var filter = new RecordFilter(RecordFilterOperator.EQUALS, "id", List.of(created.recordId()), List.of());
    var equality = new RecordValueCondition(RecordValueConditionOperator.EQUALS, "payload", List.of(
        new LogicalValue(LogicalValueKind.JSON, "{\"a\":[true,\"日本\",1.250],\"z\":9007199254740993}")), List.of());
    var equalQuery = inspection(model, schema, filter, equality, RecordJsonSemantics.STRUCTURAL_V1);
    assertThat(RecordCompletenessResult.read(equalQuery, backend.aggregateQuery(model,
        compiler.completeness(model, access, equalQuery), 15_000)).recordCount()).isEqualTo(1);
    long version = 1;
    String prefix = "{\"a\":[" + "0,".repeat(1999) + "0],\"z\":\"";
    String limit = prefix + "x".repeat(1_048_576 - prefix.length() - 2) + "\"}";
    List<String> samples = List.of("{}", "[]", "null", "\"\"", "\" \u3000\\t\"", "0", "false", "\"[]\"", "{\"a\":null}", "[null]",
        "{\"n\":1.1}", "\"" + " ".repeat(3000) + "x\"", "\"" + "日本".repeat(6000) + "\"", limit);
    for (int index = 0; index < samples.size(); index++) {
      String sample = samples.get(index);
      backend.execute(model, admission, List.of(compiler.mutation(model, access,
          new RecordUpdateMutation("json-case-" + index, created.recordId(), version++, Map.of("payload", sample)), Instant.now())), 15_000);
      assertThat(queries.get(lookup).values().get("payload")).isEqualTo(LogicalJsonValues.canonicalize(sample));
      var structural = inspection(model, schema, filter, null, RecordJsonSemantics.STRUCTURAL_V1);
      var checked = RecordCompletenessResult.read(structural, backend.aggregateQuery(model,
          compiler.completeness(model, access, structural), 15_000));
      assertThat(checked.emptyCounts().get("payload")).as(sample.substring(0, Math.min(sample.length(), 50)))
          .isEqualTo(index < 5 ? 1L : 0L);
      var emptyCondition = new RecordValueCondition(RecordValueConditionOperator.IS_EMPTY, "payload", List.of(), List.of());
      var nested = new RecordValueCondition(RecordValueConditionOperator.NOT, null, List.of(), List.of(emptyCondition));
      var present = inspection(model, schema, filter, nested, RecordJsonSemantics.STRUCTURAL_V1);
      assertThat(RecordCompletenessResult.read(present, backend.aggregateQuery(model,
          compiler.completeness(model, access, present), 15_000)).recordCount()).isEqualTo(index < 5 ? 0 : 1);
      var legacy = inspection(model, schema, filter, null, null);
      assertThat(RecordCompletenessResult.read(legacy, backend.aggregateQuery(model,
          compiler.completeness(model, access, legacy), 15_000)).emptyCounts().get("payload")).isZero();
    }
    var precision = compiler.mutation(model, access, new RecordUpdateMutation("json-precision", created.recordId(), version,
        Map.of("payload", "{\"n\":0.12345678901234567890123456789}")), Instant.now());
    if (exactDecimals) {
      backend.execute(model, admission, List.of(precision), 15_000);
      assertThat(queries.get(lookup).values().get("payload")).isEqualTo("{\"n\":0.12345678901234567890123456789}");
    } else {
      assertThatThrownBy(() -> backend.execute(model, admission, List.of(precision), 15_000))
          .isInstanceOfSatisfying(DnaRuntimeException.class, failure ->
              assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.VALUE_CONVERSION_FAILED));
      assertThat(queries.get(lookup).version()).isEqualTo(version);
    }
    long latest = queries.get(lookup).version();
    backend.execute(model, admission, List.of(compiler.mutation(model, access,
        new RecordUpdateMutation("json-clear", created.recordId(), latest, Collections.singletonMap("payload", null)),
        Instant.now())), 15_000);
    assertThat(queries.get(lookup).values()).containsEntry("payload", null);
    var absent = inspection(model, schema, filter, null, RecordJsonSemantics.STRUCTURAL_V1);
    assertThat(RecordCompletenessResult.read(absent, backend.aggregateQuery(model,
        compiler.completeness(model, access, absent), 15_000)).emptyCounts().get("payload")).isOne();
    var notEqual = new RecordValueCondition(RecordValueConditionOperator.NOT, null, List.of(), List.of(equality));
    var nullNegation = inspection(model, schema, filter, notEqual, RecordJsonSemantics.STRUCTURAL_V1);
    assertThat(RecordCompletenessResult.read(nullNegation, backend.aggregateQuery(model,
        compiler.completeness(model, access, nullNegation), 15_000)).recordCount()).isOne();
  }

  private static RecordCompletenessQuery inspection(final RuntimeRecordModel model, final RecordSchemaReference schema,
      final RecordFilter filter, final RecordValueCondition condition, final RecordJsonSemantics semantics) {
    return new RecordCompletenessQuery(model.physicalModelId(), model.appId(), model.formId(), schema,
        List.of("payload"), filter, condition, semantics);
  }
}
