package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

class RecordSqlCompilerCalculatedValuesTest {
  private final RecordSqlCompiler compiler = new RecordSqlCompiler(new StandardLogicalValueConverter());
  private final List<LogicalFieldDefinition> fields = IntStream.range(0, 130).mapToObj(index -> new LogicalFieldDefinition(
      "field" + index, "field" + index, "Value " + index, LogicalFieldType.INT32, false, null, null, null, null)).toList();
  private final RuntimeRecordModel model = new RuntimeRecordModel("model", "app", "form", 1,
      new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, fields, List.of()),
      new PhysicalSchemaTarget("model", new RuntimeMetadataTarget("source", null, "public", "records")));
  private final Set<String> codes = fields.stream().map(LogicalFieldDefinition::code).collect(java.util.stream.Collectors.toSet());
  private final RecordAccessContext access = new RecordAccessContext(new RuntimeActor("tenant", "actor"), "resource", codes,
      codes, Set.of(), new DataScopeCondition("ALL", "created_org_dept_id", "created_by", "actor", Set.of()), null);

  @Test
  void computedColumnsDoNotConsumeThePublicPayloadBudgetButRemainTypedAndRequired() {
    Map<String, Object> calculated = values();
    var intent = new RecordCreateMutation("create", Map.of());
    compiler.validateIntent(model, access, intent, codes);
    var created = compiler.computedMutation(model, access, intent, calculated, Instant.EPOCH, "c657db4c-a0da-4be5-a818-698763958887");
    assertThat(created.parameters().subList(0, fields.size())).containsExactlyElementsOf(calculated.values());
    assertThatThrownBy(() -> new RecordCreateMutation("client", calculated)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> compiler.validateIntent(model, access,
        new RecordCreateMutation("forged", Map.of("field0", 1)), codes)).isInstanceOf(DnaRuntimeException.class);
    calculated.remove("field0");
    assertThatThrownBy(() -> compiler.computedMutation(model, access, intent, calculated, Instant.EPOCH, null))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void internalUpdatesKeepUnknownFieldsAndOverflowRejected() {
    var mutation = new RecordUpdateMutation("update", "c657db4c-a0da-4be5-a818-698763958887", 0, Map.of("field0", 0));
    var values = values();
    assertThat(compiler.computedMutation(model, access, mutation, values, Instant.EPOCH, null).resultVersion()).isEqualTo(1);
    values.put("field0", "2147483648");
    assertThatThrownBy(() -> compiler.computedMutation(model, access, mutation, values, Instant.EPOCH, null))
        .isInstanceOf(DnaRuntimeException.class);
    values.put("field0", 0);
    values.put("created_by", "forged");
    assertThatThrownBy(() -> compiler.computedMutation(model, access, mutation, values, Instant.EPOCH, null))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private Map<String, Object> values() {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int index = 0; index < fields.size(); index++) {
      values.put(fields.get(index).code(), index);
    }
    return values;
  }
}
