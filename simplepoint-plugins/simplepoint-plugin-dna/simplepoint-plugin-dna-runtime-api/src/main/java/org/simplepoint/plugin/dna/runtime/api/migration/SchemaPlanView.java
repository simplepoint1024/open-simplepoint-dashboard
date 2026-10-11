package org.simplepoint.plugin.dna.runtime.api.migration;

import java.util.List;

/** Read-only publication plan derived from server-owned physical placement. */
public record SchemaPlanView(
    String physicalModelId,
    int targetVersion,
    String dialectId,
    String actualChecksum,
    String targetChecksum,
    SchemaMigrationRisk risk,
    List<SchemaMigrationStepView> steps
) {

  /** Makes the plan immutable. */
  public SchemaPlanView {
    steps = steps == null ? List.of() : List.copyOf(steps);
  }
}
