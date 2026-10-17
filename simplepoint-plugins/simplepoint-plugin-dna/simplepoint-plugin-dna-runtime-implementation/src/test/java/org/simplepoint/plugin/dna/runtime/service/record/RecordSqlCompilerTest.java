package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.core.query.QueryDocument;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSortDirection;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

class RecordSqlCompilerTest {

  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(
      1,
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  );

  private final RecordSqlCompiler compiler = new RecordSqlCompiler(
      new StandardLogicalValueConverter()
  );

  @Test
  void compilesTenantScopeFilterProjectionAndStableSortWithBindings() {
    RuntimeRecordModel model = model();
    RecordAccessContext access = access(Set.of("id", "record_version", "title", "amount"));

    RecordSelectPlan plan = compiler.page(
        model,
        access,
        new RecordPageQuery(
            "model-1",
            SCHEMA,
            2,
            20,
            List.of("title"),
            new RecordFilter(
                RecordFilterOperator.GREATER_THAN,
                "amount",
                List.of("10.25"),
                List.of()
            ),
            List.of(new RecordSort("title", RecordSortDirection.DESC))
        )
    );

    assertThat(plan.sql()).contains(
        "\"tenant_id\" = ?",
        "\"deleted_at\" IS NULL",
        "\"created_by\" = ?",
        "\"amount\" > ?",
        "ORDER BY \"title\" DESC, \"id\" ASC",
        "OFFSET 40 ROWS FETCH NEXT 20 ROWS ONLY"
    );
    assertThat(plan.sql()).doesNotContain("tenant-a", "10.25");
    assertThat(plan.parameters()).containsExactly("tenant-a", "user-a", new java.math.BigDecimal("10.25"));
  }

  @Test
  void rejectsFilteringByMaskedField() {
    RuntimeRecordModel model = model();
    RecordAccessContext access = new RecordAccessContext(
        new RuntimeActor("tenant-a", "user-a"),
        "app:page",
        Set.of("id", "record_version", "title"),
        Set.of("title"),
        Set.of("title"),
        new DataScopeCondition("ALL", "created_org_dept_id", "created_by", "user-a", Set.of()),
        "dept-a"
    );

    assertThatThrownBy(() -> compiler.page(
        model,
        access,
        new RecordPageQuery(
            "model-1",
            SCHEMA,
            0,
            10,
            List.of("title"),
            new RecordFilter(
                RecordFilterOperator.EQUALS,
                "title",
                List.of("secret"),
                List.of()
            ),
            List.of()
        )
    )).isInstanceOfSatisfying(
        DnaRuntimeException.class,
        exception -> assertThat(exception.errorCode()).isEqualTo(
            DnaRuntimeErrorCode.RECORD_ACCESS_DENIED
        )
    );
  }

  @Test
  void baseFieldsCannotBeSpoofedEvenWithAnEditableGrant() {
    var codes = new java.util.HashSet<>(org.simplepoint.plugin.dna.runtime.api.record.RecordSystemField.storageCodes());
    codes.addAll(Set.of("id", "record_version"));
    var permissions = new RecordAccessContext(new RuntimeActor("tenant-a", "user-a"), "app:page", codes, codes,
        Set.of(), new DataScopeCondition("ALL", "created_org_dept_id", "created_by", "user-a", Set.of()), "dept-a");
    for (String field : codes) {
      assertThatThrownBy(() -> compiler.mutation(model(), permissions,
          new RecordUpdateMutation("update", "record-1", 0, Map.of(field, "forged")), Instant.now()))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  @Test
  void maskedBaseFieldsCannotBeUsedForFilterOrSortInference() {
    var permissions = new RecordAccessContext(new RuntimeActor("tenant-a", "user-a"), "app:page",
        Set.of("id", "record_version", "created_by"), Set.of(), Set.of("created_by"),
        new DataScopeCondition("ALL", "created_org_dept_id", "created_by", "user-a", Set.of()), "dept-a");
    var filter = new RecordFilter(RecordFilterOperator.EQUALS, "created_by", List.of("actor"), List.of());
    assertThatThrownBy(() -> compiler.page(model(), permissions, new RecordPageQuery("model-1", SCHEMA,
        0, 10, List.of("created_by"), filter, List.of()))).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> compiler.page(model(), permissions, new RecordPageQuery("model-1", SCHEMA,
        0, 10, List.of("created_by"), null, List.of(new RecordSort("created_by", RecordSortDirection.ASC)))))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void rejectsUpdatesToFieldsThatDefineTheActiveRowScope() {
    RuntimeRecordModel model = model();
    RecordAccessContext access = new RecordAccessContext(
        new RuntimeActor("tenant-a", "user-a"),
        "app:page",
        Set.of("id", "record_version", "title", "amount"),
        Set.of("title", "amount"),
        Set.of(),
        new DataScopeCondition(
            "CUSTOM",
            "created_org_dept_id",
            "created_by",
            "user-a",
            Set.of(),
            false,
            List.of(new QueryDocument.Node(
                "equals",
                "title",
                TextNode.valueOf("allowed"),
                List.of()
            ))
        ),
        "dept-a"
    );

    assertThatThrownBy(() -> compiler.mutation(
        model,
        access,
        new RecordUpdateMutation("update", "record-1", 0, Map.of("title", "outside")),
        Instant.now()
    )).isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
        assertThat(exception.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
  }

  @Test
  void mapsHistoricalFieldCodesToCurrentPhysicalColumns() {
    PhysicalSchemaDefinition requested = new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(new LogicalFieldDefinition(
            "field-title", "title", "Title", LogicalFieldType.STRING,
            true, 64, null, null, null
        )),
        List.of()
    );
    PhysicalSchemaDefinition active = new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(new LogicalFieldDefinition(
            "field-title", "subject", "Subject", LogicalFieldType.STRING,
            true, 128, null, null, null
        )),
        List.of()
    );
    RuntimeRecordModel model = new RuntimeRecordModel(
        "model-1",
        "app-1",
        "form-1",
        2,
        active,
        1,
        requested,
        new PhysicalSchemaTarget(
            "model-1",
            new RuntimeMetadataTarget("source-1", null, "public", "records")
        )
    );
    RecordAccessContext access = access(Set.of("id", "record_version", "title"));

    RecordSelectPlan select = compiler.page(
        model,
        access,
        new RecordPageQuery(
            "model-1",
            SCHEMA,
            0,
            20,
            List.of("title"),
            new RecordFilter(
                RecordFilterOperator.EQUALS,
                "title",
                List.of("Open"),
                List.of()
            ),
            List.of(new RecordSort("title", RecordSortDirection.ASC))
        )
    );
    RecordSqlStatement update = compiler.mutation(
        model,
        access,
        new RecordUpdateMutation("update", "record-1", 0, Map.of("title", "Closed")),
        Instant.now()
    );

    assertThat(select.sql()).contains(
        "\"subject\"",
        "ORDER BY \"subject\" ASC"
    ).doesNotContain("\"title\"");
    assertThat(select.valueFields()).containsExactly("title");
    assertThat(update.sql()).contains("SET \"subject\" = ?").doesNotContain("\"title\"");
    var stableField = org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel.field("field-title");
    var scoped = new RecordAccessContext(access.actor(), access.resourceCode(), access.readableFields(),
        access.writableFields(), Set.of(), new DataScopeCondition("CUSTOM", "created_org_dept_id", "created_by",
            "user-a", Set.of(), false, List.of(new QueryDocument.Node("equals", stableField,
                TextNode.valueOf("Open"), List.of()))), "dept-a");
    var scopedPlan = compiler.page(model, scoped, new RecordPageQuery("model-1", SCHEMA, 0, 10,
        List.of("title"), null, List.of()));
    assertThat(scopedPlan.sql()).contains("\"subject\" = ?").doesNotContain(stableField);
    assertThatThrownBy(() -> compiler.mutation(model, scoped,
        new RecordUpdateMutation("update", "record-1", 0, Map.of("title", "Closed")), Instant.now()))
        .isInstanceOf(DnaRuntimeException.class);
    compiler.mutation(model, scoped, new RecordCreateMutation("create", Map.of("title", "Open")), Instant.now());
    assertThatThrownBy(() -> compiler.mutation(model, scoped,
        new RecordCreateMutation("create", Map.of("title", "Closed")), Instant.now()))
        .isInstanceOf(DnaRuntimeException.class);

  }

  @Test
  void usesTheDurableCreateIdentityAndProbesWithoutRowScope() {
    RuntimeRecordModel model = model();
    RecordAccessContext access = access(Set.of("id", "record_version", "title", "amount"));
    String recordId = "f28512b0-9e64-5c9a-90e8-c77441ad2303";

    RecordSqlStatement create = compiler.mutation(
        model,
        access,
        new RecordCreateMutation("create", Map.of("title", "First")),
        Instant.parse("2026-10-13T01:02:03Z"),
        recordId
    );
    RecordSelectPlan probe = compiler.creation(model, access, recordId);

    assertThat(create.recordId()).isEqualTo(recordId);
    assertThat(create.parameters()).contains(recordId);
    assertThat(probe.sql()).contains("\"tenant_id\" = ?", "\"id\" = ?")
        .doesNotContain("\"deleted_at\" IS NULL", "\"created_by\" = ?");
    assertThat(probe.parameters()).containsExactly("tenant-a", recordId);
  }

  private static RuntimeRecordModel model() {
    return new RuntimeRecordModel(
        "model-1",
        "app-1",
        "form-1",
        1,
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            List.of(
                new LogicalFieldDefinition(
                    "field-title", "title", "Title", LogicalFieldType.STRING,
                    false, 128, null, null, null
                ),
                new LogicalFieldDefinition(
                    "field-amount", "amount", "Amount", LogicalFieldType.DECIMAL,
                    true, null, 18, 2, null
                )
            ),
            List.of()
        ),
        new PhysicalSchemaTarget(
            "model-1",
            new RuntimeMetadataTarget("source-1", null, "public", "records")
        )
    );
  }

  private static RecordAccessContext access(final Set<String> readable) {
    return new RecordAccessContext(
        new RuntimeActor("tenant-a", "user-a"),
        "app:page",
        readable,
        Set.of("title", "amount"),
        Set.of(),
        new DataScopeCondition(
            "SELF",
            "created_org_dept_id",
            "created_by",
            "user-a",
            Set.of()
        ),
        "dept-a"
    );
  }
}
