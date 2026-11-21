package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;

class RecordReferenceCopyProjectionTest {
  private final RuntimeRecordModel parent = model("parent");
  private final RuntimeRecordModel child = model("child");
  private final RecordReferenceCopyOrigin origin = RecordReferenceCopyStoreTest.origin();
  private final RecordView root = row();
  private final RecordView first = row();
  private final RecordView second = row();

  @Test
  void currentTargetDenialRedactsCopyAndTransitiveOutputsWithoutChangingOtherRowsOrInputs() {
    var projection = projection();
    var source = new OwnedRecordView(root, Map.of("child", List.of(first, second)));
    var origins = List.of(captured("child", first));
    assertThat(projection.visibleOrigins(source, origins)).containsExactly(origin);
    var hidden = projection.project(source, origins, Set.of());
    assertThat(hidden.parent().values())
        .containsKeys("renamed_address", "unrelated")
        .doesNotContainKey("total");
    assertThat(hidden.details().get("child").getFirst().values()).containsOnlyKeys("unrelated");
    assertThat(hidden.details().get("child").get(1)).isEqualTo(second);
    assertThat(source.details().get("child").getFirst()).isEqualTo(first);
    assertThat(projection.project(source, origins, Set.of(origin))).isEqualTo(source);
  }

  @Test
  void sourceMasksAndHiddenFieldsNeverRequestTargetsAndCannotRevealDerivedOutputs() {
    for (var values :
        List.of(
            Map.<String, Object>of("renamed_address", "***", "total", "derived"),
            Map.<String, Object>of("total", "derived"))) {
      var masked = new RecordView(first.id(), first.version(), values);
      var source = new OwnedRecordView(root, Map.of("child", List.of(masked)));
      var origins = List.of(captured("child", first));
      assertThat(projection().visibleOrigins(source, origins)).isEmpty();
      // Even an overbroad target result does not widen the owning source projection.
      assertThat(
              projection()
                  .project(source, origins, Set.of(origin))
                  .details()
                  .get("child")
                  .getFirst()
                  .values())
          .isEmpty();
    }
  }

  @Test
  void omittedCollectionOriginsStillBlockAggregateOutputsWithoutReadingItsTargets() {
    var source = new OwnedRecordView(root, Map.of());
    var origins = List.of(captured("child", first));
    assertThat(projection().visibleOrigins(source, origins)).isEmpty();
    assertThat(projection().project(source, origins, Set.of()).parent().values())
        .containsKeys("renamed_address", "unrelated")
        .doesNotContainKey("total");
  }

  @Test
  void sameFieldIdentityInDifferentModelsAndStaleRowVersionsStayIsolated() {
    var source = new OwnedRecordView(root, Map.of("child", List.of(first)));
    var origins = List.of(captured(null, root));
    var projected = projection().project(source, origins, Set.of());
    assertThat(projected.parent().values()).doesNotContainKey("renamed_address");
    assertThat(projected.details().get("child").getFirst()).isEqualTo(first);
    var wrong = List.of(new RecordReferenceCopySnapshot(null, root.id(), 1, List.of(origin)));
    assertThatThrownBy(() -> projection().visibleOrigins(source, wrong))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> projection().project(source, wrong, Set.of(origin)))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void cyclesAcrossCurrentAndHistoricalGraphsTerminateWithoutRevealingConsumers() {
    var source = new OwnedRecordView(root, Map.of());
    var projection =
        new RecordReferenceCopyProjection(
            parent,
            List.of(),
            Map.of(
                field("parent", "total"), Set.of(field("parent", "address")),
                field("parent", "address"), Set.of(field("parent", "total"))));
    assertThat(
            projection.project(source, List.of(captured(null, root)), Set.of()).parent().values())
        .containsOnlyKeys("unrelated");
  }

  private RecordReferenceCopyProjection projection() {
    return new RecordReferenceCopyProjection(
        parent,
        List.of(child),
        Map.of(
            field("child", "total"), Set.of(field("child", "address")),
            field("parent", "total"), Set.of(field("child", "total"))));
  }

  @Test
  void combinesFrozenAndCurrentGraphsUsingStableFieldsAndPhysicalScopeOwners() {
    var historical =
        new RecordCalculationWriteSupport.Binding(
            null,
            parent,
            Map.of("lines", child),
            Map.of("parent.total", Set.of("lines.address")),
            null);
    var current =
        new RecordCalculationWriteSupport.Binding(
            null,
            parent,
            Map.of("new_lines", child),
            Map.of("parent.total", Set.of("parent.address", "new_lines.total")),
            null);
    var graph = RecordReferenceCopyProjection.dependencies(List.of(historical, current));
    assertThat(graph.get(field("parent", "total")))
        .containsExactlyInAnyOrder(
            field("child", "address"), field("parent", "address"), field("child", "total"));
    assertThatThrownBy(() -> graph.clear()).isInstanceOf(UnsupportedOperationException.class);
    var invalid =
        new RecordCalculationWriteSupport.Binding(
            null, parent, Map.of(), Map.of("parent.total", Set.of("unowned.address")), null);
    assertThatThrownBy(() -> RecordReferenceCopyProjection.dependencies(List.of(invalid)))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private RecordReferenceCopySnapshot captured(String detail, RecordView row) {
    return new RecordReferenceCopySnapshot(detail, row.id(), row.version(), List.of(origin));
  }

  private static RecordReferenceCopyProjection.Field field(String model, String field) {
    return new RecordReferenceCopyProjection.Field(model, field);
  }

  private static RecordView row() {
    return new RecordView(
        UUID.randomUUID().toString(),
        0,
        Map.of("renamed_address", "old copied value", "total", "derived", "unrelated", "visible"));
  }

  private static RuntimeRecordModel model(String id) {
    var schema =
        new PhysicalSchemaDefinition(
            org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion.CURRENT,
            List.of(
                definition("address", "renamed_address"),
                definition("total", "total"),
                definition("unrelated", "unrelated")),
            List.of());
    return new RuntimeRecordModel(
        id,
        "app",
        "form",
        1,
        schema,
        new PhysicalSchemaTarget(id, new RuntimeMetadataTarget("source", "catalog", "schema", id)));
  }

  private static LogicalFieldDefinition definition(String id, String code) {
    return new LogicalFieldDefinition(
        id, code, code, LogicalFieldType.STRING, true, 255, null, null, null);
  }
}
