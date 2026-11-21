package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

class RecordPublicationLockDiscoveryTest {
  private final String id = UUID.randomUUID().toString();
  private final String target = UUID.randomUUID().toString();
  private final RecordPublicationIntent intent = mock(RecordPublicationIntent.class);
  private final RuntimeRecordModel model =
      new RuntimeRecordModel(
          "source",
          "app",
          "form",
          2,
          schema("new_customer"),
          1,
          schema("old_customer"),
          new PhysicalSchemaTarget(
              "source", new RuntimeMetadataTarget("store", "db", "public", "source_table")));
  private final Map<String, RecordPublicationPatchCompiler.Resolved> models =
      Map.of("source", new RecordPublicationPatchCompiler.Resolved(model, 0));
  private final RecordPublicationLockDiscovery discovery =
      new RecordPublicationLockDiscovery(
          new RecordSqlCompiler(new StandardLogicalValueConverter()),
          new PhysicalSchemaCodec(new ObjectMapper().findAndRegisterModules()));

  @Test
  void currentReferenceProjectionIsTenantBoundAndRecheckedBeforeWritesAcrossColumnRename() {
    setup();
    var data = new AtomicReference<>(result(4, target));
    var plan =
        discovery.discover(
            intent,
            models,
            List.of(statement("tenant")),
            query -> {
              assertThat(query.sql())
                  .contains("\"new_customer\"", "\"tenant_id\"", "\"deleted_at\" IS NULL")
                  .doesNotContain("old_customer", target, id);
              assertThat(query.parameters()).contains("tenant", id);
              assertThat(query.valueFields()).containsExactly("new_customer");
              return data.get();
            });
    assertThat(plan.references())
        .containsExactly(new RecordReferenceCheck("customer", "source", id, target, false));
    plan.verifyReads().run();
    data.set(result(5, target));
    assertThatThrownBy(plan.verifyReads()::run).isInstanceOf(DnaRuntimeException.class);
    data.set(result(4, UUID.randomUUID().toString()));
    assertThatThrownBy(plan.verifyReads()::run).isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void nullReferencesHaveNoTargetLockAndMissingOrTruncatedInputCannotBeUsed() {
    setup();
    var data = new AtomicReference<>(result(4, null));
    var plan =
        discovery.discover(intent, models, List.of(statement("tenant")), query -> data.get());
    assertThat(plan.references()).isEmpty();
    plan.verifyReads().run();
    for (var invalid :
        List.of(
            new CalciteQueryResult(List.of(), List.of(), false, 0, 0, null),
            new CalciteQueryResult(List.of(), result(4, target).rows(), true, 1, 0, null))) {
      data.set(invalid);
      assertThatThrownBy(plan.verifyReads()::run).isInstanceOf(DnaRuntimeException.class);
    }
    assertThatThrownBy(
            () ->
                discovery.discover(
                    intent,
                    models,
                    List.of(statement("other")),
                    query -> {
                      throw new AssertionError("Wrong tenant must be rejected before reading");
                    }))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private void setup() {
    when(intent.key()).thenReturn(new RecordWriteFenceKey("tenant", "source"));
    when(intent.actorId()).thenReturn("publisher");
  }

  private RecordSqlStatement statement(String tenant) {
    return new RecordSqlStatement(
        "UPDATE",
        List.of(),
        RecordMutationType.UPDATE,
        id,
        5,
        Instant.now(),
        new RecordSqlStatement.Subject("source", tenant));
  }

  private CalciteQueryResult result(long version, String reference) {
    return new CalciteQueryResult(
        List.of(), List.of(java.util.Arrays.asList(id, version, reference)), false, 1, 0, null);
  }

  private static PhysicalSchemaDefinition schema(String code) {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(
            new LogicalFieldDefinition(
                "customer", code, "Customer", LogicalFieldType.UUID, true, null, null, null, null)),
        List.of(),
        null,
        true,
        List.of(new LogicalReferenceDefinition("customer", "customer", "target", false)));
  }
}
