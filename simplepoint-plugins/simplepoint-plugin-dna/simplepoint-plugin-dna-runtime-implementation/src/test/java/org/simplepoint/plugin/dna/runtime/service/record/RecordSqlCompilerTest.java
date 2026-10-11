package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
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

  private final RecordSqlCompiler compiler = new RecordSqlCompiler(
      new StandardLogicalValueConverter(new ObjectMapper())
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
