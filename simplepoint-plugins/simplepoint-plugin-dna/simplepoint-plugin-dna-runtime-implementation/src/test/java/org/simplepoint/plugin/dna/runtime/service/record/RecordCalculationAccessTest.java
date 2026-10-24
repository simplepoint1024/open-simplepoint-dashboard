package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationScope;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

class RecordCalculationAccessTest {
  private static final RuntimeActor ACTOR = new RuntimeActor("tenant", "actor");
  private final PhysicalSchemaCodec schemas = new PhysicalSchemaCodec(new ObjectMapper());
  private final RuntimeRecordModel parent = model("parent", schema(List.of(field("secret", "source_code"),
      field("middle", "middle_code"), field("total", "total_code"), field("constant", "constant_code")), null));
  private final RuntimeRecordModel child = model("child", schema(List.of(field("secret", "source_code"), field("total", "total_code"),
      new LogicalFieldDefinition("owner", "parent_record_id", "Owner", LogicalFieldType.UUID, false, null, null, null, null),
      new LogicalFieldDefinition("order", "row_order", "Order", LogicalFieldType.INT32, false, null, null, null, null)),
      new OwnedModelRelation("parent", "owner")));
  private final RuntimeRecordModelResolver models = mock(RuntimeRecordModelResolver.class);
  private final RuntimeRecordAccessPolicy policies = mock(RuntimeRecordAccessPolicy.class);
  private final RecordCalculationAccess access = RecordCalculationTestSupport.access(models, policies,
      new RecordSqlCompiler(new StandardLogicalValueConverter()));
  private final Set<String> fields = Set.of("id", "source_code", "middle_code", "total_code", "constant_code");

  @Test
  void hidesTransitiveOutputsForMaskedSourcesAndDoesNotEvaluateInvalidArithmetic() {
    var raw = grants(fields, Set.of("source_code"));
    var rules = rules(raw, grants(fields, Set.of()), "[secret]*2");
    var actual = rules.apply(parent, raw);
    assertThat(actual.readableFields()).contains("source_code", "constant_code").doesNotContain("middle_code", "total_code");
    assertThat(actual.maskedFields()).containsExactly("source_code");
    assertThat(actual.writableFields()).contains("source_code").doesNotContain("middle_code", "total_code", "constant_code");
    assertThat(actual.rowScope()).isEqualTo(raw.rowScope());
    assertThat(actual.resourceGeneration()).isEqualTo(12);
    assertThat(rules.apply(child, raw).readableFields()).doesNotContain("total_code");
  }

  @Test
  void resolvesAllScopesEvenWhenRequestOmitsSensitiveSourceAndCollection() {
    var parentAccess = grants(fields, Set.of());
    var detailAccess = grants(Set.of("total_code", "id"), Set.of());
    var rules = rules(parentAccess, detailAccess, "[secret]*2");
    var subset = new RuntimeRecordModel(parent.physicalModelId(), parent.appId(), parent.formId(), 1, parent.activeSchema(),
        1, schema(List.of(field("middle", "middle_code"), field("total", "old_total")), null), parent.target());
    var projected = rules.apply(subset, grants(Set.of("middle_code", "old_total"), Set.of()));
    assertThat(projected.readableFields()).containsExactly("middle_code");
    assertThat(projected.writableFields()).isEmpty();
    assertThat(rules.apply(parent, parentAccess).readableFields()).contains("source_code", "middle_code").doesNotContain("total_code");
  }

  @Test
  void ownMaskRemainsMaskedButMaskedIntermediateBlocksEveryConsumer() {
    var raw = grants(fields, Set.of("middle_code"));
    var rules = rules(raw, grants(fields, Set.of()), "[secret]*2");
    var projected = rules.apply(parent, raw);
    assertThat(projected.readableFields()).contains("middle_code").doesNotContain("total_code");
    assertThat(projected.maskedFields()).containsExactly("middle_code");
    assertThat(rules.apply(child, grants(fields, Set.of())).readableFields()).doesNotContain("total_code");
  }

  @Test
  void includesUntakenBranchesAndUsesRequestCodesAfterPhysicalRename() {
    var renamed = new RuntimeRecordModel("parent", "app", "form", 2,
        schema(List.of(field("secret", "renamed_source"), field("middle", "renamed_middle"),
            field("total", "renamed_total"), field("constant", "renamed_constant")), null),
        1, parent.requestedSchema(), parent.target());
    var raw = grants(Set.of("middle_code", "total_code", "constant_code"), Set.of());
    var installed = installed("IF(FALSE,[secret],1)");
    when(models.resolve(ACTOR, "parent", reference(parent))).thenReturn(renamed);
    when(models.resolve(ACTOR, "child", reference(child))).thenReturn(child);
    when(policies.authorize(ACTOR, renamed)).thenReturn(raw);
    when(policies.owned(ACTOR, child, raw)).thenReturn(grants(fields, Set.of()));
    assertThat(access.resolve(renamed, raw, installed).apply(renamed, raw).readableFields()).containsExactly("constant_code");
  }

  private RecordCalculationAccess.Rules rules(RecordAccessContext root, RecordAccessContext detail, String formula) {
    when(models.resolve(ACTOR, "parent", reference(parent))).thenReturn(parent);
    when(models.resolve(ACTOR, "child", reference(child))).thenReturn(child);
    when(policies.authorize(ACTOR, parent)).thenReturn(root);
    when(policies.owned(ACTOR, child, root)).thenReturn(detail);
    return access.resolve(parent, root, installed(formula));
  }

  private RecordCalculationStore.Installed installed(String formula) {
    var definition = new RecordCalculationDefinition("1", Map.of("middle", formula, "total", "SUM([lines.total])", "constant", "1/0"),
        Map.of("lines", new RecordCalculationScope("child", reference(child), Map.of("total", "[secret]*[parent.middle]"))));
    return new RecordCalculationStore.Installed(1, new RecordCalculationPolicy(1, "app", "form", reference(parent), definition));
  }

  private RecordSchemaReference reference(RuntimeRecordModel model) {return new RecordSchemaReference(1, schemas.encode(model.requestedSchema()).checksum());}
  private static RecordAccessContext grants(Set<String> readable, Set<String> masked) {
    return new RecordAccessContext(ACTOR, "resource", readable, readable, masked,
        new DataScopeCondition("ALL", "created_org_dept_id", "created_by", "actor", Set.of()), "dept", 12);
  }
  private static RuntimeRecordModel model(String id, PhysicalSchemaDefinition schema) {
    return new RuntimeRecordModel(id, "app", "form", 1, schema,
        new PhysicalSchemaTarget(id, new RuntimeMetadataTarget("source", "catalog", "schema", id)));
  }
  private static PhysicalSchemaDefinition schema(List<LogicalFieldDefinition> fields, OwnedModelRelation owner) {
    return new PhysicalSchemaDefinition(org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion.CURRENT, fields, List.of(), owner);
  }
  private static LogicalFieldDefinition field(String id, String code) {
    return new LogicalFieldDefinition(id, code, code, LogicalFieldType.DECIMAL, true, null, 18, 2, null);
  }
}
