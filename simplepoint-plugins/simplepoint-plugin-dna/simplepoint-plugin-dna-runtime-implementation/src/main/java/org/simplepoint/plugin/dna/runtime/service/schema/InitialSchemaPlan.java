package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalTableSnapshot;

/** Complete initial publication plan plus before and target structure snapshots. */
public record InitialSchemaPlan(
    String dialectId,
    DatabaseIdentity database,
    PhysicalTableSnapshot actualSnapshot,
    PhysicalTableSnapshot targetSnapshot,
    SchemaMigrationRisk risk,
    List<PlannedSchemaStep> steps
) {

  /** Makes the plan immutable. */
  public InitialSchemaPlan {
    if (dialectId == null || dialectId.isBlank() || database == null
        || actualSnapshot == null || targetSnapshot == null || risk == null) {
      throw new IllegalArgumentException("Initial schema plan is incomplete");
    }
    steps = steps == null ? List.of() : List.copyOf(steps);
  }
}
